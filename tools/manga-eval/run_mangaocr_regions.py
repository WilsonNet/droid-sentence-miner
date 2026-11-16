"""manga-ocr (kha-white, what mokuro uses) on detector text regions: mokuro quality with our detector.

Run with mokuro's venv python (has manga_ocr + torch):
  ~/.local/share/pipx/venvs/mokuro/bin/python -I run_mangaocr_regions.py REGIONS.json IMG_DIR OUT.json
"""
import json
import os
import sys
import time

from manga_ocr import MangaOcr
from PIL import Image


def main():
    regions, img_dir, out = sys.argv[1:4]
    data = json.load(open(regions))
    mocr = MangaOcr()
    res = {}
    for page, d in data.items():
        img = Image.open(os.path.join(img_dir, page + ".jpg")).convert("RGB")
        t0 = time.time()
        blocks = []
        for r in d["regions"]:
            if r["cls"] not in (1, 2):
                continue
            x1, y1, x2, y2 = r["box"]
            if x2 - x1 < 4 or y2 - y1 < 4:
                continue
            text = mocr(img.crop((x1, y1, x2, y2)))
            blocks.append({"text": text, "box": r["box"], "cls": r["cls"]})
        ms = int((time.time() - t0) * 1000)
        res[page] = {"ms": ms, "blocks": blocks}
        print(page, ms, "ms", len(blocks), file=sys.stderr)
    json.dump(res, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
