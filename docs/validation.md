# Validation

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

What was measured, on which device, and how. Recipes and scripts: [`.opencode/skills/dsm-ocr-eval`](../.opencode/skills/dsm-ocr-eval/SKILL.md), [`dsm-audio`](../.opencode/skills/dsm-audio/SKILL.md), [`dsm-debug-api`](../.opencode/skills/dsm-debug-api/SKILL.md), `tools/`.

- Lookup speed with 8 dictionaries (~2.1 M rows: Jitendex, Kanjium, Pixiv, 広辞苑, 三省堂, 実用日本語表現,
  新明解, 大辞林), Xiaomi Pad 7, `/lookup_bench` median per tap (line scan + lookup + popup
  HTML), before → after the lean-scan/cache work:

  | Tap | New line | Same line again |
  | --- | --- | --- |
  | 歯がゆく (long line) | 297 → 147 ms | 309 → 7 ms |
  | 詳しい | 234 → 113 ms | 277 → 10 ms |
  | 猫 | 97 → 38 ms | 103 → 16 ms |
  | こう (487 terms, 630 KB HTML) | 468 → 170 ms | 520 → 120 ms |

- Dictionary import, Xiaomi Pad 7, 6 monolingual zips (540 MB) picked at once from the user's own
  Drive via *Browse my Drive*: Pixiv Light (723,926 terms), 広辞苑 第七版 (257,602), 三省堂国語辞典
  第八版 (93,091), 実用日本語表現辞典 v2 (55,377), 新明解 第八版 (100,593), 大辞林 第四版 (334,748):
  1.56 M terms in 7 min 45 s, app in the foreground. Before streaming the banks, 広辞苑 crashed the
  app (one term bank needed a 268 MB String on a 256 MB heap).
- OCR accuracy, same crops for both engines (character error rate / exact lines; Galaxy S24+;
  recipe and scripts in `.opencode/skills/dsm-ocr-eval` and `tools/ocr-eval`):

  | Test set | ML Kit | meikiocr |
  | --- | --- | --- |
  | Hardsub travel vlog, 229 frames, hand-checked ground truth | 9.6 % / 48 % | 3.0 % / 81 % |
  | Real subtitles burned on footage, bold outlined, 102 frames | 4.6 % / 60 % | 0.24 % / 97 % |
  | Same, boxed (YouTube-like), 102 frames | 0.65 % / 92 % | 0.16 % / 98 % |

  meikiocr speed per 1920×238 subtitle-band crop on the phone (debug build, `/ocr_bench`):
  135–150 ms with the full 960×544 detector, 63–67 ms with the shorter detector cut and the
  any-width recognizer, at identical CER on both burned-subtitle sets and within frame-to-frame
  noise on the hardsub vlog (re-labelled 240-frame set: 2.62 % → 2.68 % CER, 10 frames better,
  10 worse). Live auto OCR, YouTube portrait player, same 4 minutes of the hardsub vlog: 214 →
  80 ms per read, 111 lines each, 102 exact each. Live auto OCR on the hardsub vlog with meikiocr:
  84 lines, 0 duplicates, 82 % exact, 1.9 % CER (several "errors" are meikiocr fixing the
  creator's own typos). Auto OCR with soft subs: 54/54 lines, 0 junk, 0 duplicates.

- Auto OCR end-to-end on a Galaxy S24+ (Android 16) against a YouTube video with Japanese soft
  subtitles, compared with the subtitle file and the original audio (see AGENTS.md for the
  recipe): every subtitle cue in the run was logged (61/61, 56 exact, mean character accuracy
  0.99), line start times within ±300 ms (p90) of the cue, and every voice clip located in the
  original audio at correlation ≥0.97, starting 160–1080 ms (median ~400 ms) before the subtitle.
- Visual novel on a Xiaomi Pad 7 (HyperOS 3, Snapdragon 7+ Gen 3): Nightshade through DroidDeck,
  meikiocr, text area on the dialogue box (`2272×427` crops, `detHeight=224`). Before: lines froze
  at the first half-typed read (戦乱の世におい). After: every line of the prologue and first scene
  logged complete (3-line narration included), short exclamations like 「百花……っ」 kept, 一筋 no
  longer turned into ー筋. With Nightshade actively running (`Nightshade.exe` ~80 % CPU +
  `surfaceflinger` ~36 % + `gamescope` ~18 %), the shorter detector cut, any-width recognizer and
  `threads = 3, allow_spinning = 0` default drop `/ocr_bench` wall time from 196 ms to 114–122 ms
  (live auto OCR on a 9-line scene replayed via Quicksave/Quickload: 185 → 114 ms per read, 0 %
  Japanese character difference) and CPU core-time per OCR from 768 → 350–376 CPU-ms (293 CPU-ms
  at 2 threads). On the YouTube app in fullscreen (`MH6JpXmv4zI`, 2.5 OCRs/s over moving background),
  live auto OCR drops from 159 → 93 ms per read and CPU core-time from 699 → 311 CPU-ms (173 % →
  78 % continuous CPU; 455 CPU-ms / 111 % with 4 spinning threads). Throttling `ImageReader`
  acquisitions to 100 ms and checking `FrameSignature` directly on the `Image` RGBA buffer without
  `Bitmap` allocation cuts DSM's idle CPU on a static Nightshade dialogue line from 26–44 %
  (~150 MB/s GC churn) to 5 % (0 MB/s churn). Device audio came back
  as digital zeros (`AudioRecord … [mute]`); with the microphone fallback the voiced lines are
  audible in the clips (speech at ~2000 RMS after normalization vs ~15 raw at the tablet's
  volume). Cards mined from the popup (手薄, 術) had sentence, screenshot, word audio and clip.
- Microphone fallback FX chain on the Xiaomi Pad 7 (30 Japanese speakers from Forvo played through
  the speaker and recorded back by `/mic_measure.wav`, scored against the original with
  `tools/mic-eval`): the measured speaker → mic curve is −12…−17 dB at 160–250 Hz, −13 dB at
  2.5 kHz and +15 dB at 6.3 kHz. The calibrated EQ cuts the spectral deviation from 6.9 to 1.8 dB
  and lifts PESQ-wb from 2.14 to 2.60 and STOI from 0.868 to 0.881 (old chain: suppression +
  compressor); on-device calibration matches the Python reference fit (each band within 2 dB, separate measurement runs). In
  Nightshade the 6.3 kHz resonance is gone from the clips. Cost: 1.3 % of one core while the mic
  is in use, nothing while device audio works.
- 191 JVM unit tests: speaker-correction EQ (FFT, biquads, response from pink noise, fit of the
  measured Pad 7 curve, chain), meikiocr detector/recognizer input sizing, frame change detection (one typewriter glyph counts; direct RGBA buffer with row stride), loudness
  normalization, popup placement (flip/size/shift), screen text layer (subtitle glyphs → screen fractions, signage skipped), book page layout (tategaki order, ruby filter incl. real manga ruby, split sentences, per-character photo positions), H→エ fix, pitch graphs (byte-identical to Yomitan), word audio sources, Anki field mapping, meikiocr decoding, OCR text fixes (一→ー), text areas, line consensus, replay buffer, voice trimming, line tracking, clip windows, subtitle-row
  filtering, deinflector (conjugations across godan/ichidan/suru/kuru/adjectives),
  glossary parsing/rendering (incl. Yomitan-style images: Pixiv logo, 広辞苑/三省堂 node shapes, sizes, monochrome, collapsed, Anki media names), OCR post-processing (merge, furigana filter, normalization),
  VAD segmentation, PCM utilities, Yomitan index parsing & dictionary update checks, Google Drive
  link/folder-listing/download-confirm parsing, streamed bank parsing.
- 4 on-device instrumented tests: VAD segmentation with real waveforms, AAC/M4A encoding through
  MediaCodec, WAV fallback.
- Book mode on a Galaxy phone (`/book` with synthetic photographed pages, Noto Serif 44 px,
  slightly rotated and blurred): vertical page read as 2 paragraphs in correct column order in
  ~340 ms, horizontal page 174 ms with a comma-split sentence rejoined; ML Kit still misread a few
  kanji on the blurred render (吾→音, 間→問), hence the ✎ fix-up. Camera intent, paragraph tap
  and word lookup checked in the UI.
- Screen dictionary on the Galaxy phone with the hardsub vlog (`MH6JpXmv4zI`) in YouTube's portrait
  player, meikiocr, area over the subtitle band: the layer window followed each subtitle (fitted to
  the line, e.g. `[210,601]–[865,711]` px), a tap mid-word looked up the word covering it with the
  popup right under the subtitle, YouTube's media session went PAUSED on the tap and PLAYING on close.
  Fullscreen landscape: paused, controls hidden, tapping 入 looked up 入りたい with the popup flipped
  above the subtitle at 1688×899 px (was 900×263 before the Popper-style placement).
- Book mode on real manga: 6 pages of *Black Jack ni Yoroshiku* vol. 1 (Shuho Sato's officially
  free Japanese PDF from densho810.com, rendered 1414×2000) on the Galaxy phone, 140–250 ms per
  page. Every typeset dialogue bubble and caption was found, columns in the right order; misses were
  a short "25歳" label, a "卒……" fragment and stylized signs; handwritten SFX are ignored. Ruby
  columns (about half the body width) are dropped; ML Kit's エ→H and ……→".." errors are corrected.
  Rendering at 1.5× changed nothing, so resolution is not the limit. Overlay reader on the same
  page: tapping a kanji in a bubble highlighted exactly that word on the photo and opened its entry;
  hold-and-drag selected along the column. Pinch zoom is not covered by adb tests (single touch).
- Emulator end-to-end: Jitendex one-click install (436,555 terms), OCR of an Aozora Bunko passage
  (20 matched words, deinflection of 走れ → 走る), popup lookup, mining into a real AnkiDroid
  installation (card verified with image), and mining through AnkiConnect against a mock server
  (note + media verified), plus MediaProjection consent flow.
