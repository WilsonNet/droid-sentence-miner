"""ogkalu comic-text-and-bubble-detector (RT-DETR-v2, Apache-2.0) on the host: page -> regions.

Usage: python -I run_bubbles.py MODEL.onnx OUT.json page.jpg [...]
Output: {page: {"w","h","ms","regions":[{"cls": 0 bubble | 1 text_bubble | 2 text_free, "score", "box"}]}}
"""
import json
import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort


def detect(sess, img, thresh=0.3):
    h, w = img.shape[:2]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    x = cv2.resize(rgb, (640, 640), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0
    x = x.transpose(2, 0, 1)[None]
    labels, boxes, scores = sess.run(None, {"images": x, "orig_target_sizes": np.array([[w, h]], dtype=np.int64)})
    out = []
    for lab, box, sc in zip(labels[0], boxes[0], scores[0]):
        if sc < thresh:
            continue
        x1, y1, x2, y2 = [int(round(v)) for v in box]
        out.append({"cls": int(lab), "score": round(float(sc), 3), "box": [max(0, x1), max(0, y1), min(w, x2), min(h, y2)]})
    return out


def main():
    model, out = sys.argv[1:3]
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(model, opts)
    res = {}
    for path in sys.argv[3:]:
        img = cv2.imread(path)
        t0 = time.time()
        regions = detect(sess, img)
        ms = int((time.time() - t0) * 1000)
        name = os.path.splitext(os.path.basename(path))[0]
        res[name] = {"w": img.shape[1], "h": img.shape[0], "ms": ms, "regions": regions}
        print(name, ms, "ms", len(regions), file=sys.stderr)
    json.dump(res, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
