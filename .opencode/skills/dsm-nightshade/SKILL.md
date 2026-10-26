---
name: dsm-nightshade
description: Play the Nightshade visual novel (百花百狼, Steam via DroidDeck) on the Xiaomi Pad 7 over adb — launch, resume, load a save, advance voiced lines, quicksave/quickload, open the Steam menu, quit — and run Droid Sentence Miner's auto OCR + microphone fallback on it. Use whenever a task needs real game text or voiced game audio (OCR benchmarks, mic fallback/audio tests, mining demos), or when asked to start or close the game.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Playing Nightshade (百花百狼) for tests

Nightshade is the project's visual-novel test bed: Japanese dialogue in a fixed box, every line
voiced, running as a Windows Steam game inside DroidDeck (`com.droiddeck.launcher`, Proton +
gamescope at 1280×720, scaled to the tablet). DroidDeck blocks audio capture (targetSdk 28), so
its voices reach DSM only through the **microphone fallback** — the reason it is the audio test bed.

Coordinates: Xiaomi Pad 7 `c2bd6564`, landscape `3200 × 2136` (`wm size` reports 2136x3200).
Never launch an emulator. Locked/disconnected tablet → `notify-send -u critical` and wait.
DroidDeck launch, the `Steam 一時停止中` overlay and the capture consent dialog are in
`dsm-navigation` §2 and §4 — the summary below is enough for the common path.

```bash
export ANDROID_SERIAL=c2bd6564 PATH=$HOME/Android/Sdk/platform-tools:$PATH
shot() { adb exec-out screencap -p > /tmp/g.png; }   # then look at it (downscale ~4×)
```

## 1. Get into the game

1. `adb shell monkey -p com.droiddeck.launcher -c android.intent.category.LAUNCHER 1`
2. `adb shell "dumpsys window | grep mCurrentFocus"`:
   - `…/.SessionActivity` → container already running (Nightshade.exe stays alive for days).
   - `…/.MainActivity` → ゲーム tab `(127, 320)`, ▶ 起動 `(500, 738)`, ~10 s to the title.
3. Paused (`Steam 一時停止中`, after any focus loss: consent dialog, DSM activity) → **再開 `(1600, 1265)`**.
   Check: `adb shell "top -b -n 1 | grep Nightshade.exe"` — `R`/`S` at 55–100 % CPU = running, `t` = paused.
4. If the **Steam game page** shows instead (big Nightshade art, `▶ 再開` button, sometimes a
   `匿名のフレームレートデータ` はい/いいえ prompt): tap **▶ 再開 `(440, 1200)`**. Ignore the prompt.
   (Seen once right after resuming from the consent dialog.) Steam Big Picture's library →
   tap the Nightshade tile `(800, 820)` to reach that page.

## 2. In-game controls

| Action | Tap |
| --- | --- |
| Advance line | upper background **`(1600, 600)`** — never the dialogue box `y 1450–1920` while DSM's screen layer is on (it eats taps for lookups) |
| クイックセーブ (no confirm) | `(3100, 1440)` |
| スキップ / Auto | `(3100, 1590)` / `(3100, 1740)` |
| クイックロード | `(3100, 1890)` → はい `(1280, 1100)` / いいえ `(1840, 1100)` |
| Title menu (y 1810) | 最初から `480` · 続きから `1120` · おまけ `1740` · 設定 `2325` · 終了 `2880` |
| Load screen | slot 1 `(420, 560)` → 読み込みますか？ はい `(1340, 975)` / いいえ `(1900, 975)` |

A voiced line takes 2–7 s; wait **7 s** per advance when you need its audio. Quicksave overwrites
the user's quick slot — only use it when a deterministic replay matters (OCR benchmarks), and say so.
Replay recipe (same N lines every time):
```bash
adb shell input tap 3100 1890; sleep 0.8; adb shell input tap 1280 1100; sleep 2
for i in $(seq 1 8); do adb shell input tap 1600 600; sleep 2.5; done
```

**Taps stop advancing** (same line forever, `Nightshade.exe` ~14 % CPU = idle, no pause overlay):
seen after reinstalling DSM + a new capture session; Steam menu → ゲームを再開 did not fix it. The
DroidDeck session then sits on a separate `CLONE` display (`dumpsys input`, displayId ≠ 0). Not
solved yet — re-launching DroidDeck is the next thing to try; record what works here.

## 3. DroidDeck overlay buttons (always on top of the game)

- **Steam** (bottom-left) `(120, 2012)` — needs a *held* press: `input swipe 120 2012 120 2012 150`
  (a plain tap does nothing). Opens the Steam menu: **ゲームを再開 `(1020, 756)`**,
  **ゲームを終了 `(1020, 1684)`** (quits Nightshade), ホーム/ライブラリ on the left.
- **…** (bottom-right) `(3078, 2012)` — Steam quick settings (volume, Wi-Fi…); tap again to close.
- Keyboard (bottom-centre) `(1600, 2012)`.

## 4. DSM on Nightshade (auto OCR + mic fallback)

```bash
adb forward tcp:9099 tcp:9099
adb shell am start -n io.github.wilsonnet.droidsentenceminer.debug/io.github.wilsonnet.droidsentenceminer.MainActivity
sleep 2; curl -s localhost:9099/startoverlay
curl -s "localhost:9099/set_region?l=0.17&t=0.7&r=0.88&b=0.9&orientation=landscape"  # dialogue box only
curl -s "localhost:9099/mic_fallback?on=1"
adb shell monkey -p com.droiddeck.launcher -c android.intent.category.LAUNCHER 1; sleep 3
curl -s "localhost:9099/autoocr?on=1"; sleep 2.5            # consent: 次へ, then the DroidDeck thumbnail
adb shell input tap 1830 1465; sleep 2; adb shell input tap 1230 764; sleep 3
adb shell input tap 1600 1265                                # 再開
curl -s localhost:9099/state | python3 -m json.tool | grep -A5 audioBuffer   # "source": "mic"
```

`audioBuffer.source` turns `mic` ~4 s after device audio goes silent with the game playing
(`SILENT_SWITCH_MS`). Then advance lines and pull clips:
```bash
curl -s "localhost:9099/lines?limit=6"
curl -s "localhost:9099/clip.wav?id=<id>" -o line.wav
```
Measured (Pad 7, media volume 90/160): clips −21…−31 dBFS RMS after normalization; the mic
thread costs 1.9 % of one core with the FX chain, 0.6 % without. Tuning the mic chain itself:
`dsm-audio`. Mining test cards: only into the deck **DSM** on this tablet.

## 5. Closing up

```bash
curl -s "localhost:9099/autoocr?on=0"; curl -s localhost:9099/stopoverlay
adb shell input swipe 120 2012 120 2012 150; sleep 2      # Steam menu
adb shell input tap 1020 1684                              # ゲームを終了
adb shell input keyevent 26                                # screen off / lock (only if asked)
```
