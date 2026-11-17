"""Benchmark the reader's page filter (MangaPageEnhancer) on the device through /manga_enhance.

Two runs, both need `adb forward tcp:9099 tcp:9099` and the debug app in the foreground.

quality: each reference page is shrunk by FACTOR (box filter), lightly blurred and saved as a JPEG
(a low-resolution, soft scan), then every method scales it back to the reference size on the
device. Scores against the reference: PSNR and SSIM on luma, plus edge sharpness (mean Sobel
gradient on the reference's edge pixels, output / reference; 1.0 = as crisp as the original).
Tone filters (soft/crisp/strong) change paper and ink levels on purpose, which PSNR/SSIM count as
error, so compare resamplers on `bilinear`/`lanczos`/`cas*` and read the tone filters' edge column.
`&ai=1` adds the Anime4K 2x step (PageUpscaler).

speed: every page at real reader slot sizes (Pad 7 portrait, Galaxy S24 portrait), median of N runs.

  python -I tools/manga-eval/enhance_bench.py quality PAGES_DIR OUT_DIR op042 bk013 ...
  python -I tools/manga-eval/enhance_bench.py speed PAGES_DIR OUT_DIR op042 bk013 ...
"""
import json
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

PKG = "io.github.wilsonnet.droidsentenceminer.debug"
SERIAL = "c2bd6564"
FACTOR = 2
BLUR = 0.6
JPEG_QUALITY = 80
QUALITY_METHODS = ["bilinear", "lanczos", "cas&order=before", "cas&order=after", "soft", "crisp", "strong", "cas&ai=1", "crisp&ai=1"]
SPEED_METHODS = ["bilinear", "soft", "crisp", "strong", "crisp&ai=1"]
# Reader slot sizes in px: the page area of a portrait screen.
SLOTS = {"pad7": (2136, 3200), "s24": (1080, 2340)}
RUNS = 3


def adb(*args, stdin=None):
    return subprocess.run(["adb", "-s", SERIAL, *args], input=stdin, capture_output=True, check=True).stdout


def push(local, name):
    """Copies a host file into the app's files/manga-eval/bench/ and returns its device path."""
    adb("push", str(local), f"/data/local/tmp/{name}")
    target = f"/data/data/{PKG}/files/manga-eval/bench/{name}"
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p files/manga-eval/bench && cp /data/local/tmp/{name} files/manga-eval/bench/{name}'")
    return target


def pull(name, dest):
    data = adb("exec-out", "run-as", PKG, "cat", f"files/enhance/{name}.png")
    Path(dest).write_bytes(data)


def call(query):
    with urllib.request.urlopen(f"http://localhost:9099/manga_enhance?{query}", timeout=300) as r:
        return json.loads(r.read())


def luma(path):
    return np.asarray(Image.open(path).convert("L"), dtype=np.float64)


def psnr(a, b):
    mse = np.mean((a - b) ** 2)
    return 99.0 if mse == 0 else 10 * np.log10(255.0**2 / mse)


def ssim(a, b):
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    blur = lambda x: ndimage.gaussian_filter(x, 1.5, truncate=3.5)
    mu_a, mu_b = blur(a), blur(b)
    var_a = blur(a * a) - mu_a**2
    var_b = blur(b * b) - mu_b**2
    cov = blur(a * b) - mu_a * mu_b
    s = ((2 * mu_a * mu_b + c1) * (2 * cov + c2)) / ((mu_a**2 + mu_b**2 + c1) * (var_a + var_b + c2))
    return float(s.mean())


def gradient(x):
    return np.hypot(ndimage.sobel(x, axis=0), ndimage.sobel(x, axis=1))


def quality(pages_dir, out_dir, pages):
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = {m: [] for m in QUALITY_METHODS}
    for page in pages:
        ref_img = Image.open(pages_dir / f"{page}.jpg").convert("RGB")
        w, h = ref_img.size
        small = ref_img.resize((w // FACTOR, h // FACTOR), Image.BOX).filter(ImageFilter.GaussianBlur(BLUR))
        degraded = out_dir / f"{page}_low.jpg"
        small.save(degraded, quality=JPEG_QUALITY)
        device = push(degraded, f"{page}_low.jpg")
        ref = luma(pages_dir / f"{page}.jpg")
        ref_grad = gradient(ref)
        edges = ref_grad > np.percentile(ref_grad, 90)
        for method in QUALITY_METHODS:
            name = f"{page}_{method.replace('&order=', '-').replace('&ai=1', '-ai')}"
            d = call(f"file={urllib.parse.quote(device)}&w={w}&h={h}&method={method}&n=1&out={name}")
            pull(name, out_dir / f"{name}.png")
            out = luma(out_dir / f"{name}.png")
            edge = float(gradient(out)[edges].mean() / ref_grad[edges].mean())
            rows[method].append((psnr(ref, out), ssim(ref, out), edge))
            print(page, method, f"psnr {rows[method][-1][0]:.2f} ssim {rows[method][-1][1]:.4f} edge {edge:.3f}", d.get("median"), file=sys.stderr)
    print(f"| Method | PSNR (dB) | SSIM | Edge sharpness |\n| --- | --- | --- | --- |")
    for method, values in rows.items():
        p, s, e = np.mean(values, axis=0)
        print(f"| {method.replace('&order=', ' ').replace('&ai=1', ' + AI')} | {p:.2f} | {s:.4f} | {e:.3f} |")


def speed(pages_dir, out_dir, pages):
    print("| Page | Slot | " + " | ".join(SPEED_METHODS) + " |")
    print("| --- | --- | " + " | ".join("---" for _ in SPEED_METHODS) + " |")
    for page in pages:
        device = push(pages_dir / f"{page}.jpg", f"{page}.jpg")
        w, h = Image.open(pages_dir / f"{page}.jpg").size
        for slot, (sw, sh) in SLOTS.items():
            fit = min(sw / w, sh / h)
            tw, th = round(w * fit), round(h * fit)
            cells = []
            kind = ""
            for method in SPEED_METHODS:
                call(f"file={urllib.parse.quote(device)}&w={tw}&h={th}&method={method}&n=1")  # warm-up
                d = call(f"file={urllib.parse.quote(device)}&w={tw}&h={th}&method={method}&n={RUNS}")
                cells.append(d["median"])
                if method != "bilinear":
                    kind = "mono" if d["mono"] else "colour"
            print(f"| {page} ({w}×{h}, {kind}) | {slot} {tw}×{th} | " + " | ".join(cells) + " |")


def main():
    mode, pages_dir, out_dir = sys.argv[1:4]
    pages = sys.argv[4:]
    {"quality": quality, "speed": speed}[mode](Path(pages_dir), Path(out_dir), pages)


if __name__ == "__main__":
    main()
