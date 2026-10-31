# Changelog

User-visible changes per release. Format follows [Keep a Changelog](https://keepachangelog.com/);
versions are the `v*` tags that publish a signed APK on
[GitHub Releases](https://github.com/WilsonNet/droid-sentence-miner/releases).

## [Unreleased]

### Added
- **Edit a card before adding it.** Next to *+ Anki* in the lookup popup (and on each headword's
  button) a ✎ opens the card editor: trim the sentence (type, or tap one of its sentences to keep
  just that one — handy for long visual-novel lines) and the sentence audio (waveform, play, one two-handle slider), or drop the audio.
  Works over games and videos too; the keyboard opens there for the sentence.
- **Pick the dictionary for the card in the popup.** Each dictionary's name in the lookup popup is a
  button: the one going on the card shows *✓ On card*, the others *Use on card*. Tap one to put only
  its definitions on that word's card (per headword); tap it again to go back to the setting.

### Changed
- **Cards no longer repeat the dictionary name over every definition.** The glossary setting is now
  three-way, like Yomitan's markers: *First dictionary only* (`{glossary-first}`, the new default:
  the dictionary at the top of *Settings → Dictionaries*), *All dictionaries, unnamed*
  (`{glossary-no-dictionary}`) and *All dictionaries, named* (`{glossary}`, the old behavior).
- The audio editor uses one range slider instead of two, with the play button and kept range under
  the waveform.
- The card editor starts with its audio handles on the voice (a line left on screen while you read
  runs on as room noise or, through the microphone, as the game's music: 17.8 s clips came out as
  ~4.5 s), and turns sentence audio off for a silent clip, such as an unvoiced heroine line.
- Mining no longer waits for word audio after the sentence clip: both are fetched at once, and
  word-audio sources get 8 s in total (a word with no audio anywhere held the card for 15+ s).

- **Pitch accent in the lookup popup**: the same graphs the card gets, under each headword.
- **Share sheet**: share text from any app to Droid Sentence Miner to look it up (a short shared
  sentence is scanned for words, not looked up whole), or share a page photo to read it in Book mode.

### Fixed
- Tapping a noun followed by で selected the particle too and looked it up as a "te-form"
  (学校で, 本当ですか, 元気でいて, 象徴ではある): stems can no longer match nouns or entries of
  monolingual dictionaries; inflected verbs and adjectives still do.
- Sentence audio added through AnkiDroid was stored as `….m4a_<n>.mp3`; it is now `…_<n>.m4a`.
- Starting auto OCR over a landscape game with the tablet held upright: the screen-sharing consent
  closed by itself within a second (the dialog rotated the screen), so capture never started.
- Every mined card left its sentence clip file in the app's cache; they are deleted now (and old
  ones on the next card).
- The card editor dims the game behind it, and its audio suggestion no longer stretches to a sound
  long after the line.
- *Trim silence* cut quiet microphone clips down to their loudest syllables (an 8.6 s line kept
  3.8 s): clips are now normalized before they are trimmed.
- A silent clip's waveform was drawn full height, like a loud one.
- In the card editor, a line the game wraps over two rows was split into two "sentences" and
  edited cards kept the wrap as a line break.

## [0.4.0] — 2026-10-29

### Added
- **Install dictionaries from files or Google Drive.** *From files* picks several `.zip`s at once.
  *Google Drive* opens your own Drive through Android's file picker (sign-in stays in the Drive
  app), or takes a shared file/folder link: a public folder is listed, you tick the zips and they
  install one after another. Recent folders are remembered.
- **Dictionary images like Yomitan**: sizes, aspect ratio, em units, monochrome/pixelated,
  collapsed images and captions (Pixiv icons, 広辞苑 photos, 三省堂 symbols). Images load lazily
  from the zip, big ones are downscaled, and mined cards get them as Anki media.
- JMnedict (names) in the recommended dictionaries; drag-and-drop dictionary priority with
  `#1`, `#2` badges. Name dictionaries start at the bottom.
- Project website with docs (`site/`, GitHub Pages).

### Changed
- **Much faster lookups with many dictionaries**: a tap on a new line is 2–3× faster and a second
  tap on the same line 20–40× faster (8 dictionaries, 2.1 M rows; numbers in
  [docs/validation.md](docs/validation.md)).
- Imports run on an app-wide queue: leaving the page or rotating no longer cancels them, and
  recommended installs and updates share the queue. Reading a picked file shows its progress.
- Definitions have more padding, and each dictionary's section is set off by a divider.
- The Dictionaries page scrolls as one list.

### Fixed
- Big monolingual dictionaries (広辞苑, 大辞林) crashed the app while importing (out of memory):
  banks are now streamed.
- A cancelled import or update could leave a dictionary with 0 terms (seen with JMnedict).
- Leftover zips from interrupted imports and downloads are cleaned up.
- Long lines no longer risk exceeding SQLite's 999-argument limit on Android 10 and older.

## [0.3.0] — 2026-10-26

### Added
- Automatic update checks and one-tap updates for Jitendex and any Yomitan dictionary with an
  `indexUrl`, keeping priority and enabled state.
- Yomitan structured-content data attributes, tag colors and Jitendex CSS in the popup and on cards.
- Calibrated speaker-correction EQ for the microphone fallback (*Calibrate* in Settings).
- Max height for card pictures (default 462 px, aspect ratio kept).

### Changed
- meikiocr uses about half the CPU per read; static screens no longer cost CPU or memory churn.

### Fixed
- Screen-sharing consent no longer pulls the app in front of your game.

## [0.2.0] — 2026-10-24

### Added
- **Quick lookup** bubble mode: box a word, the definition pops up next to it.
- Screenshots through an accessibility service, so quick lookups don't ask for screen sharing
  every session.
- One *+ Anki* button per headword, like Yomitan.
- *Settings → Anki → Fields* to map note type fields; deck and note type dropdowns.
- Live text window: tap a word for the in-place popup; the Live button starts/stops auto OCR.
- Mic fallback compressor; louder normalized clips (−14 dBFS with a soft limiter).

### Changed
- meikiocr 2–2.7× faster with shorter detector and any-width recognizer cuts.
- Debug builds are labelled "Droid Sentence Miner (Dev)".

### Fixed
- The bubble stays on screen across rotations; capture reads the screen size when it starts.

## [0.1.0] — 2026-10-22

First release.

- Popup OCR with Yomitan dictionaries and Anki mining (AnkiDroid API or AnkiConnect).
- Auto OCR with text areas, consensus text and the *Texts* log; replay-buffer sentence audio.
- Game-trained meikiocr as the default engine (ML Kit as fallback).
- Screen dictionary: tap the real on-screen text in any app.
- Live text window with history, resize, compact and fullscreen modes.
- Book mode for photos of printed pages and manga, with an overlay reader.
- Word audio and pitch accent graphs like Yomitan; one-click Kanjium pitch dictionary.
- Microphone fallback for apps that block audio capture; typewriter-text handling for visual novels.
- Tablet/landscape layout with a navigation rail.

[Unreleased]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/WilsonNet/droid-sentence-miner/releases/tag/v0.1.0
