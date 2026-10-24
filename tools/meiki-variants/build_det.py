"""Build fixed-height variants of the meikiocr text-line detector (D-FINE) from the 960x544 model.

usage: build_det.py ORIG.onnx OUT_DIR H [H ...]      (H multiple of 32, <= 544)

The variant "sees the top Hs rows of a 544-row frame": everything stays normalized to the
original 960x544 frame, so outputs/boxes/orig_target_sizes are unchanged and the app only feeds a
1x3xHsx960 tensor (same top-left letterbox at the same scale, just fewer zero rows).

Changes (all constants were folded for 17x30 / 34x60 / 68x120 feature grids):
  * input dim 544 -> Hs
  * AIFI pos-embed [1,510,128] -> first Hs/32*30 rows (tokens are flattened row-major, so token
    (r,c) keeps exactly the embedding it had in the 544 model)
  * AIFI reshape constants 510 -> Hs/32*30, CCFF reshape [-1,128,17,30] -> [-1,128,Hs/32,30]
  * decoder anchors [1,10710,4] / valid mask [1,10710,1] -> per-level first H_l*W_l rows
    (same normalized-to-544 anchor values)
  * level split offsets 8160/10200/10710 and value spatial shapes (68,120)/(34,60)/(17,30)
  * deformable-attention sampling locations: y multiplied by 544/Hs before the 2*loc-1 grid
    transform, so a 544-normalized y lands on the same pixel of the shorter feature map
"""
import os
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper as nh

H0, W0 = 544, 960
STRIDES = (8, 16, 32)


def build(src, dst, hs):
    assert hs % 32 == 0 and 32 <= hs <= H0
    m = onnx.load(src)
    g = m.graph
    inits = {t.name: t for t in g.initializer}

    def arr(name):
        return nh.to_array(inits[name])

    def put(name, value):
        t = inits[name]
        t.CopyFrom(nh.from_array(np.asarray(value, dtype=nh.to_array(t).dtype), name))

    hl = [hs // s for s in STRIDES]
    wl = [W0 // s for s in STRIDES]
    hl0 = [H0 // s for s in STRIDES]
    n_l = [h * w for h, w in zip(hl, wl)]
    n0 = [h * w for h, w in zip(hl0, wl)]
    tok = n_l[2]

    # input
    g.input[0].type.tensor_type.shape.dim[2].dim_value = hs
    del g.value_info[:]

    # encoder
    put('/model/encoder/Unsqueeze_2_output_0', arr('/model/encoder/Unsqueeze_2_output_0')[:, :tok])
    put('_v_1627', [tok, -1, 16])
    put('_v_1633', [tok, -1, 128])
    put('/model/encoder/Concat_4_output_0', [-1, 128, hl[2], wl[2]])
    put('/model/encoder/Unsqueeze_3_output_0', [hl[2]])
    put('/model/encoder/Unsqueeze_4_output_0', [wl[2]])

    # decoder anchors + valid mask: keep each level's first rows
    idx = np.concatenate([sum(n0[:i]) + np.arange(n_l[i]) for i in range(3)])
    put('/model/decoder/Expand_6_output_0', arr('/model/decoder/Expand_6_output_0')[:, idx])
    put('/model/decoder/Cast_11_output_0', arr('/model/decoder/Cast_11_output_0')[:, idx])
    put('/model/decoder/Unsqueeze_3_output_0', [n_l[0]])
    put('/model/decoder/decoder/Add_1_output_0', [n_l[0] + n_l[1]])
    put('/model/decoder/decoder/Add_2_output_0', [sum(n_l)])
    p = '/model/decoder/decoder/layers.0/cross_attn/'
    put(p + 'Unsqueeze_7_output_0', [hl[0]])
    put(p + 'Unsqueeze_8_output_0', [wl[0]])
    put(p + 'Unsqueeze_11_output_0', [hl[1]])
    put(p + 'Unsqueeze_12_output_0', [wl[1]])

    # sampling locations (x, y) in 544-normalized coords -> y * 544/Hs
    yscale = nh.from_array(np.array([1.0, H0 / hs], np.float32), 'dsm_loc_yscale')
    g.initializer.append(yscale)
    new_nodes = []
    for n in g.node:
        if n.op_type == 'Mul' and n.name.endswith('/cross_attn/Mul_3'):
            src_loc = n.input[0]
            assert src_loc.endswith('/cross_attn/Add_output_0'), src_loc
            scaled = src_loc + '_yscaled'
            new_nodes.append(helper.make_node('Mul', [src_loc, 'dsm_loc_yscale'], [scaled],
                                              name=n.name + '_dsm_yscale'))
            n.input[0] = scaled
        new_nodes.append(n)
    assert sum(x.name.endswith('_dsm_yscale') for x in new_nodes) == 3
    del g.node[:]
    g.node.extend(new_nodes)

    onnx.checker.check_model(m)
    onnx.save(m, dst)


if __name__ == '__main__':
    src, out_dir, *hs = sys.argv[1:]
    os.makedirs(out_dir, exist_ok=True)
    for h in map(int, hs):
        dst = os.path.join(out_dir, f'det_960x{h}.onnx')
        build(src, dst, h)
        print(dst)
