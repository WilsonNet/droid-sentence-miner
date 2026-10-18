"""Run the reference meikiocr pipeline (desktop, onnxruntime) over a folder of images.

usage: run_meiki.py MODELS_DIR IMAGES_DIR OUT.json [--band]
  --band   crop each image to its bottom 22% (subtitle band) first

Needs `pip install meikiocr onnxruntime opencv-python-headless` and the two ONNX models
(meiki.text.detect.v0.1.960x544.onnx, meiki.text.rec.v0.960x32.onnx) in MODELS_DIR.
"""
import glob
import json
import os
import sys
import time

import cv2
import meikiocr.ocr as meiki

models, images, out = sys.argv[1:4]
band = "--band" in sys.argv
meiki._get_model_path = lambda repo, name: os.path.join(models, name)
ocr = meiki.MeikiOCR(provider="CPUExecutionProvider")
result = {}
start = time.time()
for path in sorted(glob.glob(os.path.join(images, "*.png"))):
    img = cv2.imread(path)
    if band:
        img = img[int(img.shape[0] * 0.78):, :]
    result[os.path.basename(path)] = "\n".join(r["text"] for r in ocr.run_ocr(img) if r["text"])
print(f"{len(result)} images in {time.time() - start:.1f}s", file=sys.stderr)
json.dump(result, open(out, "w"), ensure_ascii=False, indent=0)
