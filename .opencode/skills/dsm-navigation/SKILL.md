---
name: dsm-navigation
description: Navigate DroidDeck, Nightshade (visual novel test bed), MediaProjection consent dialogs, and Droid Sentence Miner UI on the Xiaomi Pad 7 tablet and Galaxy phone over adb without trial-and-error screenshot loops. Use whenever launching games in DroidDeck, advancing or quickloading Nightshade dialogue, accepting screen-capture consent, or navigating DSM screens.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Navigating DroidDeck, Nightshade, and Droid Sentence Miner over adb

Use the Debug API (`dsm-debug-api`) first whenever an endpoint exists. When you need to drive
DroidDeck, Nightshade, or Android system dialogs over `adb`, use the exact flows and coordinates
below instead of guessing from screenshots.

All landscape coordinates below are for the **Xiaomi Pad 7** (`c2bd6564`, `3200 × 2136` landscape,
`2136 × 3200` portrait, 440 dpi, HyperOS 3). Always pass `WaitMsBeforeAsync: 10000` on `adb`
commands that sleep or capture full-resolution screenshots.

> **Never launch an Android emulator** — it kills the host machine. If the physical tablet is locked
> (`mInputRestricted=true` in `dumpsys window policy` or black `screencap`), wake it with
> `$ADB shell input keyevent 224` and notify the user via `notify-send -u critical "Droid Sentence Miner" "Please unlock the tablet..."`.

## 1. Keep DSM alive in the background

HyperOS and Samsung One UI freeze cached app processes within seconds when no foreground service is
running, which causes `curl localhost:9099/...` to time out:

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb -s c2bd6564"
$ADB forward tcp:9099 tcp:9099
$ADB shell am start -n io.github.wilsonnet.droidsentenceminer.debug/io.github.wilsonnet.droidsentenceminer.MainActivity
sleep 1
curl -s http://localhost:9099/startoverlay
```

Once `overlayService: true` in `/state`, DSM runs as a foreground service and answers API calls
while DroidDeck is in the foreground.

## 2. DroidDeck (`com.droiddeck.launcher`)

### Launching Nightshade from the launcher

```bash
$ADB shell monkey -p com.droiddeck.launcher -c android.intent.category.LAUNCHER 1
```

Check which activity is focused:

```bash
$ADB shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
```

- If `mCurrentFocus` is already `com.droiddeck.launcher/.SessionActivity`, the game container is
  running — skip straight to unpausing (`再開`).
- If `mCurrentFocus` is `com.droiddeck.launcher/.MainActivity`:
  - Left sidebar tabs:
    - **Steam** tab: `[74,117][181,183]` → `input tap 127 150`
    - **ゲーム** (Games) tab: `[78,293][176,359]` → `input tap 127 320`
  - Inside **ゲーム** (Nightshade selected):
    - **▶ 起動** (Launch) button: `[378,672][621,804]` → `input tap 500 738`
    - Wait ~4–5 s for `mCurrentFocus` to switch to `com.droiddeck.launcher/.SessionActivity`, and
      ~10 s total for the Nightshade title screen.

### Unpausing (`Steam 一時停止中` overlay)

Whenever DroidDeck loses focus (MediaProjection consent dialog, switching to `MainActivity`, etc.),
it pauses the container (`Nightshade.exe` shows state `t` in `top`) and displays `Steam 一時停止中`
with a blue **再開** button below center:

- **再開** (Resume) button: **`input tap 1600 1265`**
  - Do **not** tap screen center `(1600, 1068)` — that hits the `Steam 一時停止中` text label above
    the button.
- Verify the game is actually running:
  ```bash
  $ADB shell "top -b -n 1 | grep Nightshade.exe"
  ```
  State `S` or `R` (~70–85 % CPU) means running; state `t` means still paused.

## 3. Nightshade (`百花百狼`, `3200 × 2136` landscape)

- **Title screen bottom menu (`y ≈ 1810`)**:
  - `最初から` (New Game): `(480, 1810)`
  - `続きから` (Continue / Load): `(1120, 1810)`
  - `おまけ`: `(1740, 1810)`
  - `設定`: `(2325, 1810)`
  - `終了`: `(2880, 1810)`
- **Load screen (`ロード`)**:
  - Save Slot 1 (top-left): `(420, 560)`
  - `読み込みますか？` confirmation: **はい** `(1340, 975)`, **いいえ** `(1900, 975)`
- **In-game right-side wheel buttons (`x ≈ 3100`)**:
  - **クイックセーブ** (Quicksave): **`(3100, 1440)`** — saves immediately with **no confirmation dialog**.
  - **スキップ** (Skip): `(3100, 1590)`
  - **Auto**: `(3100, 1740)`
  - **クイックロード** (Quickload): **`(3100, 1890)`** — opens `クイックロードをしますか？`:
    - **はい** (Confirm Quickload): **`(1280, 1100)`**
    - **いいえ** (Cancel): **`(1840, 1100)`**
- **Advancing dialogue**:
  - Tap the **upper background** at **`(1600, 600)`**.
  - Never tap inside the dialogue box (`y = 1450..1920`) while Auto OCR is running: DSM's
    `ScreenTextLayerView` sits over the recognized text lines and intercepts taps to open the
    dictionary popup.
- **Recommended Auto OCR landscape region**:
  ```bash
  curl -s "http://localhost:9099/set_region?l=0.17&t=0.7&r=0.88&b=0.9&orientation=landscape"
  ```
  Covers up to 3 dialogue lines while excluding the character name plate on top and the right-side
  wheel buttons (`クイックセーブ` / `スキップ` / `Auto` / `クイックロード`).

### Recipe: deterministic multi-line benchmark replay with Quicksave / Quickload

```bash
# Save at the start of a dialogue sequence once:
$ADB shell input tap 3100 1440

# Replay the exact same N lines under any OCR configuration:
$ADB shell input tap 3100 1890 && sleep 0.8 && $ADB shell input tap 1280 1100
sleep 2.0
for i in $(seq 1 8); do
  $ADB shell input tap 1600 600
  sleep 2.5
done
curl -s http://localhost:9099/state | python3 -c "import json,sys; print(json.load(sys.stdin)['autoOcr'])"
```

## 4. MediaProjection Consent Flow (`3200 × 2136` landscape)

1. Put the target app (e.g. DroidDeck) in the foreground **first** so it appears as the first
   thumbnail in the system picker's recent-tasks row.
2. Trigger Auto OCR (or `/request_projection`):
   ```bash
   curl -s "http://localhost:9099/autoocr?on=1"
   sleep 2
   ```
3. **Step 1 dialog** (`com.android.systemui:id/screen_share_permission_dialog`):
   - Defaults to `1 個のアプリを共有` (Single app). (Mode spinner is at `(1750, 950)` if you ever need
     `画面全体を共有`.)
   - Tap **次へ** (`android:id/button1`, `[1616,1396][2043,1534]`): **`input tap 1830 1465`**
4. **Step 2 app picker** (`共有するアプリを選択`):
   - Tap the **first recent-task thumbnail** in the top row
     (`com.android.systemui:id/media_projection_recent_tasks_recycler`, `[830,442][1630,1086]`):
     **`input tap 1230 764`**
   - Do **not** blindly tap the lower icon grid (`android:id/chooser_row`): its app order changes
     between runs.
5. Tap **再開** in DroidDeck (`input tap 1600 1265`) and verify `/state` has `captureReady: true`
   and `autoOcr.running: true`. (Once `captureReady: true`, toggling `/autoocr?on=0` → `/autoocr?on=1`
   starts `OverlayService.startAutoOcr` in place without bringing `MainActivity` to the front.)

## 5. YouTube App (`com.google.android.youtube`, `3200 × 2136` landscape)

- **Avoid Picture-in-Picture covering MediaProjection consent**: on YouTube Pro / Premium, backgrounding
  an actively playing video puts YouTube into PiP (`mWindowingMode=pinned`, `Rect(1143, 659 - 3145, 1785)`),
  which floats *above* `MediaProjectionPermissionActivity` and intercepts taps AND removes YouTube from
  the recent-tasks row. Always **pause first** (`$ADB shell input keyevent 127`) before calling
  `/autoocr?on=1` or `/request_projection`.
- **Enter fullscreen**: in the tablet two-column layout (player at `[0, 88][2240, 1348]`), tap the
  bottom-right fullscreen icon at **`(2165, 1265)`**.
- **Hide player overlay controls**: tap **`(1600, 500)`** (controls stay visible when paused until
  dismissed).
- **Play / Pause**: `input keyevent 126` (`KEYCODE_MEDIA_PLAY`) / `input keyevent 127` (`KEYCODE_MEDIA_PAUSE`).
- **Deterministic rewind (`-10 s` per tap after the first)**:
  ```bash
  # Rewind 30 seconds (4 rapid taps on the left half of the fullscreen player):
  $ADB shell "input tap 600 1000; sleep 0.08; input tap 600 1000; sleep 0.08; input tap 600 1000; sleep 0.08; input tap 600 1000"
  ```
- **Fullscreen hardsub region** (`MH6JpXmv4zI`): `l=0.06, t=0.68, r=0.94, b=0.92` (`2816 × 513`).

## 6. Droid Sentence Miner App UI

- **Navigation**: `Home` · `Book` · `Texts` · `Settings`.
  - Width `< 600 dp` (phones, or tablet in `wm size 1080x2400`): bottom navigation bar at `y ≈ 2292`
    (`Home` `x ≈ 127`, `Book` `403`, `Texts` `677`, `Settings` `953`).
  - Width `≥ 600 dp` (tablet portrait/landscape, landscape phones): left `NavigationRail`; screen
    content sits in a centered `≤ 840 dp` column.
- **Settings sub-pages**: `Dictionaries` and `Anki` open inside `Settings` (`SettingsSubPage`) and
  are remembered when switching tabs; send `input keyevent BACK` to return to the main Settings list.
