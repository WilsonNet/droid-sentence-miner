"""PP-OCRv6_manga (Kellenok, Apache-2.0) on the host: page -> text lines JSON.

Mirrors examples/onnx_openvino.py from the model repo with onnxruntime.
Usage: python -I run_ppmanga.py MODEL_DIR OUT.json page1.jpg [page2.jpg ...]
Output: {page_name: {"w","h","ms","lines":[{"box":[x1,y1,x2,y2],"text","vertical"}]}}
"""
import json
import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper


def load(model_dir, fp16=True):
    suffix = "_fp16" if fp16 else ""
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    det = ort.InferenceSession(os.path.join(model_dir, f"det/manga_det{suffix}.onnx"), opts)
    rec = ort.InferenceSession(os.path.join(model_dir, f"rec/manga_rec{suffix}.onnx"), opts)
    with open(os.path.join(model_dir, "ppocrv6_dict.txt"), encoding="utf-8") as f:
        vocab = ["blank"] + [line.strip("\r\n") for line in f] + [" "]
    return det, rec, vocab


def box_score(pred, box):
    h, w = pred.shape[:2]
    b = box.copy().astype(np.int32)
    xmin = int(np.clip(np.floor(b[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(b[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(b[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(b[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    b[:, 0] -= xmin
    b[:, 1] -= ymin
    cv2.fillPoly(mask, [b.reshape(-1, 2)], 1)
    return cv2.mean(pred[ymin:ymax + 1, xmin:xmax + 1], mask)[0]


def unclip(box, ratio):
    x, y = box[:, 0], box[:, 1]
    area = 0.5 * abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))
    perim = np.linalg.norm(np.roll(box, -1, axis=0) - box, axis=1).sum()
    if area <= 0 or perim <= 0:
        return None
    off = pyclipper.PyclipperOffset()
    off.AddPath(box.tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    paths = off.Execute(area * ratio / perim)
    return np.asarray(paths[0], dtype=np.float32) if len(paths) == 1 else None


def detect(det, img, long_side=960, thresh=0.15, box_thresh=0.25, unclip_ratio=1.4):
    H, W = img.shape[:2]
    if max(H, W) / min(H, W) > 2:
        ratio = min(1.0, (0.75 * long_side * long_side / (H * W)) ** 0.5)
    else:
        ratio = long_side / max(H, W)
    rh, rw = max(int(round(H * ratio / 32) * 32), 32), max(int(round(W * ratio / 32) * 32), 32)
    inp = cv2.resize(img, (rw, rh)).astype(np.float32) / 255.0
    inp = (inp - np.array([0.485, 0.456, 0.406])) / np.array([0.229, 0.224, 0.225])
    x = inp.transpose((2, 0, 1))[np.newaxis].astype(np.float32)
    in_type = det.get_inputs()[0].type
    if "float16" in in_type:
        x = x.astype(np.float16)
    pred = np.float32(det.run(None, {det.get_inputs()[0].name: x})[0][0, 0])
    contours, _ = cv2.findContours(((pred > thresh) * 255).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for cnt in contours:
        pts = cnt.squeeze(1)
        if pts.ndim != 2 or pts.shape[0] < 4:
            continue
        if box_score(pred, pts) < box_thresh:
            continue
        pts = unclip(pts.astype(np.float32), unclip_ratio)
        if pts is None or len(pts) == 0:
            continue
        _, (bw, bh), _ = cv2.minAreaRect(pts)
        if min(bw, bh) < 3:
            continue
        pts[:, 0] = np.clip(np.round(pts[:, 0] / rw * W), 0, W)
        pts[:, 1] = np.clip(np.round(pts[:, 1] / rh * H), 0, H)
        boxes.append(pts)
    return boxes


def recognize(rec, vocab, img, box):
    H, W = img.shape[:2]
    xs, ys = box[:, 0], box[:, 1]
    x1, y1, x2, y2 = max(0, int(min(xs))), max(0, int(min(ys))), min(W, int(max(xs))), min(H, int(max(ys)))
    crop = img[y1:y2, x1:x2]
    if crop.size == 0:
        return None
    vertical = crop.shape[0] > crop.shape[1]
    if vertical:
        crop = cv2.rotate(crop, cv2.ROTATE_90_COUNTERCLOCKWISE)
    ch, cw = crop.shape[:2]
    tw = max(16, int(round(48.0 * cw / max(1, ch))))
    c = cv2.resize(crop, (tw, 48)).astype(np.float32) / 255.0
    c = ((c - 0.5) / 0.5).transpose((2, 0, 1))[np.newaxis].astype(np.float32)
    if "float16" in rec.get_inputs()[0].type:
        c = c.astype(np.float16)
    logits = np.float32(rec.run(None, {rec.get_inputs()[0].name: c})[0][0])
    idx = np.argmax(logits, axis=-1)
    probs = logits.max(axis=-1)
    chars, confs, steps = [], [], []
    for t, k in enumerate(idx):
        if k != 0 and (t == 0 or k != idx[t - 1]):
            chars.append(vocab[k])
            confs.append(float(probs[t]))
            steps.append(t)
    # CTC step -> position along the line (rotated crop x == original y for vertical lines)
    n_steps = len(idx)
    length = (y2 - y1) if vertical else (x2 - x1)
    centers = [(t + 0.5) / n_steps * length for t in steps]
    glyphs = []
    for i, c in enumerate(centers):
        lo = 0 if i == 0 else (centers[i - 1] + c) / 2
        hi = length if i == len(centers) - 1 else (c + centers[i + 1]) / 2
        if vertical:
            glyphs.append([x1, int(y1 + lo), x2, int(y1 + hi)])
        else:
            glyphs.append([int(x1 + lo), y1, int(x1 + hi), y2])
    return {
        "box": [x1, y1, x2, y2],
        "text": "".join(chars),
        "vertical": bool(vertical),
        "conf": float(np.mean(confs)) if confs else 0.0,
        "glyphs": glyphs,
    }


def main():
    model_dir, out = sys.argv[1], sys.argv[2]
    det, rec, vocab = load(model_dir)
    result = {}
    for path in sys.argv[3:]:
        img = cv2.imread(path)
        t0 = time.time()
        lines = [r for b in detect(det, img) if (r := recognize(rec, vocab, img, b)) and r["text"].strip()]
        ms = int((time.time() - t0) * 1000)
        name = os.path.splitext(os.path.basename(path))[0]
        result[name] = {"w": img.shape[1], "h": img.shape[0], "ms": ms, "lines": lines}
        print(name, ms, "ms", len(lines), "lines", file=sys.stderr)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
