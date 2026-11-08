"""Desktop PaddleOCR (PP-OCRv5/v6 ONNX) over a folder, the same way the app runs it.

usage: run_paddle.py DET_DIR REC_DIR IMAGES_DIR OUT.json [--det-limit 960] [--threads 4]
DET_DIR/REC_DIR are PaddlePaddle Hugging Face ONNX repos (inference.onnx + inference.yml), e.g.
PaddlePaddle/PP-OCRv6_small_det_onnx and PaddlePaddle/PP-OCRv6_small_rec_onnx. Rows are read
top-to-bottom, boxes on one row left-to-right, rows joined by a space (Latin languages).
Prints median det/rec/total ms per image.
"""
import argparse
import json
import os
import statistics
import time

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper
import yaml

ap = argparse.ArgumentParser()
ap.add_argument("det")
ap.add_argument("rec")
ap.add_argument("images")
ap.add_argument("out")
ap.add_argument("--det-limit", type=int, default=960)
ap.add_argument("--threads", type=int, default=4)
args = ap.parse_args()

opts = ort.SessionOptions()
opts.intra_op_num_threads = args.threads
opts.log_severity_level = 3
det = ort.InferenceSession(os.path.join(args.det, "inference.onnx"), opts)
rec = ort.InferenceSession(os.path.join(args.rec, "inference.onnx"), opts)
det_cfg = yaml.safe_load(open(os.path.join(args.det, "inference.yml")))["PostProcess"]
rec_yml = yaml.safe_load(open(os.path.join(args.rec, "inference.yml")))
chars = [""] + rec_yml["PostProcess"]["character_dict"] + [" "]


def detect(img):
    h, w = img.shape[:2]
    scale = min(1.0, args.det_limit / max(h, w))
    nh, nw = max(32, int(round(h * scale / 32)) * 32), max(32, int(round(w * scale / 32)) * 32)
    x = cv2.resize(img, (nw, nh)).astype(np.float32) / 255.0
    x = (x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    prob = det.run(None, {"x": x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
    mask = (prob > det_cfg["thresh"]).astype(np.uint8)
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for c in contours:
        if len(c) < 4:
            continue
        rect = cv2.minAreaRect(c)
        if min(rect[1]) < 3:
            continue
        m = np.zeros_like(mask)
        cv2.fillPoly(m, [c], 1)
        if prob[m.astype(bool)].mean() < det_cfg["box_thresh"]:
            continue
        pts = cv2.boxPoints(rect)
        area, length = cv2.contourArea(pts), cv2.arcLength(pts, True)
        off = pyclipper.PyclipperOffset()
        off.AddPath(pts.astype(np.int64).tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
        grown = off.Execute(area * det_cfg["unclip_ratio"] / max(length, 1e-6))
        if not grown:
            continue
        rect = cv2.minAreaRect(np.array(grown[0]))
        if min(rect[1]) < 5:
            continue
        pts = cv2.boxPoints(rect) * [w / nw, h / nh]
        boxes.append(order(pts))
    return boxes


def order(pts):
    pts = pts[np.argsort(pts[:, 0])]
    left, right = pts[:2], pts[2:]
    tl, bl = left[np.argsort(left[:, 1])]
    tr, br = right[np.argsort(right[:, 1])]
    return np.array([tl, tr, br, bl], dtype=np.float32)


def crop(img, box):
    w = int(max(np.linalg.norm(box[0] - box[1]), np.linalg.norm(box[2] - box[3])))
    h = int(max(np.linalg.norm(box[0] - box[3]), np.linalg.norm(box[1] - box[2])))
    m = cv2.getPerspectiveTransform(box, np.float32([[0, 0], [w, 0], [w, h], [0, h]]))
    out = cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REPLICATE)
    return np.rot90(out) if h > w * 1.5 else out


def recognize(line):
    h, w = line.shape[:2]
    nw = max(16, int(np.ceil(48 * w / h)))
    x = cv2.resize(line, (nw, 48)).astype(np.float32) / 255.0
    x = (x - 0.5) / 0.5
    probs = rec.run(None, {"x": x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0]
    idx, text, prev, scores = probs.argmax(1), [], 0, []
    for t, i in enumerate(idx):
        if i != prev and i != 0:
            text.append(chars[i] if i < len(chars) else "")
            scores.append(probs[t, i])
        prev = i
    return "".join(text), float(np.mean(scores)) if scores else 0.0


result, det_ms, rec_ms = {}, [], []
for name in sorted(os.listdir(args.images)):
    img = cv2.imread(os.path.join(args.images, name))
    t0 = time.perf_counter()
    boxes = detect(img)
    t1 = time.perf_counter()
    reads = []
    for b in boxes:
        text, score = recognize(crop(img, b))
        if text.strip() and score > 0.5:
            reads.append((b[:, 1].mean(), b[:, 0].min(), b[:, 1].ptp() if hasattr(b[:, 1], "ptp") else np.ptp(b[:, 1]), text))
    t2 = time.perf_counter()
    reads.sort()
    rows = []
    for y, x, hh, text in reads:
        if rows and abs(rows[-1][0] - y) < hh * 0.5:
            rows[-1][1].append((x, text))
        else:
            rows.append([y, [(x, text)]])
    result[name] = " ".join(" ".join(t for _, t in sorted(r[1])) for r in rows)
    det_ms.append((t1 - t0) * 1000)
    rec_ms.append((t2 - t1) * 1000)
print(f"{os.path.basename(args.det.rstrip('/'))} + {os.path.basename(args.rec.rstrip('/'))}: {len(result)} images, "
      f"median det {statistics.median(det_ms):.0f} ms, rec {statistics.median(rec_ms):.0f} ms")
json.dump(result, open(args.out, "w"), ensure_ascii=False, indent=0)
