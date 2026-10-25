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
   (年李, すこい清掃か): ground truth is what is on screen. Hand GT for that exact extraction
   (`ffmpeg -i clip.mp4 -vf "fps=1,crop=iw:ih*0.22:0:ih*0.78" frames/f%03d.png`, 240 frames, 235
   with a subtitle, 121 distinct lines) is committed: `tools/ocr-eval/data/MH6JpXmv4zI_2m30_gt.json`
   (+ `_uncertain.json`: mostly a two-row caption's top row clipped by the band).

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

## Speed (on the phone, never on the desktop)

`/ocr_bench` times each stage on device; `tools/ocr-eval/bench_device.py` alternates configs
with cool-downs (the S24+ throttles 135 → 186 ms within minutes of back-to-back runs, so
sequential A-then-B runs lie). Push sets to `files/bench/<set>/`; a 40-frame subset
(`h40` = every 6th hardsub frame) is enough for timing.

Galaxy S24+, 1920×238 bands, 4 threads (2026-10-24):

| Config | total | det prep | det run | rec run |
| --- | --- | --- | --- | --- |
| original 960×544 detector, heap input buffers | 135 ms | 11.4 | 100 | 21 |
| + reused direct input buffers | 129 ms | 7.3 | 98 | 21 |
| + detector cut 960×160 | 76 ms | 8.4 | 34 | 31* |
| + any-width recognizer | 67 ms | 8.5 | 34 | 22 |

\* mean over frames incl. 2-line ones; rows are separate alternating runs. Accuracy: bandsA/B
CER identical (0.24 / 0.16 %); hardsub (new GT) 2.62 → 2.68 % CER, 10 frames better / 10
worse — the same spread as the original on a 1-px-shifted or JPEG-q95 copy of the frame. Live
YouTube auto OCR (portrait player, area over the subtitle band, same 4 min twice, switched with
`/ocr_bench?variants=`): 214 → 80 ms per read (`/state.autoOcr.ocrMsAvg`), 111 lines / 102
exact both; line scoring = closest GT line per logged line.

Dead ends on S24+ (idle/YouTube): XNNPACK EP 2× slower (only Conv/MatMul, no GridSample); thread affinity to the big
cores, `allow_spinning=0` and 2/3/5/6/8 threads all slower (also with 4 busy loops pinned to the
big cores: 4 threads 232 ms, 6 → 266, 3 → 325, 2 → 357); NNAPI is deprecated and lacks
GridSample/TopK; Exynos 2400 NPU has no public path.

Xiaomi Pad 7 (`c2bd6564`, Snapdragon 7+ Gen 3, 4× Cortex-X4 + 3× A720 + 1× A520) while Nightshade
runs live in DroidDeck (`Nightshade.exe` ~75–82 % CPU, `surfaceflinger` ~36 %, `gamescope` ~18 %),
2272×427 dialogue crops (`l=0.17, t=0.70, r=0.88, b=0.90`, `detHeight=224`, 2026-10-25):

| Config | wall mean | wall p90 | det run | rec run | DSM CPU ms/ocr (`/proc/pid/stat`) |
| --- | --- | --- | --- | --- | --- |
| original 960×544 + 960×32, 4 threads, spin=1 | 196 ms | 225 ms | 137 ms | 51 ms | 768 CPU-ms |
| cuts, 4 threads, spin=1 | 122 ms | 150 ms | 67 ms | 47 ms | 493 CPU-ms |
| cuts, 3 threads, spin=1 | 114 ms | 137 ms | 66 ms | 42 ms | 376 CPU-ms |
| cuts, 2 threads, spin=1 | 125 ms | 171 ms | 75 ms | 44 ms | 293 CPU-ms |
| cuts, 4 threads, `allow_spinning=0` | 130 ms | 147 ms | 74 ms | 50 ms | 350 CPU-ms |
| cuts, 2 threads, `allow_spinning=0` | 152 ms | 186 ms | 94 ms | 51 ms | 296 CPU-ms |
| cuts, 1 thread, `allow_spinning=0` | 167 ms | 250 ms | 109 ms | 52 ms | 205 CPU-ms |

- **Live Auto OCR in Nightshade** (9-line scene replayed deterministically via Quicksave/Quickload,
  see `dsm-navigation`): original `t=4, spin=1` = **185 ms** (`ocrMsAvg`), cuts `t=4, spin=1` =
  **114 ms**, cuts `t=2, allow_spinning=0` = **116 ms** (all 9 lines logged in every run, 0 %
  Japanese character difference between original and cuts).
- **Live Auto OCR in YouTube app** (`MH6JpXmv4zI` fullscreen `3200×2136`, area `l=0.06, t=0.68, r=0.94, b=0.92` = `2816×513`, `detHeight=224`, 25 s active segment replayed from `2:42`, ~62 OCR runs = 2.5 OCRs/s because the video background moves behind hardsubs):

  | Config | Live `ocrMsAvg` | `ocrRuns` (25 s) | `lines` | Total DSM CPU (25 s) | Continuous CPU % | CPU ms/ocr |
  | --- | --- | --- | --- | --- | --- | --- |
  | original 960×544, 4 threads, `spin=1` | 159 ms | 62 | 12 | 43.34 s | 173.4 % | 699 CPU-ms |
  | cuts, 4 threads, `spin=1` | 93 ms | 61 | 10 | 27.73 s | 110.9 % | 455 CPU-ms |
  | **cuts, 3 threads, `allow_spinning=0` (default)** | **93 ms** | **63** | **12** | **19.61 s** | **78.4 %** | **311 CPU-ms** |
  | cuts, 2 threads, `allow_spinning=0` | 97 ms | 63 | 12 | 17.76 s | 71.0 % | 282 CPU-ms |

- **Static-screen CPU & GC churn fix** (`ScreenCaptureManager.pollRegionCrops`, `FrameSignature.of(image, region)`):
  throttling `ImageReader` acquisitions to `MIN_ACQUIRE_INTERVAL_MS = 100L`, computing
  `FrameSignature` directly on the `Image` RGBA `ByteBuffer` without allocating a `Bitmap`, and
  converting the full-screen screenshot `Bitmap` only on `Event.Started` reduced DSM's idle CPU on a
  static Nightshade dialogue line (under 60–144 Hz `gamescope`) from **26.5–44.4 % CPU** (~150 MB/s
  GC sawtooth) to **5.0 % CPU** (0 MB/s churn).

## Results so far (Galaxy S24+, 2026-10-18)

| Set | ML Kit CER / exact | meikiocr CER / exact |
| --- | --- | --- |
| Hardsub vlog, 229 frames (hand GT) | 9.6 % / 48 % | 2.9 % / 82 % (device 3.0 %) |
| Burned subs, bold outlined, 102 | 4.6 % / 60 % | 0.24 % / 97 % (device identical) |
| Burned subs, boxed, 102 | 0.65 % / 92 % | 0.16 % / 98 % (device identical) |

- The 320×192 detector is 3× faster than 960×544 but worse on busy frames (3.3 % vs 2.9 % CER) —
  kept the 960×544 with height cuts (`160..448`).
- Mixing ML Kit votes into the consensus did not help (its errors outnumber meiki's).
- `OcrTextFixes` (一 after kana → ー): hardsub set meiki 2.97 → 2.84 % CER, ML Kit 9.60 → 9.41 %.
- Live auto OCR, hardsub vlog, meiki: 84 lines, 0 dups, 69 exact, 1.9 % CER vs hand GT. Left:
  repeated digits merged by the recognizer's overlap NMS (4407 → 407), occasional trailing ー.
- Scoring a live auto-OCR run: dump `/lines`, match each line's text to the closest
  ground-truth line (`score.lev` on `score.norm` text); a best match worse than 50 % CER = junk,
  two lines matching the same GT line = duplicate.
