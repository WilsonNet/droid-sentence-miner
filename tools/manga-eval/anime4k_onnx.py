"""Build the app's AI page upscaler (app/src/main/assets/anime4k/*.onnx) from Anime4K weight files
(anime4k_convert.py convert): one or more networks chained in a single ONNX graph.

Input `page` [1, 3, H, W] (RGB 0..1, any size), output `out` [1, 3, 2H, 2W] when the chain ends
with an x2 upscaler. Each shader pass becomes Concat(CReLU parts) → edge Pad → Conv; Restore
passes add their residual to the input, the x2 pass is DepthToSpace(residual) + bilinear 2x
(half-pixel) of its input, exactly as the mpv shaders compute it.

  venv/bin/python tools/manga-eval/anime4k_onnx.py OUT.onnx restore_M.txt x2_M.txt
  venv/bin/python tools/manga-eval/anime4k_onnx.py --check OUT.onnx IN.png restore_M.txt x2_M.txt
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

import anime4k_convert as a4k

OPSET = 17


class Builder:
    def __init__(self):
        self.nodes = []
        self.inits = []
        self.count = 0

    def name(self, prefix):
        self.count += 1
        return f"{prefix}_{self.count}"

    def const(self, prefix, array):
        n = self.name(prefix)
        self.inits.append(numpy_helper.from_array(np.asarray(array), n))
        return n

    def node(self, op, inputs, **attrs):
        out = self.name(op.lower())
        self.nodes.append(helper.make_node(op, inputs, [out], **attrs))
        return out


def add_network(b, net, main):
    """Appends one Anime4K network reading tensor `main` (3 channels); returns its output tensor."""
    tex = {"MAIN": main}
    channels = {"MAIN": 3}
    feature_cache = {}

    def feature(name, mode):
        key = (name, mode)
        if key not in feature_cache:
            x = tex[name]
            if mode == "pos":
                x = b.node("Relu", [x])
            elif mode == "neg":
                x = b.node("Relu", [b.node("Neg", [x])])
            feature_cache[key] = x
        return feature_cache[key]

    for p in net:
        if "d2s" in p:
            parts = [b.node("DepthToSpace", [tex[t]], blocksize=2, mode="DCR") for t in p["d2s"]]
            residual = parts[0] if len(parts) == 1 else b.node("Concat", parts, axis=1)
            scales = b.const("scales", np.array([1, 1, 2, 2], dtype=np.float32))
            base = b.node("Resize", [tex["MAIN"], "", scales], mode="linear", coordinate_transformation_mode="half_pixel")
            return b.node("Add", [base, residual])
        groups = []
        for name, mode, _, _, _ in p["terms"]:
            if (name, mode) not in groups:
                groups.append((name, mode))
        offsets = {}
        cin = 0
        for g in groups:
            offsets[g] = cin
            cin += channels[g[0]]
        is3x3 = any(dx or dy for _, _, dx, dy, _ in p["terms"])
        k = 3 if is3x3 else 1
        weight = np.zeros((4, cin, k, k), dtype=np.float32)
        for name, mode, dx, dy, m in p["terms"]:
            base = offsets[(name, mode)]
            for j in range(channels[name]):
                for o in range(4):
                    weight[o, base + j, dy + k // 2, dx + k // 2] += m[4 * j + o]
        inputs = [feature(n, md) for n, md in groups]
        x = inputs[0] if len(inputs) == 1 else b.node("Concat", inputs, axis=1)
        if is3x3:
            pads = b.const("pads", np.array([0, 0, 1, 1, 0, 0, 1, 1], dtype=np.int64))
            x = b.node("Pad", [x, pads], mode="edge")
        out_ch = 3 if p["out"] == "MAIN" else 4
        w = b.const("w", weight[:out_ch])
        bias = b.const("b", p["bias"][:out_ch].astype(np.float32))
        y = b.node("Conv", [x, w, bias])
        if p["out"] == "MAIN":  # Restore: residual on the input
            tex["MAIN"] = b.node("Add", [tex["MAIN"], y])
            feature_cache.clear()
        else:
            tex[p["out"]] = y
            channels[p["out"]] = 4
    return tex["MAIN"]


def build(out_path, weight_files):
    b = Builder()
    x = "page"
    for path in weight_files:
        x = add_network(b, a4k.load(path), x)
    b.nodes.append(helper.make_node("Identity", [x], ["out"]))
    graph = helper.make_graph(
        b.nodes,
        "anime4k",
        [helper.make_tensor_value_info("page", TensorProto.FLOAT, [1, 3, "h", "w"])],
        [helper.make_tensor_value_info("out", TensorProto.FLOAT, [1, 3, "oh", "ow"])],
        b.inits,
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", OPSET)], producer_name="dsm-anime4k")
    model.doc_string = "Anime4K CNN (github.com/bloc97/Anime4K, MIT License, Copyright (c) 2019-2021 bloc97): " + ", ".join(
        p.split("/")[-1] for p in weight_files
    )
    model.ir_version = 8
    onnx.checker.check_model(model)
    onnx.save(model, out_path)
    print(out_path, sum(i.ByteSize() for i in b.inits), "weight bytes", file=sys.stderr)


def check(onnx_path, image_path, weight_files):
    import onnxruntime as ort
    from PIL import Image

    rgb = np.asarray(Image.open(image_path).convert("RGB"), dtype=np.float32) / 255
    ref = rgb
    for path in weight_files:
        ref = a4k.run(a4k.load(path), ref).astype(np.float32)
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    got = sess.run(None, {"page": rgb.transpose(2, 0, 1)[None]})[0][0].transpose(1, 2, 0)
    diff = np.abs(got - ref)
    print("max", diff.max(), "mean", diff.mean(), "interior max", diff[8:-8, 8:-8].max())


if __name__ == "__main__":
    if sys.argv[1] == "--check":
        check(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        build(sys.argv[1], sys.argv[2:])
