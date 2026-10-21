# Droid Sentence Miner

A native Android sentence miner for Japanese immersion: OCR text from any app with a floating
popup, look words up with Yomitan dictionaries, and mine them into Anki — plus the game's voice
line as sentence audio.

Inspired by [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner), rebuilt for Android
with [Yomitan](https://github.com/yomidevs/yomitan) dictionary compatibility.

<p align="center">
  <img src="docs/screenshots/home.png" width="24%" alt="Home" />
  <img src="docs/screenshots/capture-region.png" width="24%" alt="Capture region" />
  <img src="docs/screenshots/lookup.png" width="24%" alt="Dictionary popup" />
  <img src="docs/screenshots/anki-card.png" width="24%" alt="Anki card" />
</p>

## Features

- **Auto OCR mode** — draw boxes over the game's text areas once (dialogue box, name box,
  subtitles; kept separately for portrait and landscape) and keep playing. The areas are read
  every ~400 ms; each new line is logged with the moment it appeared, the moment it went away and
  a screenshot. Repeated reads must agree before a line counts, and the stored text is the
  consensus of all reads of that line, so fades, typewriter text and OCR noise over moving video
  don't create junk; background signage is filtered out.
- **Screen dictionary (tap the real text, in any app)** — while auto OCR runs, each read is laid
  as an invisible text layer exactly over the subtitles/dialogue on screen (meikiocr's and ML Kit's
  per-character positions). Tap a character in a YouTube hardsub or a game's text box and the word
  covering it lights up with its definition right next to it; hold and drag to select characters.
  The layer is fitted to the text, so touches elsewhere still reach the app. Videos are fast, so a
  lookup pauses playback (media key: YouTube and most players) and resumes it when the popup closes;
  if you pause yourself, the layer stays and you can tap word after word. Mining links the
  log line, so the card gets the sentence, screenshot and voice clip.
- **Live text window** — a small floating feed of the newest lines over the game, updated in
  real time. Tap a line to look up and mine it without leaving the game.
- **App focus** — share "A single app" (Android 14+) and pick the game: only it is read, and
  auto OCR pauses while you are in other apps or in Droid Sentence Miner itself.
- **Book mode (paper books, manga)** — the *Book* tab takes a photo with Samsung Camera on Galaxy
  phones (any camera app elsewhere) or picks one from the gallery, reads the page with ML Kit — vertical (tategaki, columns right to left)
  and horizontal print — and outlines each paragraph on the photo. Tap a paragraph, tap a word,
  and mine it: the card gets the word's sentence (cut at 。！？, keeping closing quotes) and the
  paragraph cropped from the photo as its picture. Hold and drag over the text to force your own
  selection (hold alone = one character, e.g. a single kanji). The definition pops up right at the
  word (below it, or above when there is no room), instantly and without a scrim, so tapping the
  next word just moves it. Tap Japanese inside a definition to look that up too (← goes back). Ruby lines, page numbers and sentences split
  across blocks are handled; ✎ fixes a misread before mining.
- **Overlay dictionary mode (read on the photo)** — Mokuro-style: after a photo the page opens
  full screen with the OCR text laid invisibly over the printed characters (ML Kit's per-character
  boxes). Tap any character on the photo, in a speech bubble or a column of print, and the word
  starting there lights up with its definition popping up beside it; hold and drag to select
  characters yourself; pinch to zoom, drag to pan.
- **Texts page** (GSM's texthooker) — every recognized line, newest first. Tap a line, tap a word,
  and mine it: the card gets that line's sentence, its screenshot and its voice clip.
- **Replay buffer sentence audio** — the last 5 minutes (configurable 1–10) of game audio are kept
  in memory. Any line still inside the buffer can be mined with audio cut from when it appeared to
  when it was replaced (± configurable offsets). Preview or fine-tune it in the waveform editor
  before mining; optional (experimental) silence trimming is in Settings.
- **Floating popup overlay** — a draggable bubble that lets you select any screen region, OCR it,
  and look up words without leaving the game. Long-press opens a menu; the notification stays
  available as a shortcut.
- **Japanese OCR trained on games** — on-device [meikiocr](https://github.com/rtr46/meikiocr)
  (ONNX Runtime), a detector + recognizer trained on Japanese video-game text: 3–19× fewer
  character errors than ML Kit on stylized, outlined game/subtitle fonts (small kana, dakuten).
  One-time 31 MB model download; ML Kit (with a GameSentenceMiner-style two-pass pipeline) is
  built in as the fallback/alternative engine.
- **Yomitan dictionaries** — import any Yomitan/Yomichan format 3 `.zip` (Jitendex, JMdict,
  KANJIDIC, frequency dictionaries) or install Jitendex/JMdict/KANJIDIC with one tap.
  Term, kanji, tag, term-meta and kanji-meta banks are all parsed, including structured-content
  glossaries and dictionary images.
- **Deinflection & word scanning** — a full Japanese deconjugator drives longest-match scanning of
  scanned text and tap-to-look-up, with the same rules Yomitan uses.
- **Anki mining** — two backends:
  - **AnkiDroid API** (no network needed; cards, images and audio via the official API), and
  - **AnkiConnect** (desktop Anki with the AnkiConnect add-on; card + media upload over HTTP).
  Cards include Expression, Reading, Meaning (rendered glossary), Sentence, Image (captured
  screenshot), Audio (sentence voice line), **Word audio** and Source. Existing note types are
  filled by field name the way Yomitan/GSM fill them — e.g. Anime Cards: Word, Reading, Glossary,
  Context Sentence (Sentence stays empty so it stays a word card), Word Audio, Audio, Picture.
- **Word audio like Yomitan** — the word's pronunciation is fetched from the same sources Yomitan
  uses for Japanese, in the same order: JapanesePod101 (placeholder clip rejected by hash),
  JapanesePod101 dictionary search, Jisho; plus an optional custom URL (`{term}`/`{reading}`,
  e.g. a local audio server) tried first. Option to put only the first dictionary on the card
  (Yomitan's `{glossary-first}`).
- **Pitch accent graph** — the same SVG Yomitan's `{pitch-accent-graphs}` writes (byte-identical to
  the user's existing Yomitan cards), filled into a pitch/graph field such as Anime Cards' *Graph*.
  Uses any installed Yomitan pitch dictionary, or the free Kanjium pitch accents (124k words,
  one-click install from *Settings → Dictionaries* or the Home setup card).
- **Sentence audio** (Android 10+) — captures playback audio through the existing MediaProjection
  session (the Android equivalent of GSM's loopback capture) into the replay buffer, with a
  waveform **audio editor** to trim the exact line before mining.
- **Mining history** — recent cards with a status showing whether they made it into Anki.
- **Text selection lookup** — other apps can send selected text via `ACTION_PROCESS_TEXT`, including
  Samsung's text selection on screenshots/images and Circle to Search. A selected word is matched to
  the newest logged line containing it, so the card still gets the full sentence, screenshot and
  audio; a selected sentence is logged as a new line. Handy for text auto OCR can't read (vertical
  or handwritten-style), using Google's cloud OCR by hand.

## Requirements

- Android 8.0 (API 26) or newer. Audio capture requires Android 10+; screen sharing follows the
  Android 14/15 consent flow (app picker + foreground service type).
- [AnkiDroid](https://github.com/ankidroid/Anki-Android) (2.16+) and/or desktop Anki with
  [AnkiConnect](https://foosoft.net/projects/anki-connect/).

## Building from the command line

No Android Studio required.

```bash
# 1. Toolchain — JDK 21 + Android CLI (Linux example, no root needed)
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
mkdir -p ~/Android/tools && curl -sSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar xz -C ~/Android/tools
export JAVA_HOME=$(echo ~/Android/tools/jdk-21*)      # macOS: brew install openjdk@21
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. Android SDK packages
android sdk install platform-tools platforms/android-36 build-tools/35.0.0 emulator \
                    system-images/android-35/google_apis_playstore/x86_64
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Build & test
./gradlew testDebugUnitTest          # 128 JVM unit tests
./gradlew connectedDebugAndroidTest  # 4 on-device tests (VAD + AAC encoding)
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Dictionaries** — open *Settings → Dictionaries* and install **Jitendex** and **Kanjium pitch accents**
   with one tap each (or import any Yomitan `.zip`, e.g. a monolingual dictionary; drag it first
   if you want only it on cards). In *Settings → OCR*, tap *Download meikiocr models* (31 MB).
2. **Anki** — in *Settings → Anki* pick a backend:
   - *AnkiDroid*: install AnkiDroid, grant the API permission from that page, and enable
     *Settings → Advanced → AnkiDroid API* inside AnkiDroid.
   - *AnkiConnect*: start Anki with the AnkiConnect add-on and enter the URL
     (e.g. `http://192.168.1.10:8765`).
   Pick a deck and note type (a "Droid Sentence Miner" note type is created automatically).
3. **Auto OCR (recommended)** — open the game, long-press the bubble → *Start auto OCR*. In the
   share dialog choose *A single app* → your game. If no text area exists for the current
   orientation the area editor opens: drag boxes over the dialogue/subtitle area (drag to move,
   corners to resize, × to delete; drag the toolbar by ⠿ out of the way) and *Save*. To change
   them later (e.g. a different video), tap ✎ in the *Live text* window or long-press the bubble →
   *Auto OCR text areas*. New lines
   appear in the floating *Live text* window — tap one to look up words and mine it with its audio
   and screenshot. Everything is also on the *Texts* tab.
   While it runs, just tap a word in the real subtitle on screen to look it up (the video pauses
   until you close the definition). Toggles: *Settings → Tap text on screen to look it up* and
   *Pause the video while looking up*.
4. **Manual scan (one-off)** — tap the bubble (or long-press → *Scan once: pick a region*), adjust
   the capture box and hit *Scan region*. This box is separate from the auto OCR text areas. Tap a
   highlighted word for definitions, then *Anki*. Manual scans are logged on *Texts* too.
5. **Audio** — on by default; grant the microphone permission when asked (Android requires it for
   playback capture). *Settings* has the buffer length, start/end offsets and silence trimming.
   Apps that opt out of capture stay silent.
6. **Books** — open the *Book* tab, *Take photo* of the page (flat, well lit, filling the frame),
   tap the paragraph on the photo, then tap a word in its text to look it up and *Anki* it
   (hold + drag to pick the exact characters yourself; tap words inside the definition to dig deeper).
   After a photo the page opens in the overlay reader: tap the printed text itself to look it up,
   pinch to zoom; ✕ or Back returns to the paragraph view (*Read on the photo* reopens it).
   Arrows step through paragraphs; ✎ corrects the OCR text.
7. **Test drive** — the Home tab has a built-in sample (a public-domain Aozora Bunko passage) and
   an image picker to test OCR + lookup without a game.

## How it works

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
  are de-duplicated by overlap and read left to right (`MeikiOcr`, `MeikiDecoder`). With ML Kit
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
| `styles.css` + images | applied in the popup WebView; images embedded in Anki via media files |

Glossaries may be strings, arrays, image definitions, deinflection entries or structured content
(div/span/ul/table/ruby...), all parsed and rendered.

## Compatibility notes

- **AnkiConnect** is plain HTTP; the app ships a network security config that permits cleartext so
  LAN/emulator URLs work. Only user-configured AnkiConnect traffic is affected.
- **AnkiDroid API**: current AnkiDroid releases take `addNote(modelId, deckId, ...)`. If a note type
  was just created through the API and the backend refuses it, the app retries with the built-in
  *Basic* note type so your card is still created. Tested against AnkiDroid 2.24.1 (works,
  including media); AnkiDroid 2.25.0 has a provider bug with freshly-created note types.
- **Emulator**: with a headless emulator, starting MediaProjection can black out the emulated
  display (SwiftShader/host GPU limitation). OCR, lookup, audio encode and Anki paths are all
  testable; for end-to-end screen capture use a device or a windowed emulator.

## Validation

- OCR accuracy, same crops for both engines (character error rate / exact lines; Galaxy S24+;
  recipe and scripts in `.opencode/skills/dsm-ocr-eval` and `tools/ocr-eval`):

  | Test set | ML Kit | meikiocr |
  | --- | --- | --- |
  | Hardsub travel vlog, 229 frames, hand-checked ground truth | 9.6 % / 48 % | 3.0 % / 81 % |
  | Real subtitles burned on footage, bold outlined, 102 frames | 4.6 % / 60 % | 0.24 % / 97 % |
  | Same, boxed (YouTube-like), 102 frames | 0.65 % / 92 % | 0.16 % / 98 % |

  ~150 ms per subtitle-band crop on the phone. Live auto OCR on the hardsub vlog with meikiocr:
  84 lines, 0 duplicates, 82 % exact, 1.9 % CER (several "errors" are meikiocr fixing the
  creator's own typos). Auto OCR with soft subs: 54/54 lines, 0 junk, 0 duplicates.

- Auto OCR end-to-end on a Galaxy S24+ (Android 16) against a YouTube video with Japanese soft
  subtitles, compared with the subtitle file and the original audio (see AGENTS.md for the
  recipe): every subtitle cue in the run was logged (61/61, 56 exact, mean character accuracy
  0.99), line start times within ±300 ms (p90) of the cue, and every voice clip located in the
  original audio at correlation ≥0.97, starting 160–1080 ms (median ~400 ms) before the subtitle.
- 128 JVM unit tests: screen text layer (subtitle glyphs → screen fractions, signage skipped), book page layout (tategaki order, ruby filter incl. real manga ruby, split sentences, per-character photo positions), H→エ fix, pitch graphs (byte-identical to Yomitan), word audio sources, Anki field mapping, meikiocr decoding, OCR text fixes (一→ー), text areas, line consensus, replay buffer, voice trimming, line tracking, clip windows, subtitle-row
  filtering, deinflector (conjugations across godan/ichidan/suru/kuru/adjectives),
  glossary parsing/rendering, OCR post-processing (merge, furigana filter, normalization),
  VAD segmentation, PCM utilities, Yomitan index parsing.
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

## Roadmap

- Neural VAD (Silero via ONNX Runtime, as GSM uses) for silence trimming that holds up under
  loud BGM; the current energy trimmer is opt-in because it clipped speech under rain/music in
  the YouTube test.
- Emulator capture E2E: MediaProjection frames go black once video plays on the emulator, so
  auto OCR is only validated on real devices. Find a working GPU/rendering setup or a
  frame-injection test path.
- AnkiDroid media naming: the API stores our AAC clip as `…m4a_<n>.mp3`. It plays, but pass the
  proper MIME type/extension so the file name matches the content.
- Vertical text for screen OCR (meikiocr ships a vertical recognizer; not wired up yet). Book mode
  reads vertical print with ML Kit; manga speech bubbles and inline ruby are untested.
- Book mode: share a photo straight from the camera/gallery into DSM; pinch-zoom on the page.
- Pitch accent in the lookup popup (the card already gets the graph); jidoujisho-style graph option.
- Hotkey toggle for the live/lookup overlay (gamepad button or volume key via an accessibility
  service), as meikidroid does — handy on Android handhelds.
- Faster auto OCR on low-end devices: detector only when the area changes, smaller detector option.
- Optional ASR engines (e.g. whisper.cpp or Moonshine via JNI) as an alternative/complement to OCR
  for games with hard-to-read fonts.
- Per-dictionary frequency display modes and pitch-accent graphs.
- Quick Settings tile and share-sheet mining.

## Credits

This project stands on the shoulders of these projects — thank you:

- **[GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner)** (GPL-3.0) — the whole
  concept: OCR on untranslated games, popup lookups, one-press Anki cards with audio, the two-pass
  OCR pipeline and the sentence-audio editing workflow.
- **[Yomitan](https://github.com/yomidevs/yomitan)** (GPL-3.0) — the dictionary format, deinflection
  approach, structured-content glossaries and popup UX that this app implements for Android.
- **[Yomihon](https://github.com/yomihon/yomihon)** (Apache-2.0) — the Kotlin Japanese deinflector in
  `JapaneseDeinflector.kt` is adapted from this project (which in turn credits Jiten). Also a great
  reference for Android Yomitan-dictionary parsing.
- **[Jiten](https://github.com/Sirush/Jiten)** — deinflection rule data used by the deinflector.
- **[meikiocr](https://github.com/rtr46/meikiocr)** by rtr46 (code Apache-2.0, models
  [meiki.text.detect.v0](https://huggingface.co/rtr46/meiki.text.detect.v0) and
  [meiki.txt.recognition.v0](https://huggingface.co/rtr46/meiki.txt.recognition.v0), LGPL-3.0) —
  the default OCR engine; the models are downloaded from Hugging Face at runtime and the
  pre/post-processing in `MeikiDecoder.kt` is ported from its reference pipeline.
- **[meikidroid](https://github.com/rtr46/meikidroid)** by rtr46 — Android game OCR overlay that
  served as a reference for the region-capture + overlay workflow and the hotkey idea.
- **[ONNX Runtime](https://onnxruntime.ai/)** (MIT) — runs meikiocr on device.
- **[ML Kit](https://developers.google.com/ml-kit)** (Google) — on-device Japanese text recognition (alternative engine).
- **[AnkiDroid](https://github.com/ankidroid/Anki-Android)** (GPL-3.0) — the AnkiDroid API used for
  card creation.
- **[AnkiConnect](https://foosoft.net/projects/anki-connect/)** — the HTTP API used for desktop Anki.
- **[OwOCR](https://github.com/AuroraWright/owocr)** — two-pass OCR concepts used by GSM.
- **[Aozora Bunko](https://www.aozora.gr.jp/)** — the bundled sample image uses a passage from
  走れメロス by 太宰治 (public domain), with ruby markup, which doubles as a furigana-filter test case.
- **[Kanjium](https://github.com/mifunetoshiro/kanjium)** (CC BY-SA 4.0) — the one-click pitch accent
  data. "The pitch accent notation, verb particle data, phonetics, homonyms and other additions or
  modifications to EDICT, KANJIDIC or KRADFILE were provided by Uros O. through his free database."
- **[Jitendex](https://jitendex.org/)** and **[jmDict for Yomitan](https://github.com/yomidevs/jmdict-yomitan)**
  — the one-click recommended dictionaries (installed from their official releases).

Droid Sentence Miner is not affiliated with Anki, AnkiDroid, Yomitan, Jiten or GameSentenceMiner.

## License

[GPL-3.0](LICENSE). The adapted Yomihon deinflector remains available under its Apache-2.0 license
wherever required; see the file header for details.
