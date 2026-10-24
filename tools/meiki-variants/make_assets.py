"""Build the meikiocr model variants the app ships in its assets.

usage: make_assets.py MODELS_DIR OUT_DIR

MODELS_DIR holds the two models the app downloads from Hugging Face
(meiki.text.detect.v0.1.960x544.onnx, meiki.text.rec.v0.960x32.onnx). Variants:
  * detector at shorter input heights (build_det.py), for wide, short crops like subtitle bands;
  * recognizer accepting any width (build_rec_dynamic.py), so short lines skip the padding.

A variant keeps every weight of its original, so instead of storing them again its large
initializers are ONNX external data pointing at their bytes inside the original file. Each
variant then is a graph of well under 1 MB: copy it next to the original and load it by path.
Prints the originals' SHA-256 (MeikiModels.*_SHA256): the app only uses the variants when the
downloaded files match.

App assets: make_assets.py MODELS_DIR app/src/main/assets/meiki
"""
import hashlib
import os
import sys

import onnx

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_det  # noqa: E402
import build_rec_dynamic  # noqa: E402

DETECTOR = "meiki.text.detect.v0.1.960x544.onnx"
RECOGNIZER = "meiki.text.rec.v0.960x32.onnx"
DET_HEIGHTS = (160, 224, 288, 352, 448)
REC_DYNAMIC = "meiki.text.rec.v0.32xW.onnx"


def externalize(src, path):
    """Points [path]'s initializers that are byte-identical to [src]'s at their bytes in [src]."""
    blob = open(src, "rb").read()
    offsets = {}
    for t in onnx.load(src).graph.initializer:
        if len(t.raw_data) < 4096:
            continue
        at = blob.find(t.raw_data)
        assert at >= 0 and blob.find(t.raw_data, at + 1) < 0, t.name
        offsets[t.name] = (at, t.raw_data)
    m = onnx.load(path)
    for t in m.graph.initializer:
        o = offsets.get(t.name)
        if o and t.raw_data == o[1]:
            onnx.external_data_helper.set_external_data(t, os.path.basename(src), offset=o[0], length=len(o[1]))
            t.ClearField("raw_data")
            t.data_location = onnx.TensorProto.EXTERNAL
    onnx.save(m, path)
    print(path, os.path.getsize(path))
    return hashlib.sha256(blob).hexdigest()


def main():
    models, out = sys.argv[1:]
    os.makedirs(out, exist_ok=True)
    det = os.path.join(models, DETECTOR)
    rec = os.path.join(models, RECOGNIZER)
    for h in DET_HEIGHTS:
        path = os.path.join(out, f"meiki.text.detect.v0.1.960x{h}.onnx")
        build_det.build(det, path, h)
        det_sha = externalize(det, path)
    path = os.path.join(out, REC_DYNAMIC)
    build_rec_dynamic.build(rec, path)
    rec_sha = externalize(rec, path)
    print("detector sha256  ", det_sha)
    print("recognizer sha256", rec_sha)


if __name__ == "__main__":
    main()
