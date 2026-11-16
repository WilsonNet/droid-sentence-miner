"""meikiocr recognizers (horizontal 960x32 + vertical 32x480, LGPL-3.0) on PP-OCRv6_manga line boxes.

Swaps each line's text for meiki's reading so grouping/scoring stays identical.
Usage: python -I run_meiki_lines.py MEIKI_DIR PP_LINES.json IMG_DIR OUT.json
"""
import json
import os
import sys
import time

import cv2
import meikiocr.ocr as meiki


def main():
    models, lines_path, img_dir, out = sys.argv[1:5]
    meiki._get_model_path = lambda repo, name: os.path.join(models, name)
    ocr = meiki.MeikiOCR(provider="CPUExecutionProvider")
    data = json.load(open(lines_path, encoding="utf-8"))
    res = {}
    for page, d in data.items():
        img = cv2.imread(os.path.join(img_dir, page + ".jpg"))
        t0 = time.time()
        crops = [img[l["box"][1]:l["box"][3], l["box"][0]:l["box"][2]] for l in d["lines"]]
        rec = ocr.run_recognition(crops)
        lines = []
        for l, r in zip(d["lines"], rec):
            nl = dict(l, text=r["text"])
            nl.pop("glyphs", None)
            lines.append(nl)
        res[page] = dict(d, lines=lines, ms=int((time.time() - t0) * 1000))
        print(page, res[page]["ms"], "ms", file=sys.stderr)
    json.dump(res, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
