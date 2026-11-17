"""Convert an Anime4K CNN upscaler shader (bloc97/Anime4K, MIT) into DSM's plain-text weight format,
and run it with numpy for host-side scoring.

Anime4K's mpv shaders are small CNNs written out as GLSL: each pass reads one or two 4-channel
textures (raw, or CReLU = max(x, 0) and max(-x, 0)), applies 3x3 or 1x1 convolutions as
`mat4(...) * go_k(dx, dy)` terms (GLSL mat4 is column-major: out[i] += m[4*j + i] * in[j]) plus a
bias, and a last Depth-to-Space pass adds the 2x pixel-shuffled residual to the bilinear-upscaled
input. The format the app reads (app/src/main/assets/anime4k/*.txt):

    a4k 1
    # <license / source lines>
    pass <out_tex> <terms>
    t <in_tex> <mode raw|pos|neg> <dx> <dy> <16 floats>   (repeated <terms> times)
    b <4 floats>
    d2s <tex> [<tex> <tex>]       one residual texture (broadcast to RGB) or one per RGB channel
    A pass whose out_tex is MAIN (Restore shaders) adds its result to the input at the same size.

  python tools/manga-eval/anime4k_convert.py convert SHADER.glsl OUT.txt
  python tools/manga-eval/anime4k_convert.py run WEIGHTS.txt IN.png OUT.png
"""
import re
import sys

import numpy as np
from PIL import Image

NUM = r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"
DEFINE = re.compile(r"#define (go?_\d+)(?:\(x_off, y_off\))? \((.*)\)\s*$")
TERM = re.compile(r"mat4\(([^)]*)\) \* (go?_\d+)(?:\((" + NUM + r"), (" + NUM + r")\))?")
BIAS = re.compile(r"result \+= vec4\(([^)]*)\);")


def parse_define(body):
    """'max(-(conv2d_tf_texOff(...)), 0.0)' -> ('conv2d_tf', 'neg')."""
    tex = re.search(r"(\w+?)_tex(?:Off)?\(", body).group(1)
    if body.startswith("max(-("):
        return tex, "neg"
    if body.startswith("max(("):
        return tex, "pos"
    return tex, "raw"


def convert(shader_path, out_path):
    text = open(shader_path, encoding="utf-8").read()
    header = [line for line in text.splitlines()[:25] if line.startswith("//") and not line.startswith("//!")]
    blocks = text.split("//!DESC ")[1:]
    lines = ["a4k 1", f"# Converted from {shader_path.split('/')[-1]} (github.com/bloc97/Anime4K)."]
    lines += ["# " + h.lstrip("/ ").rstrip() for h in header if h.strip("/ ")]
    for block in blocks:
        desc = block.splitlines()[0]
        save = re.search(r"//!SAVE (\w+)", block).group(1)
        binds = re.findall(r"//!BIND (\w+)", block)
        if "Depth-to-Space" in desc:
            lasts = [b for b in binds if b != "MAIN"]
            lines.append("d2s " + " ".join(lasts))
            continue
        defines = {}
        for line in block.splitlines():
            m = DEFINE.match(line.strip())
            if m:
                defines[m.group(1)] = parse_define(m.group(2))
        terms = []
        for m in TERM.finditer(block):
            floats = [float(x) for x in m.group(1).split(",")]
            assert len(floats) == 16, desc
            tex, mode = defines[m.group(2)]
            dx = int(float(m.group(3))) if m.group(3) else 0
            dy = int(float(m.group(4))) if m.group(4) else 0
            terms.append(f"t {tex} {mode} {dx} {dy} " + " ".join(repr(f) for f in floats))
        bias = BIAS.findall(block)[-1]
        lines.append(f"pass {save} {len(terms)}")
        lines += terms
        lines.append("b " + " ".join(repr(float(x)) for x in bias.split(",")))
    open(out_path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(out_path, sum(1 for l in lines if l.startswith("t ")), "terms", file=sys.stderr)


def load(path):
    net = []
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if not parts or parts[0] in ("a4k", "#"):
            continue
        if parts[0] == "pass":
            net.append({"out": parts[1], "terms": [], "bias": None})
        elif parts[0] == "t":
            net[-1]["terms"].append((parts[1], parts[2], int(parts[3]), int(parts[4]), np.array(parts[5:21], dtype=np.float32)))
        elif parts[0] == "b":
            net[-1]["bias"] = np.array(parts[1:5], dtype=np.float32)
        elif parts[0] == "d2s":
            net.append({"d2s": parts[1:]})
    return net


def shift(x, dx, dy):
    """x sampled at (col + dx, row + dy), clamp-to-edge."""
    h, w = x.shape[:2]
    rows = np.clip(np.arange(h) + dy, 0, h - 1)
    cols = np.clip(np.arange(w) + dx, 0, w - 1)
    return x[rows][:, cols]


def bilinear2x(x):
    h, w = x.shape[:2]
    out = np.asarray(Image.fromarray(x).resize((w * 2, h * 2), Image.BILINEAR)) if False else None
    ys = (np.arange(h * 2) + 0.5) / 2 - 0.5
    xs = (np.arange(w * 2) + 0.5) / 2 - 0.5
    y0 = np.clip(np.floor(ys).astype(int), 0, h - 1)
    x0 = np.clip(np.floor(xs).astype(int), 0, w - 1)
    y1 = np.clip(y0 + 1, 0, h - 1)
    x1 = np.clip(x0 + 1, 0, w - 1)
    fy = np.clip(ys - np.floor(ys), 0, 1)[:, None, None]
    fx = np.clip(xs - np.floor(xs), 0, 1)[None, :, None]
    top = x[y0][:, x0] * (1 - fx) + x[y0][:, x1] * fx
    bot = x[y1][:, x0] * (1 - fx) + x[y1][:, x1] * fx
    return top * (1 - fy) + bot * fy


def run(net, rgb):
    """rgb: float32 (H, W, 3) in 0..1 -> (2H, 2W, 3)."""
    h, w = rgb.shape[:2]
    tex = {"MAIN": np.concatenate([rgb, np.ones((h, w, 1), np.float32)], axis=2)}
    for p in net:
        if "d2s" in p:
            residual = np.zeros((h * 2, w * 2, 3), np.float32)
            for c in range(3):
                src = tex[p["d2s"][min(c, len(p["d2s"]) - 1)]]
                for i in range(4):
                    residual[i // 2 :: 2, i % 2 :: 2, c] = src[:, :, i]
            return bilinear2x(rgb) + residual
        acc = np.broadcast_to(p["bias"], (h, w, 4)).copy()
        for name, mode, dx, dy, m in p["terms"]:
            x = shift(tex[name], dx, dy)
            if mode == "pos":
                x = np.maximum(x, 0)
            elif mode == "neg":
                x = np.maximum(-x, 0)
            acc += x @ m.reshape(4, 4)  # row j of the reshape = column j of the GLSL mat4
        if p["out"] == "MAIN":  # Restore: residual on the input, same size
            tex["MAIN"] = tex["MAIN"] + acc
            rgb = tex["MAIN"][:, :, :3]
        else:
            tex[p["out"]] = acc
    return rgb


def main():
    if sys.argv[1] == "convert":
        convert(sys.argv[2], sys.argv[3])
    else:
        net = load(sys.argv[2])
        rgb = np.asarray(Image.open(sys.argv[3]).convert("RGB"), dtype=np.float32) / 255
        out = run(net, rgb)
        Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)).save(sys.argv[4])


if __name__ == "__main__":
    main()
