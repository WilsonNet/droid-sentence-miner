# Architecture

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

## Pipeline

```
MediaProjection ──► ScreenCaptureManager ──► AutoOcrController ──► TextLineTracker ──► TextLog (Room)
                        │   (new frames only)    (fast OCR → stable?     (start/end times,    │
                        │                         → two-pass re-read)      screenshot)       │
                        │                                                                    ▼
                        └──► AudioCaptureManager ──► AudioRingBuffer ──► ClipWindow + VoiceTrimmer
                                (AudioPlaybackCapture)   (last N min)         (line's voice clip)
                                                                                    │
                                                                                    ▼
   OCR text ──► TermLookup.scan() ──► JapaneseDeinflector ──► Room DB (Yomitan dictionaries)
                        │                                        │
                        ▼                                        ▼
                 Overlay popup (Compose + WebView)        Glossary parser/Renderer
                        │
                        ▼
                 CardMiner ──► AnkiDroid API or AnkiConnect
```

- **OCR**: meikiocr by default — a D-FINE text-line detector (960×544) finds lines, and a
  recognizer that treats recognition as *character detection* reads each 960×32 line; characters
  are de-duplicated by overlap and read left to right (`MeikiOcr`, `MeikiDecoder`). Wide, short
  crops (a subtitle band) run a shorter cut of the same detector (960×160…448) and short lines a
  narrower recognizer input, so the zero padding isn't computed: 2–2.7× faster, same accuracy.
  The cuts are tiny graphs shipped in the APK that read their weights from the downloaded models
  (`tools/meiki-variants`). With ML Kit
  instead: pass 1 recognizes the region, pass 2 re-reads each line upscaled and keeps the better
  reading. Either way rows are merged, furigana and off-size background text are dropped, and auto
  OCR stores the consensus of all reads of a line.
- **Book photos** (`book/`): always ML Kit (meikiocr only reads horizontal game text). ML Kit's
  blocks are kept as paragraphs; `PageLayout` decides vertical vs horizontal from line shapes,
  orders columns right→left (lines top→bottom when horizontal), drops thin kana-only ruby lines
  and non-Japanese blocks, and joins a block that ends mid-sentence with the adjacent one.
- **Matching**: `JapaneseDeinflector` expands every substring of the OCR text into dictionary-form
  candidates (Yomitan/Jiten rules), a single batched SQL query resolves them, and longest-match-wins
  builds the clickable word list. Grammar-only single-kana matches are filtered as noise.
- **Storage**: Room DB with `terms`, `kanji`, `tags`, `term_meta`, `kanji_meta`, `dictionaries` and
  `mined_cards` tables; indices on expression/reading for fast lookup.
- **Overlay**: a foreground service draws the bubble, capture selector and a Compose result panel;
  MediaProjection consent is handled by a transparent activity that forwards the result to the
  service (with the Android 14/15 foreground-service-type ordering).

## Dictionary compatibility

Yomitan/Yomichan format 3 (and legacy format 2) zips are supported:

| Bank | Usage |
| --- | --- |
| `index.json` | title, revision, languages, frequency mode |
| `term_bank_N.json` | `[expression, reading, definitionTags, rules, score, glossary, sequence, termTags]` |
| `kanji_bank_N.json` | `[character, onyomi, kunyomi, tags, meanings, stats]` |
| `tag_bank_N.json` | tag metadata shown as chips |
| `term_meta_bank_N.json` / `kanji_meta_bank_N.json` | frequency and pitch metadata |
| `styles.css` + images | applied in the popup WebView (images served lazily from the zip, >1600 px downscaled); images stored as Anki media on cards |

Glossaries may be strings, arrays, image definitions, deinflection entries or structured content
(div/span/ul/table/ruby...), all parsed and rendered.

## Compatibility notes

- **AnkiConnect** is plain HTTP; the app ships a network security config that permits cleartext so
  LAN/emulator URLs work. Only user-configured AnkiConnect traffic is affected.
- **AnkiDroid API**: current AnkiDroid releases take `addNote(modelId, deckId, ...)`. If a note type
  was just created through the API and the backend refuses it, the app retries with the built-in
  *Basic* note type so your card is still created. Tested against AnkiDroid 2.24.1 (works,
  including media); AnkiDroid 2.25.0 has a provider bug with freshly-created note types.
- **DroidDeck** (Steam games on Android): text works like any game. Its `targetSdkVersion` is 28,
  so Android blocks other apps from capturing its audio (see
  [Droid-Deck/DroidDeck#1314](https://github.com/Droid-Deck/DroidDeck/issues/1314)); use the
  microphone fallback. The game pauses itself whenever it loses focus (e.g. the capture consent
  dialog); tap *再開* to continue. Keep the text area on the dialogue box only — side buttons
  (スキップ, クイックロード) inside it get logged as lines.
- **Emulator**: with a headless emulator, starting MediaProjection can black out the emulated
  display (SwiftShader/host GPU limitation). OCR, lookup, audio encode and Anki paths are all
  testable; for end-to-end screen capture use a device or a windowed emulator.

Deeper implementation notes, pitfalls and design invariants for contributors are in [AGENTS.md](../AGENTS.md) (*Gotchas learned the hard way*).
