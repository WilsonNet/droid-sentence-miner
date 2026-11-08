"""Latin-script OCR test set: caption sentences drawn over real video frames in game/subtitle styles.

usage: gen_latin_set.py VIDEO.mp4 CAPTIONS.json3 OUT_DIR [N]
Takes N sentences (default 80) from a YouTube json3 caption file (`yt-dlp --write-auto-subs
--sub-format json3`), draws each over a frame of VIDEO in one of four styles, and saves the text
band the app's text area would crop (OUT_DIR/img/NNN.png) plus OUT_DIR/gt.json (exact text, rows
joined by a space). Styles: outlined subtitle, VN dialogue box (2 rows), yellow shadowed subtitle,
dark-on-light UI panel. Fonts rotate through whatever of FONTS exists on this machine.
"""
import json
import os
import random
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

video, captions, out = sys.argv[1], sys.argv[2], sys.argv[3]
count = int(sys.argv[4]) if len(sys.argv) > 4 else 80
random.seed(7)

FONTS = [
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/noto/NotoSans-Italic.ttf",
    "/usr/share/fonts/noto/NotoSerif-Regular.ttf",
    "/usr/share/fonts/gsfonts/URWGothic-Book.otf",
    "/usr/share/fonts/gsfonts/C059-Roman.otf",
    "/usr/share/fonts/gsfonts/NimbusSansNarrow-Bold.otf",
    "/usr/share/fonts/gsfonts/URWBookman-LightItalic.otf",
]
fonts = [f for f in FONTS if os.path.exists(f)]
assert fonts, "no fonts found"

events = json.load(open(captions))["events"]
sentences = []
for e in events:
    text = "".join(s.get("utf8", "") for s in e.get("segs", [])).replace("\n", " ").strip()
    text = " ".join(text.split())
    if len(text.split()) >= 5 and "[" not in text and text not in sentences:
        sentences.append(text[0].upper() + text[1:])
# Two consecutive caption events make one sentence of game/subtitle length (8-20 words).
sentences = [f"{a} {b[0].lower()}{b[1:]}" for a, b in zip(sentences[::2], sentences[1::2])]
random.shuffle(sentences)
sentences = sentences[:count]

duration = float(subprocess.check_output(
    ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video]))
os.makedirs(os.path.join(out, "img"), exist_ok=True)


def frame(t):
    png = subprocess.check_output(
        ["ffmpeg", "-v", "error", "-ss", f"{t:.2f}", "-i", video, "-frames:v", "1", "-vf", "scale=1280:720",
         "-f", "image2pipe", "-vcodec", "png", "-"])
    from io import BytesIO
    return Image.open(BytesIO(png)).convert("RGB")


def wrap(draw, text, font, width):
    rows, row = [], ""
    for word in text.split():
        trial = (row + " " + word).strip()
        if draw.textlength(trial, font=font) <= width or not row:
            row = trial
        else:
            rows.append(row)
            row = word
    rows.append(row)
    return rows


gt = {}
for i, text in enumerate(sentences):
    img = frame(random.uniform(1, duration - 1))
    draw = ImageDraw.Draw(img, "RGBA")
    style = i % 4
    font = ImageFont.truetype(fonts[i % len(fonts)], random.choice([30, 34, 38, 42]))
    if style == 0:  # outlined subtitle, centered, up to 2 rows
        rows = wrap(draw, text, font, 1100)[:2]
        y = 720 - 40 - len(rows) * 52
        for r in rows:
            w = draw.textlength(r, font=font)
            draw.text(((1280 - w) / 2, y), r, font=font, fill="white", stroke_width=3, stroke_fill="black")
            y += 52
        band = (0, 720 - 220, 1280, 720)
    elif style == 1:  # visual-novel dialogue box
        draw.rounded_rectangle((60, 480, 1220, 700), 18, fill=(10, 10, 30, 235), outline=(200, 200, 255, 220), width=3)
        rows = wrap(draw, text, font, 1060)[:3]
        y = 510
        for r in rows:
            draw.text((100, y), r, font=font, fill=(245, 245, 245))
            y += 54
        band = (60, 480, 1220, 700)
    elif style == 2:  # yellow subtitle with drop shadow
        rows = wrap(draw, text, font, 1100)[:2]
        y = 720 - 40 - len(rows) * 52
        for r in rows:
            w = draw.textlength(r, font=font)
            draw.text(((1280 - w) / 2 + 3, y + 3), r, font=font, fill=(0, 0, 0, 200))
            draw.text(((1280 - w) / 2, y), r, font=font, fill=(255, 230, 60))
            y += 52
        band = (0, 720 - 220, 1280, 720)
    else:  # dark text on a light UI panel
        draw.rectangle((140, 80, 1140, 320), fill=(238, 232, 214, 235))
        rows = wrap(draw, text, font, 940)[:3]
        y = 110
        for r in rows:
            draw.text((170, y), r, font=font, fill=(40, 30, 20))
            y += 54
        band = (140, 80, 1140, 320)
    name = f"{i:03d}.png"
    img.crop(band).save(os.path.join(out, "img", name))
    gt[name] = " ".join(rows)
json.dump(gt, open(os.path.join(out, "gt.json"), "w"), ensure_ascii=False, indent=0)
print(f"{len(gt)} images in {out}")
