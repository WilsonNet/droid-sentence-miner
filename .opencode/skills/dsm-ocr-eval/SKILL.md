---
name: dsm-ocr-eval
description: Measure OCR accuracy of Droid Sentence Miner (meikiocr vs ML Kit, auto OCR line quality) with ground truth instead of eyeballing. Use when changing OCR engines, preprocessing, line tracking/consensus, or when a user reports wrong/broken text.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# OCR evaluation

Scripts live in `tools/ocr-eval/`. Metric: character error rate (CER, edit distance /
ground-truth chars, punctuation and spaces ignored) and exact-line rate. Always compare engines
on the **same crops**.

## Data sets

1. **Unbiased (preferred)** — burn a real subtitle file onto real footage; ground truth is exact:
   ```bash
   yt-dlp --write-subs --sub-langs ja --sub-format vtt --skip-download -o subs URL
   yt-dlp -f "bv*[height<=1080][ext=mp4]" --download-sections "*0:10-4:10" -o clip.mp4 URL
   python3 tools/ocr-eval/burn_subs.py clip.mp4 subs.ja.vtt 10 out/   # bandsA (outlined), bandsB (boxed), gt.json
   ```
   Used: `gM7sP8vT40o` (manual JA subs).
2. **Real hardsubs** — `yt-dlp` a hardsub video (`MH6JpXmv4zI`, 2:30–6:30), `ffmpeg -vf fps=1`,
   crop the bottom 22 %, ground truth by hand. Subtitles may contain the creator's own typos
   (年李, すこい清掃か): ground truth is what is on screen.

## Engines

```bash
# desktop reference (onnxruntime): models from huggingface rtr46/meiki.text.detect.v0 + meiki.txt.recognition.v0
python3 tools/ocr-eval/run_meiki.py MODELS out/bandsA meiki_A.json
# on device, through the debug API (meiki or mlkit); stop auto OCR first, it shares the engine
adb push out/bandsA/. /sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/omA/
python3 tools/ocr-eval/run_device.py omA out/bandsA dev_meiki_A.json meiki
python3 tools/ocr-eval/score.py out/gt.json desktop=meiki_A.json device=dev_meiki_A.json
```

`score.py` also reports multi-frame fusion (medoid / ROVER per on-screen line, frames named
`fNNN` consecutively).

## Results so far (Galaxy S24+, 2026-10-18)

| Set | ML Kit CER / exact | meikiocr CER / exact |
| --- | --- | --- |
| Hardsub vlog, 229 frames (hand GT) | 9.6 % / 48 % | 2.9 % / 82 % (device 3.0 %) |
| Burned subs, bold outlined, 102 | 4.6 % / 60 % | 0.24 % / 97 % (device identical) |
| Burned subs, boxed, 102 | 0.65 % / 92 % | 0.16 % / 98 % (device identical) |

- Device meiki ≈ 150 ms per 1920×238 crop (4 threads); detection is ~75 % of it. The 320×192
  detector is 3× faster but worse on busy frames (3.3 % vs 2.9 % CER) — kept the 960×544.
- Mixing ML Kit votes into the consensus did not help (its errors outnumber meiki's).
- `OcrTextFixes` (一 after kana → ー): hardsub set meiki 2.97 → 2.84 % CER, ML Kit 9.60 → 9.41 %.
- Live auto OCR, hardsub vlog, meiki: 84 lines, 0 dups, 69 exact, 1.9 % CER vs hand GT. Left:
  repeated digits merged by the recognizer's overlap NMS (4407 → 407), occasional trailing ー.
- Scoring a live auto-OCR run: dump `/lines`, match each line's text to the closest
  ground-truth line (`score.lev` on `score.norm` text); a best match worse than 50 % CER = junk,
  two lines matching the same GT line = duplicate.
