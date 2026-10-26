# AGENTS.md — working on Droid Sentence Miner

Notes for CLI/agent-driven development of this Android app. No Android Studio is used.

## Every task ends with a docs pass

After any change to behavior, UI, settings, toolchain, debug API, dependencies or measured
numbers, update the markdown docs **in the same push**: `README.md`, this file, and the skills in
`.opencode/skills/` (also visible to Claude Code via `.claude/skills` and Antigravity via `.agents/skills`). The checklist of which file
owns what is in `.opencode/skills/dsm-docs/SKILL.md` — follow it before committing.

Skills: `dsm-debug-api` (drive/validate the app over adb), `dsm-navigation` (navigate DroidDeck,
Nightshade, MediaProjection consent dialogs and DSM UI over adb with exact coordinates), `dsm-ocr-eval`
(measure OCR accuracy and latency/CPU with ground truth), `dsm-docs` (docs checklist),
`wisdom-increaser` (harvest lessons, coordinates and measured numbers into skills/docs after every
long task), `dsm-promo-media` (README screenshots and demo videos: phone-size capture on the tablet,
scripted mining recordings, GIF previews), `dsm-release` (signed APK → GitHub Releases on a pushed
`v*` tag).

## Releases — only when asked

Never create or push a `v*` tag unless the user explicitly asks for a release: the tag *is* the
release (`.github/workflows/release.yml` builds a signed APK and publishes it). Recipe, signing key
and secrets: `.opencode/skills/dsm-release/SKILL.md`.

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

Debug builds are named "Droid Sentence Miner (Dev)" (`app/src/debug/res/values/strings.xml`), so
they're told apart from a release install in the launcher. They are signed with the committed `app/debug.keystore`, so APKs built on any machine
upgrade each other in place (no uninstall, dictionaries survive).

Versions: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.12, compileSdk 36, minSdk 26, targetSdk 35,
Compose BOM 2024.12.01, Room 2.8.5, ML Kit Japanese 16.0.1, ONNX Runtime Android 1.31.0,
AnkiDroid API v2.24.1 (JitPack). APK ships arm64-v8a, armeabi-v7a and x86_64 only (`ndk.abiFilters`).

## Common commands

```bash
# Build + unit tests
$GRADLE testDebugUnitTest assembleDebug --console=plain

# NEVER launch an Android emulator! It kills the host machine. Always test on the physical device
# (Xiaomi Pad 7 `c2bd6564` or Galaxy phone). If the tablet is locked (`mInputRestricted=true` in
# `dumpsys window policy`), wake it (`input keyevent 224`) and send a desktop notification with
# `notify-send -u critical "Droid Sentence Miner" "..."` asking the user to unlock it.

# Install / permissions (debug builds are suffixed .debug and labeled "Droid Sentence Miner (Dev)")
$ADB -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s <serial> shell appops set io.github.wilsonnet.droidsentenceminer.debug SYSTEM_ALERT_WINDOW allow
$ADB -s <serial> shell pm grant io.github.wilsonnet.droidsentenceminer.debug android.permission.RECORD_AUDIO
$ADB -s <serial> shell pm grant io.github.wilsonnet.droidsentenceminer.debug android.permission.POST_NOTIFICATIONS
$ADB -s <serial> shell pm grant io.github.wilsonnet.droidsentenceminer.debug com.ichi2.anki.permission.READ_WRITE_DATABASE
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

Endpoints: `/state`, `/scan`, `/frame.png`, `/ocr` (`l,t,r,b` crops the file first),
`/ocr_bench?dir=&n=&threads=&variants=&anyw=&xnnpack=&cfg=` (meikiocr per-stage timings over a folder), `/lookup`, `/popup.html?word=&dark=` (exact popup WebView HTML), `/mine`, `/dicts`, `/dict_updates`,
`/autoocr?on=1|0`, `/lines?limit=`, `/clip.wav?id=&trim=0|1`, `/line_shot.jpg?id=`, `/mine_line?id=&word=`,
`/set_region?l=&t=&r=&b=&orientation=`, `/clear_lines`, `/add_line?text=&ago=&dur=`,
`/quick?l=&t=&r=&b=` (bubble quick lookup of a box), `/install_ocr_models` (meikiocr download), `/ocr?engine=meiki|mlkit`, `/select_areas` (opens the area editor),
`/install_dict?id=jitendex` (background install; progress in `/state.dictInstall`),
`/anki_notes?word=` (field-level card verification via the AnkiDroid provider),
`/anki_meta` (decks + note types), `/book?file=` (book-mode page OCR → paragraphs, shown in the Book tab), `/live?show=1&mode=` (live text window), `/mic_fallback?on=`, `/startoverlay`, `/stopoverlay`, `/request_projection`.
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
- **meikiocr model cuts & defaults** (`tools/meiki-variants`, `assets/meiki`, `MeikiOcr.Options`):
  both ONNX models bake their input size into constants (pos-embed, anchors, level sizes). The cuts
  keep the original normalized frame — the detector "sees the top H rows of a 544 frame"
  (anchor/pos-embed rows sliced, GridSample y scaled by 544/H), the recognizer any width W (AIFI
  still gets all 30 tokens, the missing ones are the original's black-padding tokens) — so
  `orig_target_sizes` stays (960/scale, 544/scale) and (960, 32). Regenerating the constants for the
  small frame instead garbles text (box heights come out 3.4× short). The graphs' initializers are
  external data pointing *into the downloaded originals* (offsets), so `MeikiModels.installVariants`
  only copies them when the originals' SHA-256 match. Height = smallest cut ≥ content + 32 rows
  (`MeikiDecoder.detectorHeight`; tighter drifts), width = widest line + 64 (`recognizerWidth`).
  Default options are `threads = 3, session.intra_op.allow_spinning = 0`: on Xiaomi Pad 7 vs the
  544×960 / 4-spinning-thread original, cuts + `t=3, spin=0` drop live latency and CPU core-time per
  OCR from 185 → 114 ms and 768 → 350 CPU-ms under Nightshade (`Nightshade.exe` ~80 % CPU), and from
  159 → 93 ms and 699 → 311 CPU-ms (173 % → 78 % continuous CPU) on live YouTube hardsubs. Tried and
  slower on S24+ (idle): XNNPACK EP (2×) and thread affinity. Full table: `.opencode/skills/dsm-ocr-eval/SKILL.md`.
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
  card without adding it with `/preview_card?word=&line=`. A mapping picked in Settings → Anki →
  Fields (`DsmPrefs.ankiFieldMap(model)`, JSON per note type: field → `CardFields` name, "" = empty)
  replaces all name matching for that note type, including the Reading word-audio fallback.
- **Pitch graph** (`dict/PitchAccent.kt`): port of Yomitan's `createPronunciationGraph` with the
  inline styles Yomitan bakes into Anki fields; unit-tested byte-for-byte against SVGs from the
  user's own cards. Pitch comes from `term_meta` rows with mode `pitch` (any Yomitan pitch dict) —
  Kanjium's `accents.txt` is converted into such rows by `YomitanImporter.importKanjiumAccents`
  (`RecommendedDictionary.Kind.KANJIUM_ACCENTS`). `/lookup?word=` now lists meta rows.
- **Word audio** (`anki/WordAudio.kt`): Yomitan's Japanese chain — jpod101 direct
  (`audiomp3.php`, kana-only words query by kana; reject SHA-256 `ae6398b5…` placeholder) →
  JapanesePod101 `dictionary_post` search (split rows on the `dc-result-row` *class*, not the
  substring, or `dc-result-row__checkbox` breaks parsing) → Jisho `audio_{term}:{reading}` →
  optional custom URL first.
- **Book mode** (`book/`, `ui/screens/BookScreen.kt`): photos come from a camera app via
  `TakePagePhoto` (a `TakePicture` that targets Samsung Camera `com.sec.android.app.camera` when it
  resolves, else any `IMAGE_CAPTURE` app; both are in manifest `<queries>`) into `cache/camera/page.jpg` (FileProvider path `camera`) — no CAMERA permission;
  declaring one would make `TakePicture` require it. Decoded upright (`ImageDecoder` applies EXIF)
  and capped at 3000 px (`BookOcr.loadPhoto`). Always ML Kit `recognizeBlocks`, never meikiocr
  (horizontal-only) and never `OcrPostProcessor.merge` (joins horizontal rows, garbles columns);
  `PageLayout` (unit-tested) does tategaki order, ruby filtering (kana-only, ≤12 chars, <0.6× median
  column width — checked *after* `OcrTextFixes`, since ruby エーダイ comes back as `Hーダイ`) and
  split-sentence joins. NFKC turns …… into dots, so `PageLayout.clean` restores ……. Test page
  source: Black Jack ni Yoroshiku, free reuse, PDFs at densho810.com/free (`dl/001bj.zip`). The
  page lives in `AppContainer.bookPage`, so it survives tab switches.
- **Yomitan structured-content & Jitendex CSS** (`dict/GlossaryHtml.kt`, `dict/GlossaryModels.kt`,
  `dict/DictArchive.kt`): Yomitan's `structured-content` nodes put `data: {"class": "tag", "content":
  "part-of-speech-info"}` into `data-sc-*` HTML attributes (`data-sc-class="tag"`,
  `data-sc-content="part-of-speech-info"`) and style keys in camelCase (`listStyleType` →
  `list-style-type`), which Jitendex's `styles.css` matches on for colored POS/misc/field/dialect
  tags, sense callout boxes (`info-gloss`, `sense-note`, `xref`, `antonym`, `example-sentence` with
  lime-mixed `example-keyword`), and the `forms` variant table (`td[data-sc-class="form-pri"] > span`
  radial-gradient circles with `::before` `△`/`✕`/`古`/`旧`/`▽`/`◇`). `GlossaryHtml.wrapDocument`
  defines Yomitan's CSS variables (`--text-color`, `--fg`, `--background-color`, `--canvas`,
  `--font-size-no-units`) and category colors on `.gloss-tag[data-category]` (`popular`, `frequent`,
  `archaism`, etc. from `tag_bank`); `CardHtml.meaning` embeds `<style>.yomitan-glossary { … }</style>`
  so Anki cards get the same styling. `DictArchive.styles` caches `styles.css` in memory per dict ID
  and `YomitanImporter` stores it on `DictionaryEntity.styles`. Internal query links (`?query=…`) in
  `TAP_SCRIPT` trigger recursive lookup directly.
- **Dictionary update checks & in-place replacement** (`dict/RecommendedDictionaries.kt`,
  `dict/YomitanImporter.kt`, `dict/DictArchive.kt`): `DictDownloader.checkUpdates` reads `index.json`
  from each installed dictionary's zip (`DictArchive.index`, falling back to
  `RecommendedDictionary.indexUrl` for Jitendex `https://jitendex.org/static/yomitan.json`), fetches
  the remote `index.json`, and compares `revision` (`isNewerRevision`). Because Jitendex embeds the
  release date in `title` (`"Jitendex.org [2026-10-03]"`), `YomitanImporter.findExistingForImport`
  matches by `baseDictionaryTitle` (stripping trailing `[...]`) so updating replaces the old
  dictionary in-place, preserving `priority` and `enabled`, deleting the old zip/image cache, and
  invalidating `DictArchive`. Checked automatically on Home, Settings, and Dictionaries screens (plus
  `/dict_updates`).
- **Per-headword entries** (`TermLookup.LookupDetails.entries` / `forEntry`, unit-tested): Yomitan's
  "group" mode — terms grouped by (expression, kana-folded reading, deinflection reasons) in first
  appearance order. With >1 entry `CardMiner.buildPopupHtml` renders a header + `.dsm-add` button per
  entry; `ENTRY_SCRIPT` (capture-phase click, so the tap scanner never sees it) calls `dsm.mine(i)`
  and `DefinitionWebView` pushes states back with `dsmSetStates` after `onPageFinished`. Mining
  entry i builds the card from `forEntry(i)` only.
- **Lookup popup** (`WordLookupPopup`, `DefinitionComponents.kt`): non-focusable `Popup` placed by
  `AnchoredPopupPosition` from the word's window rect (`getPathForRange` + `positionInWindow`), so
  taps pass through to the text. Book tab pre-creates a WebView after the first frame (Chromium cold
  start is the slow part). Recursive lookup: `TAP_SCRIPT` (JS on, `dsm` JavascriptInterface) sends
  the text from the tapped character (ruby stripped) and its block; `recursiveTap` pushes the first
  scanned word onto the popup's stack. Hold-to-select is a hand-rolled `awaitEachGesture` (tap /
  long-press-drag / scroll by touch slop). On adb, test drags with `input draganddrop`, not
  `input motionevent` (separate injections don't deliver the release).
- **Overlay reader** (`PageReader.kt`): `PageBlock.glyphs` holds one photo box per character of
  `text` — ML Kit symbol boxes (`OcrLine.chars`) when their count matches the cleaned line, else the
  line split evenly (`PageLayout.glyphsOf`); edits keep positions via `PageBlock.withText`. Page →
  screen is fit-center, then zoom/pan in a `graphicsLayer` (origin 0,0); taps are mapped back with
  `toPage`. One `awaitEachGesture` handles tap / hold-drag / pan+pinch.
- **Live text window** (`overlay/LiveTextWindow.kt`, `LiveMode`): compact (one marquee line,
  WRAP_CONTENT height), normal (explicit px size from `liveWindowSizeDp`, corner-grip resize,
  reversed `LazyColumn` of 200 lines that follows the newest only while at the bottom) and
  fullscreen (MATCH_PARENT, opaque; auto OCR pauses because it would OCR this window). Position
  and size are saved per orientation (`liveWindowPosition(landscape)`, decided from screen metrics)
  and reloaded on rotation; mode is global, fullscreen is never restored on reopen. The window
  draws one status-bar height below `params.y` in portrait — offset adb taps accordingly. `ScreenCaptureManager.screenMetrics`
  leaves `density` at 0 — take density from `resources.displayMetrics` (a 0 there made a 0×0
  window). Drive it with `/live?show=1&mode=`.
  A tap on a line maps the finger to a character (`TextLayoutResult` + `positionOnScreen`, `CharTap`)
  and opens the ScreenDictionary popup there (`lookupInLiveLine`); only a long press opens the old
  bottom panel (`showLatestLine`) — tap used to, and it pauses auto OCR, so the screen layer vanished.
  Every hide logs its reason under `DSM-Live` (`hideLiveWindow(reason)`), as do config changes; a
  window detached by the system (not by us) is re-added. Live window vanishing → `adb logcat -s DSM-Live`.
- **Screen dictionary** (`overlay/ScreenDictionary.kt`, `capture/ScreenText.kt`): auto OCR's fast
  read publishes `AutoOcrController.screenText` (lines with per-glyph screen fractions; meikiocr char
  x-intervals mapped back in `MeikiOcr`, ML Kit symbols). The service fits one touchable, transparent
  `TYPE_APPLICATION_OVERLAY` window per line (`ScreenTextLayerView`; per area swallowed taps on game buttons between lines); the popup is
  a separate overlay window placed by `AnchoredPopupPosition` in absolute screen px (a Compose `Popup`
  inside an overlay strip is positioned relative to that strip). While the popup is open the layer is
  frozen and auto OCR pauses (`holdLayer`/`paused`), or it would log the definition text. Media
  pause/play via `AudioManager.dispatchMediaKeyEvent`, only if music was active; resume is delayed
  350 ms so tapping the next word doesn't play/pause-flicker. `/state.screenText` shows the layer
  (with per-line confidence; overlapping duplicate detections keep the surer one).
- **Popup placement** (`ui/components/PopupPlacement.kt`, unit-tested): Popper.js-style — below the
  word if it has ≥260 dp, else flip above, else beside; height = room on that side (capped at 60 %
  only on portrait screens), shifted to stay on screen. Overlay popup windows need an explicit
  width: a `WRAP_CONTENT` window is measured at `config_prefDialogWidth` (320 dp) first and Compose
  never asks for more. The overlay service's Compose `Configuration` can lag rotation, so the overlay
  sizes from `ScreenCaptureManager.screenMetrics`. Width: 420 dp portrait, 600 dp landscape.
  Test the YouTube flow like a user: pause, tap an empty spot to hide the player controls (they
  cover the subtitle and the layer re-reads them), then tap the word.
- **Navigation**: Home · Book · Texts · Settings. Dictionaries and Anki are Settings sub-pages
  (`MainActivity.SettingsSubPage`, back button/gesture returns to the list). At ≥600 dp width
  (tablets, landscape phones) the tabs move to a `NavigationRail` and screens sit in a centered
  column ≤840 dp (`ReadableWidth`); cards are `fillMaxWidth` so they don't shrink to their content.
- **Tap verify** (`ScreenDictionary.lookupFrom`): the popup opens at once from the layer (up to one
  auto OCR interval old), then 250 ms later `AutoOcrController.readNow()` reads the paused frame;
  if the word under the finger changed (rolling captions) the popup moves to it, if the read sees
  other text but nothing there it closes, an empty read changes nothing.
- **Clip end while paused**: an open line has no `endMs`, so its clip used to run to "now" (30 s
  max) when mined from a lookup popup with the video paused. `TextLog.openLinePausedAtMs` (set when
  auto OCR pauses) ends it there. `SubtitleLineFilter.dropUnsure` drops engine lines <0.5 confidence
  when another is ≥0.8 (a hardsub half-hidden under YouTube captions read as garbage, 0.27 vs 0.97).
- **Netflix**: the video surface is DRM-black, but subtitles are drawn as normal views and OCR fine.
  Audio is silent (per-stream capture policy), so cards get no sentence clip. Not supported.
- **Xiaomi Pad 7 / HyperOS 3** (adb `c2bd6564`): the first `adb install` is cancelled by
  `com.miui.securitycenter` even with "Install via USB" on — install once from the file manager,
  later `install -r` works. `appops set SYSTEM_ALERT_WINDOW allow` is reverted on next launch; the
  user must enable "他のアプリの上に重ねて表示" in Settings once. App-private files only: `/book`
  can't read files adb put in `Android/data` on Android 16 — copy with `run-as` into `files/`.
- **Shared text selections** (`ProcessTextActivity` → `TextLog.lineForSharedText`): a word is
  matched to the newest of the last 300 lines containing it (earlier bare-word shares skipped),
  otherwise logged as a manual line, so Galaxy AI / Circle to Search selections mine with sentence
  + audio. Those engines have no API, so they can't drive auto OCR.
- **Consensus text**: `TextLineTracker` keeps every read of the open line and emits `Settled`
  with the medoid (`TextSimilarity.consensus`) at 3/5/8/13 reads and at line end; ML Kit errs
  differently per read on stylized hardsubs, so this fixes most single-character errors.
- **Samsung freezes cached apps**: with DSM in the background and no foreground service, the
  debug API stops answering. Bring `MainActivity` forward before calling it.
- **Bubble modes** (`BubbleMode`, pref `bubbleMode`, picked under *Bubble tap* in the long-press
  menu): QUICK (default) opens `QuickSelectView` — drag a box, `OverlayService.quickLookup` OCRs it
  and `ScreenDictionary.showQuick` lays the box's lines as a layer and pops up the first scanned
  word. The quick layer (`quickActive`) ignores auto OCR updates, skips the tap re-read and goes
  away 400 ms after the popup closes (so a tap on another boxed word still lands). REGION is the
  old `CaptureOverlayView`. meikiocr reads nothing on a word-sized crop (it scales it up to
  960×544 and the glyphs get huge): `padForOcr` puts it top-left on a ≥960×544 canvas at real size.
- **Screenshots without consent** (`capture/ScreenshotAccessibilityService`, API 30+): an
  accessibility service with only `canTakeScreenshot` (no window content, no events). Android 14+
  makes MediaProjection consent single-session, so `OverlayService.grabStill` takes the
  accessibility screenshot for quick lookups and one-off scans and falls back to the projection
  frame; auto OCR stays on MediaProjection (audio). Not enabled + no projection → the bubble
  opens Accessibility settings (below API 30 it requests projection as before). Enabling it is
  the user's call — don't flip `enabled_accessibility_services` over adb on a personal phone.
  `/state.accessibilityScreenshots` shows whether it is bound.
- **Bubble position is a side, not a pixel**: the saved x (`DsmPrefs.bubblePosition`) is a snapped
  edge, so `OverlayService.fitBubble` reads it as left/right and re-fits it to the current screen on
  add and on every config change. A right-edge x saved in landscape (2148) left the bubble off a
  1080-wide portrait screen — invisible but still "there". Screen size comes from
  `ScreenCaptureManager.screenMetrics`, not `captureManager.screenWidth` (stale while not capturing).
- **Rotation keeps the projection**: `ScreenCaptureManager.onScreenSizeChanged()` resizes the
  virtual display and swaps in a new `ImageReader`, so starting in portrait and switching to a
  landscape game keeps capturing (no re-consent). `start()` re-reads the screen size: rotating while
  not capturing left the stale size, and a landscape game came out as portrait frames (no text read).
- **YouTube test bed**: softsub video `gM7sP8vT40o` + `yt-dlp --write-subs --sub-langs ja` gives
  ground truth; pick the plain "日本語" caption track (not auto-generated/translated) in the
  player settings. `yt-dlp -f bestaudio --download-sections "*0:00-8:00"` gives reference audio
  to cross-correlate `/clip.wav` output against.

- **Typewriter text / zero-allocation static-region skip** (`FrameSignature` in
  `AutoOcrController.kt`, `ScreenCaptureManager.pollRegionCrops`): a frame is OCR'd only if it
  differs from the frame *last OCR'd* (not the previous frame), by mean or by any single cell of a
  96×24 area-averaged grid. Comparing consecutive frames with a mean of a bilinear 48×12 downscale
  froze visual novel lines at the first half-typed read. Under 60–144 Hz compositors (`gamescope`,
  YouTube), `ScreenCaptureManager` throttles `ImageReader` acquisitions to `MIN_ACQUIRE_INTERVAL_MS`
  (100 ms), checks `FrameSignature.of(image, region)` directly on the RGBA `ByteBuffer` (bulk
  `IntBuffer` row reads, zero `Bitmap` allocations when static; 5 % CPU on a static Nightshade line
  vs 26–44 % + 150 MB/s GC churn before), crops only the text band when changed, and converts the
  full-screen screenshot `Bitmap` (`latestScreenshot`) only once per logged line (`Event.Started`).
- **DroidDeck / Nightshade / YouTube navigation**: see `.opencode/skills/dsm-navigation/SKILL.md`
  for exact coordinates on the Xiaomi Pad 7 and Galaxy phone (DroidDeck launch, `Steam 一時停止中`,
  Nightshade Quicksave/Quickload, YouTube PiP avoidance, MediaProjection consent).
- **`SubtitleLineFilter` anchors on the row with the most Japanese**: game buttons inside the text
  area (Nightshade's スキップ/クイックロード) became the anchor and the short, bigger dialogue line
  was dropped as "wrong size". Keep areas on the dialogue box. `JapaneseTextScore` ignores
  punctuation in its ratio, or 「百花……っ」 scored as not-Japanese.
- **DroidDeck (Steam) blocks audio capture**: `targetSdkVersion 28`, no
  `allowAudioPlaybackCapture`, so `AudioRecord` gets zeros (logcat `AudioRecordImpl … [mute]`).
  Upstream: Droid-Deck/DroidDeck#1314 (28 is in their initial commit, likely for exec from app
  data; no commit explains it). `AudioCaptureManager` microphone fallback (opt-in pref
  `audioMicFallback`): mic recorder runs alongside, fills the buffer after
  `SILENT_SWITCH_MS` of exact zeros while `isMusicActive`, device audio takes over on the first
  non-zero chunk. Background mic needs FGS type `microphone`, only granted while DSM may use the
  mic — `startForegroundCompat` tries it and falls back without. `NoiseSuppressor` on the mic
  session (pref, default on), then `AudioCompressor` (soft knee, −30 dBFS, 4:1, 5/150 ms; pref
  `audioMicCompressor`, default on) on every mic chunk in `micLoop` — no makeup gain, normalize
  lifts the squeezed clip; every clip goes through `AudioPcm.normalize` (pref, default on) in
  `TextLog.clip`: voiced RMS to −14 dBFS, peaks allowed 6 dB over −1 dBFS and soft-limited
  (tanh) back under it — capping gain at the loudest peak left clips with one shout quiet. DroidDeck pauses the game when it loses focus (consent dialog) — tap 再開.
- **Media keys and games**: a game's audio makes `isMusicActive` true, but media keys go to the
  last media session (a paused YouTube) — the lookup popup's PLAY on close started YouTube in the
  background. `ScreenDictionary.pauseMediaPlayback` re-checks after 500 ms and skips the resume if
  audio is still playing.
- **Mining outlives the popup**: `mineAction` runs on `AppContainer.appScope`; it used the
  popup's composition scope and closing the popup cancelled the card. The popup checks
  `CardMiner.isInAnki` (the bridges' `isDuplicate(word, deck, model)` — first field of the
  configured note type, same rule `addNote` refuses with) and shows ✓ In Anki.
- **Release builds run R8**: native code that looks up Java classes by name breaks when they are
  renamed/stripped — ONNX Runtime crashed on the first meikiocr run (`ClassNotFoundException:
  ai.onnxruntime.TensorInfo`) until `-keep class ai.onnxruntime.**` in `proguard-rules.pro`. Before a
  release, install the release APK and run meikiocr + a lookup (no debug API in release).
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
├── book/      Book mode: page photo loading, ML Kit page OCR, tategaki layout
├── ui/        Compose screens, theme, lookup dialogs, audio editor
└── core/      Settings (DataStore)

tools/ocr-eval/   OCR benchmark scripts (desktop meikiocr, on-device runner/A-B timing, scorer, subtitle burner, hand GT)
tools/meiki-variants/  builds the meikiocr model cuts in app/src/main/assets/meiki (make_assets.py)
.opencode/skills/ dsm-debug-api, dsm-navigation, dsm-ocr-eval, dsm-docs, wisdom-increaser, dsm-promo-media, dsm-release (symlinked as .claude/skills and .agents/skills)
```
