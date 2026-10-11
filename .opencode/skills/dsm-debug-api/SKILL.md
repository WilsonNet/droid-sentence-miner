---
name: dsm-debug-api
description: Drive and validate the Droid Sentence Miner Android app from the CLI through its debug-only localhost HTTP API (adb forward + curl) instead of slow UI taps. Use when testing OCR, capture, lookups or Anki mining on a device/emulator, when asked to "validate the app", "debug DSM", "run a scan on device", or when adb UI automation would otherwise be needed.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Droid Sentence Miner debug API

Debug builds boot a loopback HTTP server (`DebugApi` in `app/src/debug/`, started
reflectively from `DsmApp`). It exposes capture, OCR, dictionary and Anki operations
as JSON so agents can validate the app with text instead of screenshot/tap loops.

## Setup

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Android/Sdk
GRADLE=$HOME/Android/tools/gradle-8.14.3/bin/gradle
ADB=$ANDROID_HOME/platform-tools/adb

# Build, install (debug suffix!), start the app, forward the port
$GRADLE assembleDebug --console=plain
$ADB -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s <serial> shell am start -n io.github.wilsonnet.droidsentenceminer.debug/io.github.wilsonnet.droidsentenceminer.MainActivity
$ADB -s <serial> forward tcp:9099 tcp:9099
curl -s localhost:9099/state
```

The server lives in the app process; installing a new APK kills it. `curl` may be
blocked by wrappers — use `/usr/bin/curl` if a wrapper interferes.

`/startoverlay` boots the foreground service; the first capture needs the system
MediaProjection consent. Call `/request_projection`, then on the device tap:
**Share one app → Share entire screen → Share screen** (the dialog defaults to
single-app; entire-screen is usually what you want for general overlay scanning).
Because installs kill the process, this consent dance repeats after every install.

## Endpoints

All GET, all JSON (except `frame.png`). Regions are screen fractions unless noted.

| Endpoint | What it does |
| --- | --- |
| `/state` | Service/capture status, saved region, last scan, panel state, OCR prefs |
| `/request_projection` | Ask for MediaProjection consent (shows system dialog on device) |
| `/scan?l=&t=&r=&b=` | Full pipeline: grab frame → crop → OCR → dictionary lookup. Omit the region to reuse the saved one |
| `/frame.png?l=&t=&r=&b=` | PNG of what MediaProjection actually sees (omit region for the raw full frame) |
| `/ocr?file=/sdcard/...&twoPass=1&noise=1` | OCR an image file; returns text, per-line boxes + confidences, and dictionary matches |
| `/lookup?word=<urlencoded>` | Dictionary lookup summary (reading, term count) |
| `/mine?word=&sentence=` | Look a word up and add a card via the configured Anki backend (AnkiDroid/AnkiConnect) |
| `/dicts` | Installed dictionaries with term/kanji counts and zip paths |
| `/install_dict?id=jitendex` | Download + import a recommended dictionary in the background (`/state.dictInstall` shows progress; ids: jitendex, jmdict, kanjidic) |
| `/anki_notes?word=&model=` | Find notes by first field via the AnkiDroid provider; returns every field name → value, plus tags. Defaults to the configured model |
| `/anki_meta` | AnkiDroid decks and note types as id → name maps |
| `/startoverlay`, `/stopoverlay` | Start/stop the floating overlay service |

Example output:

```json
{"ok":true,"text":"日本でもねもう少ないわけよ","elapsedMs":194,"matchCount":1,
 "status":"OCR 194 ms · 1 words found","region":[0.083,0.325,0.917,0.415]}
```

## Recipes

```bash
# What is the app actually capturing? (the most useful debugging step)
curl -s "localhost:9099/frame.png" -o frame.png && open frame.png
curl -s "localhost:9099/frame.png?l=0.08&t=0.32&r=0.92&b=0.42" -o crop.png && open crop.png

# OCR hierarchy: final text vs each ML Kit line and its box
curl -s "localhost:9099/ocr?file=/sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/test.png" | python3 -m json.tool

# Whole loop in one line
curl -s "localhost:9099/scan?l=0.08&t=0.32&r=0.92&b=0.42" | python3 -m json.tool

# Fresh device? Install Jitendex (background; poll /state until dictInstall.done)
curl -s "localhost:9099/install_dict?id=jitendex"
curl -s localhost:9099/state | python3 -c "import json,sys; print(json.load(sys.stdin).get('dictInstall'))"
curl -s localhost:9099/dicts | python3 -m json.tool

# Mine, then verify the card fields exactly as AnkiDroid stored them
curl -s "localhost:9099/mine?word=%E5%B0%91%E3%81%AA%E3%81%84&sentence=%E6%97%A5%E6%9C%AC%E3%81%A7%E3%82%82%E5%B0%91%E3%81%AA%E3%81%84"
curl -s "localhost:9099/anki_notes?word=%E5%B0%91%E3%81%AA%E3%81%84" | python3 -m json.tool
```

Push test images where the app can read them without media permissions:

```bash
$ADB -s <serial> push test.png /sdcard/Android/data/io.github.wilsonnet.droidsentenceminer.debug/files/
```

## Gotchas

- **Scoped storage**: `/ocr?file=` cannot read `/sdcard/Pictures` (needs READ_MEDIA_IMAGES).
  Use the app's own external files dir as above, or push into `/data/local/tmp` and `run-as`.
- **Single-app vs entire-screen capture**: if consent was granted for one app, frames only
  contain that app. Re-consent with "Share entire screen" when scanning arbitrary UIs.
- **Static screens**: MediaProjection produces no new buffers when nothing changes; the
  service caches the last frame (`ScreenCaptureManager.lastFrameBitmap`) so rescans work.
  If `/scan` returns "Could not capture the screen", the projection is either stopped
  (rotation releases it — re-run `/request_projection`) or the service was restarted.
- **Thread affinity**: anything that touches views (e.g. `performScan`) must run on the
  main thread; `DebugApi` posts/hops as needed. Keep it that way when extending it.
- **Starting the service from the API**: Android 12+ blocks foreground-service starts from
  the background, so `/startoverlay` first brings `MainActivity` forward. Launch the app
  at least once after each install, or the API server itself won't be running.
- **AnkiDroid's collection is not adb-readable** on recent versions (scoped storage:
  `/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.anki2`).
  Verify mined cards with `/anki_notes` instead of pulling the SQLite file.
- The API only exists in debug builds; `DsmApp` finds it with `Class.forName` +
  `INSTANCE` reflection, so release builds are untouched.
- Post-install the overlay must be restarted and capture re-consented; verify with
  `/state` (`overlayService: true`, `captureReady: true`).

## Validating checklist (suggested order)

1. `/state` — service running, capture ready, dictionaries imported.
2. `/frame.png` — frames are not black (emulators with SwiftShader often are).
3. `/scan` over a known text region — check `text` matches ground truth.
4. `/scan` twice in a row — confirms the static-frame cache works.
5. `/ocr?file=` — ML Kit accuracy on a pushed image, plus line boxes.
6. `/lookup` a known word — dictionary data is live.
7. `/mine` a word+sentence — inspect the deck in AnkiDroid (deck/model come from prefs).
