"""Unbiased OCR test set: burn a subtitle file onto real footage and grab frames with exact ground truth.

usage: burn_subs.py VIDEO.mp4 SUBS.vtt OFFSET_SECONDS OUT_DIR
Writes OUT_DIR/{bandsA,bandsB}/cNNN_{0,1}.png (bold outlined / boxed styles, bottom 22% of the
frame, two frames per cue) and OUT_DIR/gt.json. OFFSET_SECONDS = where VIDEO.mp4 starts in the
original timeline (e.g. 10 for `yt-dlp --download-sections "*0:10-4:10"`).
"""
import json
import os
import re
import subprocess
import sys

video, vtt, offset, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
os.makedirs(out, exist_ok=True)


def ts(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def srt_time(x):
    return f"{int(x // 3600):02d}:{int(x % 3600 // 60):02d}:{x % 60:06.3f}".replace(".", ",")


duration = float(subprocess.check_output(
    ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video]))
cues = []
for block in open(vtt, encoding="utf-8").read().split("\n\n"):
    m = re.search(r"(\d+:\d+:\d+\.\d+) --> (\d+:\d+:\d+\.\d+)", block)
    if not m:
        continue
    a, e = ts(m[1]) - offset, ts(m[2]) - offset
    if a > 0.5 and e < duration - 1:
        cues.append((a, e, "".join(block.split("\n")[1:]).strip()))
srt = os.path.join(out, "subs.srt")
open(srt, "w").write("\n".join(f"{i + 1}\n{srt_time(a)} --> {srt_time(e)}\n{t}\n" for i, (a, e, t) in enumerate(cues)))

styles = {
    "A": "FontName=Noto Sans CJK JP,Bold=1,FontSize=22,Outline=2.5,Shadow=0,MarginV=22",
    "B": "FontName=Noto Sans CJK JP,FontSize=18,BorderStyle=3,Outline=1,BackColour=&H60000000,Shadow=0,MarginV=22",
}
gt = {}
for name, style in styles.items():
    burned = os.path.join(out, f"burn{name}.mp4")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", video, "-vf", f"subtitles={srt}:force_style='{style}'",
                    "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", burned], check=True)
    os.makedirs(os.path.join(out, f"bands{name}"), exist_ok=True)
    for i, (a, e, text) in enumerate(cues):
        for j, frac in enumerate((0.3, 0.7)):
            frame = f"c{i:03d}_{j}.png"
            gt[frame] = text
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", f"{a + (e - a) * frac:.3f}", "-i", burned,
                            "-frames:v", "1", "-vf", "crop=iw:ih*0.22:0:ih*0.78",
                            os.path.join(out, f"bands{name}", frame)], check=True)
json.dump(gt, open(os.path.join(out, "gt.json"), "w"), ensure_ascii=False, indent=0)
print(f"{len(cues)} cues, {len(gt)} frames per style")
