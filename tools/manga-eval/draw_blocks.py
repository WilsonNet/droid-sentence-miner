"""Draw numbered blocks of a .mokuro file (or engine JSON) on page images to build/check ground truth.

Usage: python -I draw_blocks.py VOLUME.mokuro IMG_DIR OUT_DIR
Prints each page's blocks (index, vertical, text) and writes OUT_DIR/<page>.jpg.
"""
import json
import os
import sys

from PIL import Image, ImageDraw


def main():
    mokuro, img_dir, out_dir = sys.argv[1:4]
    os.makedirs(out_dir, exist_ok=True)
    data = json.load(open(mokuro, encoding="utf-8"))
    for page in data["pages"]:
        name = os.path.splitext(os.path.basename(page["img_path"]))[0]
        img = Image.open(os.path.join(img_dir, os.path.basename(page["img_path"]))).convert("RGB")
        draw = ImageDraw.Draw(img)
        print(f"== {name}")
        for i, b in enumerate(page["blocks"]):
            x1, y1, x2, y2 = b["box"]
            draw.rectangle([x1, y1, x2, y2], outline=(255, 0, 0), width=3)
            draw.rectangle([x1, y1 - 22, x1 + 30, y1], fill=(255, 0, 0))
            draw.text((x1 + 3, y1 - 21), str(i), fill=(255, 255, 255), font_size=18)
            print(i, "V" if b["vertical"] else "H", "".join(b["lines"]))
        img.save(os.path.join(out_dir, name + ".jpg"), quality=85)


if __name__ == "__main__":
    main()
