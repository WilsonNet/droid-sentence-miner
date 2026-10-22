---
name: dsm-promo-media
description: Capture promotional media for Droid Sentence Miner — README screenshots (phone-style portrait) and demo videos/GIFs of real mining flows (visual novel, YouTube) — on a real device over adb. Use when asked for new screenshots, demo videos, GIFs, or to refresh the README's images.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Promotional screenshots and videos

What the README shows today (keep it small):

- `docs/videos/*-mining.{mp4,gif}` — two demo clips in the README *Demo* table: mining a word
  from a visual novel (Nightshade via DroidDeck) and from a YouTube hardsub. The GIF is inline,
  it links to the MP4 (GitHub only plays videos uploaded as attachments, not repo files).
- `docs/screenshots/*.jpg` — **three** phone-portrait app screens under the title: book reader
  lookup, Texts with an expanded line, sentence-audio settings. Pick the 3–4 that best show
  what changed; don't add a screenshot per screen. In-game flows belong in the videos.

Device: the Xiaomi Pad 7 (adb `c2bd6564`, natural orientation portrait 2136×3200, 440 dpi).
Use the SDK `adb` (see AGENTS.md). Set up the debug API first (`dsm-debug-api` skill).

## Phone-style screenshots on the tablet

The README should look like a phone. Fake a phone display, shoot, then **always reset**:

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb -s c2bd6564"
$ADB shell wm size; $ADB shell wm density            # note the originals
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 0       # portrait
$ADB shell wm size 1080x2400 && $ADB shell wm density 420   # ~411 dp wide → bottom tabs
# Clean status bar (SystemUI demo mode)
$ADB shell settings put global sysui_demo_allowed 1
for c in "enter" "clock -e hhmm 1200" "battery -e level 100 -e plugged false" "notifications -e visible false"; do
  $ADB shell am broadcast -a com.android.systemui.demo -e command $c; done
curl -s localhost:9099/stopoverlay      # no bubble / live window over app screens
# ... navigate, then: $ADB exec-out screencap -p > shot.png
# Reset (do not skip)
$ADB shell wm size reset; $ADB shell wm density reset
$ADB shell settings put system accelerometer_rotation 1
$ADB shell am broadcast -a com.android.systemui.demo -e command exit
$ADB shell settings put global sysui_demo_allowed 0
```

Phone layout taps (1080×2400): bottom tabs at y≈2292 — Home x≈127, Book 403, Texts 677,
Settings 953. Settings remembers the last sub-page (Anki/Dictionaries); Back returns to the list.

Content tips:
- Book: `curl "localhost:9099/book?file=/data/user/0/io.github.wilsonnet.droidsentenceminer.debug/files/bj12.jpg"`
  loads a free-reuse *Black Jack ni Yoroshiku* page (copy one in with `run-as` if missing),
  then the Book tab → *Read on the photo* → tap a word in a bubble.
- Texts: delete junk lines first (tap a line → trash) — test taps often log a launcher/menu.
  Expand a mined line so its screenshot and play/edit controls show.
- Save as JPEG: `ffmpeg -i shot.png -q:v 3 docs/screenshots/<name>.jpg` (~200–300 KB each).
  README: one `<p align="center">` row, `width="30%"` per image for three (24% for four).

## Demo videos

`screenrecord` has no audio and stops on static screens — script the taps so something moves.

```bash
$ADB shell screenrecord --size 1600x1068 --bit-rate 8000000 --time-limit 30 /sdcard/Movies/demo.mp4 &
sleep 2.5; $ADB shell input tap <word x> <word y>     # from /state screenText bounds × screen px
sleep 3.5; $ADB shell input tap <+Anki x> <+Anki y>   # find it with a dry run screenshot first
# poll until the card exists, so the clip ends on "✓ In Anki":
curl -s "localhost:9099/anki_notes?word=<urlencoded>"
```

- **Dry run first**: tap the word, screenshot, read the popup's *+ Anki* / ✕ positions, close it,
  then record. Popup position depends on the word's position.
- **Pick a word that is not in Anki yet** (`/anki_notes?word=` returns `notes: []`), otherwise
  the button shows ✓ In Anki and nothing gets added. Tell the user which cards you added — they
  land in their real deck.
- Mining takes ~11 s (word audio + AAC encode); record ≥25 s.
- Glyph tap point: `x = (left + (i + 0.5) / glyphs × (right − left)) × width`,
  `y = (top + bottom) / 2 × height` from `/state` → `screenText.lines[].bounds`.
- Trim and compress, then make the GIF preview:

```bash
ffmpeg -ss 1.5 -to 20 -i demo.mp4 -c:v libx264 -crf 26 -preset slow -an -movflags +faststart \
  -vf scale=1280:-2 docs/videos/<name>-mining.mp4
ffmpeg -i docs/videos/<name>-mining.mp4 -vf "fps=8,scale=720:-1:flags=lanczos,split[a][b];\
[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" docs/videos/<name>-mining.gif
```

Check the result with a contact sheet before committing:
`ffmpeg -i x.mp4 -vf "fps=1/3,scale=600:-1,tile=5x2" sheet.png`.

## Scene setup gotchas

- **Capture consent picker**: the icon grid order changes between runs (tapping the 3rd icon once
  picked YouTube instead of DroidDeck → no frames, `reads: 0`). Tap the target's **thumbnail**
  in the top row; bring the target app to the front right before so it is the first thumbnail.
  `/request_projection` does nothing while a projection is active — `/stopoverlay` →
  `/startoverlay` → `/autoocr?on=1`.
- **DroidDeck/Nightshade** pauses whenever it loses focus (consent dialog): tap *再開* at the
  screen center. Tap the background (not the text box) to advance — the screen-dictionary layer
  sits over the text. Landscape text area for it: `set_region?l=0.17&t=0.7&r=0.88&b=0.9`
  (excludes the side buttons).
- **YouTube**: landscape area `l=0.03&t=0.68&r=0.97&b=0.93` catches two-line hardsubs. Pause on
  the subtitle, wait ~5 s for the player controls to fade, then tap the word. Double-tap the left
  half to rewind 10 s so the line is logged with its audio.
- Text areas are per orientation, not per app: restore the one the user needs when done.
