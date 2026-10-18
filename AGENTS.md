# AGENTS.md — working on Droid Sentence Miner

Notes for CLI/agent-driven development of this Android app. No Android Studio is used.

## Every task ends with a docs pass

After any change to behavior, UI, settings, toolchain, debug API, dependencies or measured
numbers, update the markdown docs **in the same push**: `README.md`, this file, and the skills in
`.opencode/skills/` (also visible to Claude Code via `.claude/skills`). The checklist of which file
owns what is in `.opencode/skills/dsm-docs/SKILL.md` — follow it before committing.

Skills: `dsm-debug-api` (drive/validate the app over adb), `dsm-ocr-eval` (measure OCR accuracy
with ground truth), `dsm-docs` (docs checklist).

## Git

Commit as Wilson Neto <wilsonpsn@hotmail.com>. The SSH key needs a passphrase, so push over HTTPS
with the GitHub CLI token:

```bash
git -c credential.helper= -c credential.helper='!gh auth git-credential' \
  push https://github.com/WilsonNet/droid-sentence-miner.git main
```

## Toolchain

Linux (current dev box, no sudo needed — everything lives under `~/Android`):

```bash
export JAVA_HOME=$HOME/Android/tools/jdk-21.0.12.1+1   # Temurin 21 tarball
export ANDROID_HOME=$HOME/Android/Sdk                   # sdk.dir is also in local.properties
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
GRADLE=./gradlew
ADB=$ANDROID_HOME/platform-tools/adb
```

macOS: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, same SDK layout.

**Android CLI** (`android`, installed to `~/.local/bin`) handles SDK packages, emulators, screenshots,
UI dumps and docs: `android sdk install platforms/android-36 build-tools/35.0.0 emulator`,
`android layout --no-idle --flat`, `android screen capture -o s.png`, `android docs search "..."`.
`android init` / `android skills add <id>` install its agent skills.

Use the SDK's `adb` (first on `PATH` above). A distro `/usr/bin/adb` of a different version
kills the server on every call and devices flap between `device` and `offline`.

Debug builds are signed with the committed `app/debug.keystore`, so APKs built on any machine
upgrade each other in place (no uninstall, dictionaries survive).

Versions: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.12, compileSdk 36, minSdk 26, targetSdk 35,
Compose BOM 2024.12.01, Room 2.8.5, ML Kit Japanese 16.0.1, ONNX Runtime Android 1.31.0,
AnkiDroid API v2.24.1 (JitPack). APK ships arm64-v8a, armeabi-v7a and x86_64 only (`ndk.abiFilters`).

## Common commands

```bash
# Build + unit tests
$GRADLE testDebugUnitTest assembleDebug --console=plain

# On-device instrumented tests (restrict to the emulator! gradle otherwise targets all devices)
ANDROID_SERIAL=emulator-5554 $GRADLE connectedDebugAndroidTest

# Single instrumentation class
ANDROID_SERIAL=emulator-5554 $GRADLE connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.wilsonnet.droidsentenceminer.audio.AudioPipelineInstrumentedTest

# Emulator lifecycle (Wayland hosts need QT_QPA_PLATFORM=xcb for a window)
QT_QPA_PLATFORM=xcb $ANDROID_HOME/emulator/emulator -avd dsm_test -gpu host -no-snapshot-save -no-boot-anim &
$ADB -s emulator-5554 wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'

# Install / permissions (debug builds are suffixed .debug)
$ADB -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5554 shell appops set io.github.wilsonnet.droidsentenceminer.debug SYSTEM_ALERT_WINDOW allow
$ADB -s emulator-5554 shell pm grant io.github.wilsonnet.droidsentenceminer.debug android.permission.RECORD_AUDIO
$ADB -s emulator-5554 shell pm grant io.github.wilsonnet.droidsentenceminer.debug android.permission.POST_NOTIFICATIONS
$ADB -s emulator-5554 shell pm grant io.github.wilsonnet.droidsentenceminer.debug com.ichi2.anki.permission.READ_WRITE_DATABASE
```

## Debug API (text control over adb)

Debug builds start a loopback HTTP API from `DsmApi`/`DebugApi` (`app/src/debug/`,
reflective boot in `DsmApp`), so validation is `curl`, not screenshot loops:

```bash
$ADB -s <serial> forward tcp:9099 tcp:9099
curl -s localhost:9099/state
curl -s "localhost:9099/scan?l=0.08&t=0.32&r=0.92&b=0.42" | python3 -m json.tool
curl -s localhost:9099/frame.png -o frame.png   # what MediaProjection actually sees
curl -s "localhost:9099/ocr?file=/sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/test.png"
curl -s "localhost:9099/lookup?word=%E3%81%AA%E3%81%84"
curl -s "localhost:9099/mine?word=%E3%81%AA%E3%81%84&sentence=..."   # Anki card via configured backend
```

Endpoints: `/state`, `/scan`, `/frame.png`, `/ocr` (`l,t,r,b` crops the file first), `/lookup`, `/mine`, `/dicts`,
`/autoocr?on=1|0`, `/lines?limit=`, `/clip.wav?id=&trim=0|1`, `/line_shot.jpg?id=`, `/mine_line?id=&word=`,
`/set_region?l=&t=&r=&b=&orientation=`, `/clear_lines`, `/add_line?text=&ago=&dur=`,
`/install_ocr_models` (meikiocr download), `/ocr?engine=meiki|mlkit`, `/select_areas` (opens the area editor),
`/install_dict?id=jitendex` (background install; progress in `/state.dictInstall`),
`/anki_notes?word=` (field-level card verification via the AnkiDroid provider),
`/anki_meta` (decks + note types), `/startoverlay`, `/stopoverlay`, `/request_projection`.
Regions are screen fractions.
See `.opencode/skills/dsm-debug-api/SKILL.md` for the full reference and recipes.

## Gotchas learned the hard way

- **Auto OCR / text log / replay buffer** (GSM model): `AutoOcrController` polls new frames,
  OCRs the saved region, and `TextLineTracker` turns stable reads into lines (start = first
  differing read). `AudioRingBuffer` keeps the last N minutes of mono 24 kHz playback audio by
  wall-clock time; a line's clip is `[start − before, min(end, next.start) + after]`
  (`ClipWindow`), then `VoiceTrimmer`. Lines live in a separate Room DB (`textlog.db`) —
  never add tables to `dict.db`, it uses destructive migration and would wipe dictionaries.
- **OCR engine = meikiocr by default** (`ocr/MeikiOcr.kt`, ONNX Runtime): a line detector + a
  character recognizer trained on Japanese games, ported from the reference Python pipeline
  (BGR input in [0,1], detector letterbox to 960x544 top-left, recognizer lines resized to height
  32 and padded to 960, NMS on x-intervals — `MeikiDecoder`). Models (LGPL-3.0, 31 MB) are
  downloaded on demand by `MeikiModels` into `files/models`; until then `AppContainer.ocrEngine()`
  falls back to ML Kit. One shared instance, `synchronized`: auto OCR and `/ocr` calls serialize,
  so benchmark with auto OCR stopped. Measured 3–19× fewer character errors than ML Kit on game
  and subtitle fonts — numbers and recipe in `.opencode/skills/dsm-ocr-eval/SKILL.md`.
  `OcrTextFixes` (applied in `OcrPostProcessor.toSentenceText`) turns 一 after kana into ー,
  except before counters (もう一つ, お一人).
- **Text areas are per orientation** (`DsmPrefs.autoAreas(landscape)`, encoded by `TextArea`):
  fractions drawn in portrait point at the wrong pixels once a video/game goes fullscreen
  landscape (this truncated sentences before). `/set_region` writes the current orientation
  (or `&orientation=landscape|portrait`). Auto OCR pauses (`AutoOcrController.paused`) while
  `MainActivity` is resumed, the panel/area editor is open, or a single-app capture's app is not
  visible (`MediaProjection.Callback.onCapturedContentVisibilityChanged`, API 34).
- **Anki field mapping** (`AnkiFieldMapper`): aliases are in *preference* order, not field
  order. Anime Cards (the user's note type: Word, Graph, Reading, Glossary, Context Sentence, Word
  Audio, Audio, Picture, Sentence): its template shows `{{Sentence}}` on the front when filled,
  turning a word card into a sentence card, so the sentence goes to *Context Sentence* and
  *Sentence* stays empty (matches the user's 5k Yomitan/GSM notes). "Audio" + "Sentence Audio" ⇒
  Audio is word audio; no word-audio field ⇒ the `[sound:]` is appended to Reading. Foreign note
  types get the sentence as plain text; the glossary is wrapped in `div.yomitan-glossary`.
  Inspect a deck read-only with `/anki_sample?model=` and `/anki_templates?model=`, and render a
  card without adding it with `/preview_card?word=&line=`.
- **Word audio** (`anki/WordAudio.kt`): Yomitan's Japanese chain — jpod101 direct
  (`audiomp3.php`, kana-only words query by kana; reject SHA-256 `ae6398b5…` placeholder) →
  JapanesePod101 `dictionary_post` search (split rows on the `dc-result-row` *class*, not the
  substring, or `dc-result-row__checkbox` breaks parsing) → Jisho `audio_{term}:{reading}` →
  optional custom URL first.
- **Shared text selections** (`ProcessTextActivity` → `TextLog.lineForSharedText`): a word is
  matched to the newest of the last 300 lines containing it (earlier bare-word shares skipped),
  otherwise logged as a manual line, so Galaxy AI / Circle to Search selections mine with sentence
  + audio. Those engines have no API, so they can't drive auto OCR.
- **Consensus text**: `TextLineTracker` keeps every read of the open line and emits `Settled`
  with the medoid (`TextSimilarity.consensus`) at 3/5/8/13 reads and at line end; ML Kit errs
  differently per read on stylized hardsubs, so this fixes most single-character errors.
- **Samsung freezes cached apps**: with DSM in the background and no foreground service, the
  debug API stops answering. Bring `MainActivity` forward before calling it.
- **Rotation keeps the projection**: `ScreenCaptureManager.onScreenSizeChanged()` resizes the
  virtual display and swaps in a new `ImageReader`, so starting in portrait and switching to a
  landscape game keeps capturing (no re-consent).
- **YouTube test bed**: softsub video `gM7sP8vT40o` + `yt-dlp --write-subs --sub-langs ja` gives
  ground truth; pick the plain "日本語" caption track (not auto-generated/translated) in the
  player settings. `yt-dlp -f bestaudio --download-sections "*0:00-8:00"` gives reference audio
  to cross-correlate `/clip.wav` output against.

- **Prefer the debug API over UI automation.** Debug builds expose a loopback text API
  (`curl localhost:9099/...` via `adb forward tcp:9099 tcp:9099`) for capture frames, OCR,
  lookups and mining. Use it to validate flows; reserve `input tap`/screenshots for
  permission dialogs. Full reference: `.opencode/skills/dsm-debug-api/SKILL.md`.
- **MediaProjection only delivers frames when the screen changes.** On a static screen a
  second `/scan` used to fail with "Could not capture the screen"; `ScreenCaptureManager`
  now caches the last frame (`lastFrameBitmap`) as a fallback. Consent dialog (Android 14+):
  *Share one app* → *Next* → pick the app (recommended: the game), or switch the spinner to
  *Share entire screen* → *Share screen*.
- **`adb forward` silently disappears** when another adb binary restarts the server; if
  `curl localhost:9099` is refused, re-run `adb forward tcp:9099 tcp:9099`. Keep the phone awake
  during long runs (`adb shell svc power stayon usb`) or Samsung freezes the app.
- **`connectedAndroidTest` uninstalls the app** (wiping dictionaries/settings) when it finishes,
  and `Gradle 8.14` targets *every* connected device — always set `ANDROID_SERIAL`.
- **AnkiDroid API `addNote(modelId, deckId, fields, tags)`** — model first, deck second. Passing them
  the other way produces `No such notetype: '<deckId>'` from the Rust backend.
- **AnkiDroid keeps its collection under scoped storage** (2.24.1:
  `/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2`) — it survives
  DSM uninstall, and `adb shell` cannot read it. Verify cards with `/anki_notes` (content provider)
  instead of pulling the SQLite file. For clean API tests, AnkiDroid's own uninstall/reinstall
  clears the collection.
- **Emulator capture is unreliable**: with `-gpu host` MediaProjection frames only contain our own
  overlays; with `-gpu swiftshader_indirect` UI is captured but video playback goes black and
  silent. Validate capture/auto OCR on a real device; the emulator is fine for UI, Room,
  lookups and AnkiDroid card creation (`/add_line` + `/mine_line`).
- **Android 14/15 FGS ordering**: a foreground service may only use the `mediaProjection` type
  *after* consent exists (`android:project_media` app-op). The permission activity must NOT be
  `noHistory` or it dies while the system consent UI is up.
- **Cleartext HTTP is blocked by default** on targetSdk 28+; `res/xml/network_security_config.xml`
  permits it for AnkiConnect.
- Emulator AVD: `dsm_test` (API 35, google_apis_playstore, x86_64). AnkiDroid test APK:
  `gh release download v2.24.1 -R ankidroid/Anki-Android -p "variant-abi-AnkiDroid-2.24.1-x86_64.apk"`
  (grant `appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow`, then restart it). Never mine
  test cards into a personal AnkiDroid collection on a real phone; Jitendex installs via the app's one-click button or
  `curl localhost:9099/install_dict?id=jitendex` (~40 s, 436,555 terms).

## Local test servers (used during development)

- Mock AnkiConnect: a tiny Python HTTP server handling `version/deckNames/modelNames/createDeck/createModel/findNotes/storeMediaFile/addNote`
  is enough to validate mining; point the app at `http://10.0.2.2:<port>`.
- A static `python3 -m http.server` can serve a Japanese text/audio page for OCR/audio tests.

## Project layout

```
app/src/main/java/io/github/wilsonnet/droidsentenceminer/
├── dict/      Yomitan import, Room storage, deinflector, lookup, glossary rendering
├── ocr/       meikiocr (ONNX) + ML Kit engines, preprocessing, GSM-style post-processing
├── capture/   MediaProjection capture, auto OCR loop, text areas
├── textlog/   Text log DB, line tracker/consensus, clip windows
├── audio/     AudioPlaybackCapture, VAD, WAV/AAC encoders, PCM utils
├── overlay/   Foreground service, bubble, region selector, Compose panel
├── anki/      AnkiDroid + AnkiConnect bridges, card builder/miner
├── ui/        Compose screens, theme, lookup dialogs, audio editor
└── core/      Settings (DataStore)

tools/ocr-eval/   OCR benchmark scripts (desktop meikiocr, on-device runner, scorer, subtitle burner)
.opencode/skills/ dsm-debug-api, dsm-ocr-eval, dsm-docs (symlinked as .claude/skills)
```
