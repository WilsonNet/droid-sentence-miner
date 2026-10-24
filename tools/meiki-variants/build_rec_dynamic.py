"""Make meiki.text.rec.v0.960x32.onnx accept any input width W (multiple of 32, 32..960).

usage: python build_rec_dynamic.py SRC.onnx OUT.onnx

Everything stays in the original 960x32 frame, so callers keep orig_target_sizes = (960, 32), the
boxes are in 960x32 pixels and the effective_w post-processing is unchanged:
  * anchors + valid mask: the original 960-normalized entries of the first W/8, W/16, W/32 columns
    of each level (sliced in-graph from the baked [1,630,*] constants);
  * level sizes / split points of the flattened memory: computed from W;
  * deformable-attention sampling grid: x' = (960/W) * x + (960/W - 1) before every GridSample, so a
    960-normalized location samples the same pixel of the narrower feature map;
  * AIFI (global self-attention on the stride-32 level) still runs on the full 30-token sequence:
    the missing columns are filled with the tokens the original produces for a black line (its
    zero padding), and the output is cropped back to W/32. Without this the attention context
    changes and the model invents low-confidence punctuation/characters past the text.
"""
import sys
import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper, TensorProto

FULL_W, H = 960, 32
CONV32 = "/model/encoder/input_proj.2/conv/Conv_output_0"
REPLACE = {  # baked int64 constants -> expression of W
    "/model/decoder/decoder/layers.0/cross_attn/Unsqueeze_8_output_0": "w8",                # 120
    "/model/decoder/decoder/layers.0/cross_attn/Unsqueeze_12_output_0": "w16",              # 60
    "/model/encoder/encoder.0/layers.0/self_attn/Unsqueeze_output_0": "w32",                # 30
    "/model/decoder/Unsqueeze_3_output_0": "n0",                                            # 480
    "/model/decoder/decoder/Add_1_output_0": "n01",                                         # 600
    "/model/decoder/decoder/Add_2_output_0": "n012",                                        # 630
    "/model/decoder/Expand_6_output_0": "anchors",                                          # [1,630,4]
    "/model/decoder/Cast_11_output_0": "valid",                                             # [1,630,1]
}


def black_tokens(src):
    m = onnx.load(src)
    m.graph.output.append(helper.make_tensor_value_info(CONV32, TensorProto.FLOAT, None))
    s = ort.InferenceSession(m.SerializeToString(), providers=["CPUExecutionProvider"])
    return s.run([CONV32], {"images": np.zeros((1, 3, H, FULL_W), np.float32),
                            "orig_target_sizes": np.array([[FULL_W, H]], np.int64)})[0]  # [1,128,1,30]


def build(src, out):
    m = onnx.load(src)
    g = m.graph
    inits = {t.name: t for t in g.initializer}
    consts = {n.output[0]: n for n in g.node if n.op_type == "Constant"}

    def value(name):
        return numpy_helper.to_array(inits[name] if name in inits else consts[name].attribute[0].t)

    full_anchors = value("/model/decoder/Expand_6_output_0")
    full_valid = value("/model/decoder/Cast_11_output_0")
    # drop the baked tensors; new nodes below produce the same names
    keep_nodes = [n for n in g.node if not (n.op_type == "Constant" and n.output[0] in REPLACE)]
    keep_inits = [t for t in g.initializer if t.name not in REPLACE]
    del g.initializer[:]
    g.initializer.extend(keep_inits)

    def c(name, arr):
        g.initializer.append(numpy_helper.from_array(np.asarray(arr), "dsm_" + name))
        return "dsm_" + name

    c("full_anchors", full_anchors.astype(np.float32))
    c("full_valid", full_valid.astype(np.float32))
    c("black", black_tokens(src).astype(np.float32))
    for v in (0, 1, 2, 3, 4, 8, 16, 32, 30, 480, 600, 630):
        c(f"i{v}", np.array([v], np.int64))
    c("f960", np.array([float(FULL_W)], np.float32))
    c("f1", np.array([1.0], np.float32))
    c("f0", np.array([0.0], np.float32))
    c("shape_lvl0", np.array([1, 4, 120, -1], np.int64))
    c("shape_lvl1", np.array([1, 2, 60, -1], np.int64))
    c("shape_lvl2", np.array([1, 1, 30, -1], np.int64))
    c("flat_anchors", np.array([1, -1, 4], np.int64))
    c("flat_valid", np.array([1, -1, 1], np.int64))

    N = []
    def node(op, ins, outs, **kw):
        N.append(helper.make_node(op, ins, outs if isinstance(outs, list) else [outs], name="dsm_" + (outs if isinstance(outs, str) else outs[0]), **kw))
        return outs

    node("Shape", ["images"], "dsm_shape")
    node("Slice", ["dsm_shape", "dsm_i3", "dsm_i4"], "dsm_W")                 # [1] int64
    node("Div", ["dsm_W", "dsm_i8"], REPLACE_name("w8"))
    node("Div", ["dsm_W", "dsm_i16"], REPLACE_name("w16"))
    node("Div", ["dsm_W", "dsm_i32"], REPLACE_name("w32"))
    w8, w16, w32 = REPLACE_name("w8"), REPLACE_name("w16"), REPLACE_name("w32")
    node("Mul", [w8, "dsm_i4"], REPLACE_name("n0"))
    node("Mul", [w16, "dsm_i2"], "dsm_s1")
    node("Add", [REPLACE_name("n0"), "dsm_s1"], REPLACE_name("n01"))
    node("Add", [REPLACE_name("n01"), w32], REPLACE_name("n012"))

    # anchors / valid mask: per level reshape [1,h,wfull,C] -> keep first w columns -> flatten
    for src_name, dst in (("dsm_full_anchors", "anchors"), ("dsm_full_valid", "valid")):
        parts = []
        for lvl, (a, b, wn) in enumerate(((0, 480, w8), (480, 600, w16), (600, 630, w32))):
            p = f"dsm_{dst}_l{lvl}"
            node("Slice", [src_name, f"dsm_i{a}", f"dsm_i{b}", "dsm_i1"], p + "_s")
            node("Reshape", [p + "_s", f"dsm_shape_lvl{lvl}"], p + "_r")
            node("Slice", [p + "_r", "dsm_i0", wn, "dsm_i2"], p + "_c")
            node("Reshape", [p + "_c", f"dsm_flat_{dst}"], p)
            parts.append(p)
        node("Concat", parts, REPLACE_name(dst), axis=1)

    # sampling-grid rescale constants
    node("Cast", ["dsm_W"], "dsm_Wf", to=TensorProto.FLOAT)
    node("Div", ["dsm_f960", "dsm_Wf"], "dsm_k")
    node("Concat", ["dsm_k", "dsm_f1"], "dsm_grid_scale", axis=0)
    node("Sub", ["dsm_k", "dsm_f1"], "dsm_km1")
    node("Concat", ["dsm_km1", "dsm_f0"], "dsm_grid_shift", axis=0)

    # black tokens for the missing stride-32 columns
    node("Slice", ["dsm_black", w32, "dsm_i30", "dsm_i3"], "dsm_black_tail")   # [1,128,1,30-w32]

    body = []
    for n in keep_nodes:
        for i, x in enumerate(n.input):
            if x in REPLACE:
                n.input[i] = REPLACE_name(REPLACE[x])
            elif x == CONV32:
                n.input[i] = CONV32 + "_dsm_pad"
        body.append(n)
        if CONV32 in n.output:
            # batch-broadcast the black tail: zeros [N,128,1,1] + [1,128,1,30-w32]
            body += [
                helper.make_node("Slice", [CONV32, "dsm_i0", "dsm_i1", "dsm_i3"], ["dsm_col0"], name="dsm_col0"),
                helper.make_node("Mul", ["dsm_col0", "dsm_f0"], ["dsm_zcol"], name="dsm_zcol"),
                helper.make_node("Add", ["dsm_zcol", "dsm_black_tail"], ["dsm_black_b"], name="dsm_black_b"),
                helper.make_node("Concat", [CONV32, "dsm_black_b"], [CONV32 + "_dsm_pad"], axis=3, name="dsm_pad"),
            ]
        if n.name == "/model/encoder/Reshape_3":  # AIFI output [N,128,1,30] -> crop to w32
            o = n.output[0]
            n.output[0] = o + "_dsm_full"
            body.append(helper.make_node("Slice", [o + "_dsm_full", "dsm_i0", w32, "dsm_i3"], [o], name="dsm_crop"))
        if n.op_type == "Sub" and n.name.endswith("/cross_attn/Sub"):
            o = n.output[0]
            n.output[0] = o + "_dsm_raw"
            body += [
                helper.make_node("Mul", [o + "_dsm_raw", "dsm_grid_scale"], [o + "_dsm_m"], name=n.name + "_dsm_mul"),
                helper.make_node("Add", [o + "_dsm_m", "dsm_grid_shift"], [o], name=n.name + "_dsm_add"),
            ]
    del g.node[:]
    g.node.extend(N + body)
    d = g.input[0].type.tensor_type.shape.dim[3]
    d.ClearField("dim_value"); d.dim_param = "W"
    del g.value_info[:]
    m = onnx.shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    onnx.save(m, out)


def REPLACE_name(key):
    return "dsm_v_" + key


if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2])
