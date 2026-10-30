# Usage

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

Step by step from a fresh install. The website has the same flow with screenshots: [docs](https://wilsonnet.github.io/droid-sentence-miner/docs/getting-started/installation/).

## Requirements

- Android 8.0 (API 26) or newer. Audio capture requires Android 10+; screen sharing follows the
  Android 14/15 consent flow (app picker + foreground service type).
- [AnkiDroid](https://github.com/ankidroid/Anki-Android) (2.16+) and/or desktop Anki with
  [AnkiConnect](https://foosoft.net/projects/anki-connect/).

## Setup and daily use

1. **Dictionaries** — open *Settings → Dictionaries* and install **Jitendex** and **Kanjium pitch accents**
   with one tap each (or import any Yomitan `.zip` with *From files* or *Google Drive*, e.g. a
   monolingual dictionary; drag it first if you want only it on cards). In *Settings → OCR*, tap *Download meikiocr models* (31 MB).
2. **Anki** — in *Settings → Anki* pick a backend:
   - *AnkiDroid*: install AnkiDroid, grant the API permission from that page, and enable
     *Settings → Advanced → AnkiDroid API* inside AnkiDroid.
   - *AnkiConnect*: start Anki with the AnkiConnect add-on and enter the URL
     (e.g. `http://192.168.1.10:8765`).
   Pick a deck and note type (a "Droid Sentence Miner" note type is created automatically).
3. **Quick lookup** — tap the bubble, drag a box over a word, lift: the definition pops up next
   to it with *+ Anki* (sentence = the boxed text, audio from the replay buffer). Tap another word
   in the box to look that one up; tap anywhere else to close. A tap without a drag cancels.
   Turn on *Accessibility → Installed apps → Quick lookup screenshots* once (Android 11+; the
   first bubble tap opens that page) and quick lookups and one-off scans never ask for screen
   sharing again — the service only takes a screenshot when you box a word and reads no app
   content. If the switch is greyed out (APK installed from a browser/file manager, Android 13+):
   *App info → ⋮ → Allow restricted settings*. Auto OCR still asks for screen sharing each
   session: Android has no other way to record the playback audio for sentence cards.
4. **Auto OCR (recommended for games/video)** — open the game, long-press the bubble → *Start
   auto OCR* (or set *Bubble tap* to *Scan region* and tap the bubble → *Live*); *Live* uses the box you drew as the text area when this orientation has none, and
   turns into *Stop live* while auto OCR runs. In the
   share dialog choose *A single app* → your game. If no text area exists for the current
   orientation the area editor opens: drag boxes over the dialogue/subtitle area (drag to move,
   corners to resize, × to delete; drag the toolbar by ⠿ out of the way) and *Save*. To change
   them later (e.g. a different video), tap ✎ in the *Live text* window or long-press the bubble →
   *Auto OCR text areas*. If the screen capture ends (the captured app was closed, consent
   revoked), the bubble menu offers *Restart screen capture*. New lines
   appear in the floating *Live text* window — tap a word to look it up in place and mine it with
   the line's audio and screenshot (hold a line for the full panel). Everything is also on the *Texts* tab.
   While it runs, just tap a word in the real subtitle on screen to look it up (the video pauses
   until you close the definition). Toggles: *Settings → Tap text on screen to look it up* and
   *Pause the video while looking up*.
5. **Manual scan (one-off)** — long-press the bubble → *Scan once: pick a region* (or tap it in
   *Scan region* mode), adjust
   the capture box and hit *Scan region*. This box is separate from the auto OCR text areas. Tap a
   highlighted word for definitions, then *Anki*. Manual scans are logged on *Texts* too.
6. **Audio** — on by default; grant the microphone permission when asked (Android requires it for
   playback capture). *Settings* has the buffer length, start/end offsets, silence trimming and
   volume normalization. Apps that opt out of capture (DroidDeck, Netflix) record silence unless
   you enable *Microphone fallback* (Settings or the bubble menu).
7. **Books** — open the *Book* tab, *Take photo* of the page (flat, well lit, filling the frame),
   tap the paragraph on the photo, then tap a word in its text to look it up and *Anki* it
   (hold + drag to pick the exact characters yourself; tap words inside the definition to dig deeper).
   After a photo the page opens in the overlay reader: tap the printed text itself to look it up,
   pinch to zoom; ✕ or Back returns to the paragraph view (*Read on the photo* reopens it).
   Arrows step through paragraphs; ✎ corrects the OCR text.
8. **Test drive** — the Home tab has a built-in sample (a public-domain Aozora Bunko passage) and
   an image picker to test OCR + lookup without a game.
