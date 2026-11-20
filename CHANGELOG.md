# Changelog

User-visible changes per release. Format follows [Keep a Changelog](https://keepachangelog.com/);
versions are the `v*` tags that publish a signed APK on
[GitHub Releases](https://github.com/WilsonNet/droid-sentence-miner/releases).

## [Unreleased]

### Added
- **AI explanations.** An ✦ button in the lookup popup asks for a grammar breakdown of the
  sentence, focused on the word you tapped (its form, the patterns around it, a natural
  translation). Two ways: **AI app** opens Gemini with the prompt already typed (Claude, ChatGPT
  and others through the share sheet, prompt also copied) — free, nothing to set up; **API key**
  streams the answer into the popup from Google Gemini (free tier), Groq, Cerebras, Mistral,
  OpenRouter, OpenAI, Anthropic, DeepSeek, xAI, OpenCode Go, Ollama on your computer, or any
  OpenAI-, Anthropic- or Gemini-compatible endpoint. Keys are stored encrypted.
- **Editable prompts**: *Grammar*, *Translate* and *Word nuance* built in, add your own, with
  `{word}`, `{sentence}`, `{lemma}`, `{reading}`, `{definition}` and `{language}` placeholders.
  Edited built-ins can always go back with *Restore original*.
- **AI answers on cards**: an answer you opened goes after (or before) the glossary, or into its own
  field (*Grammar* / *AI Explanation*, or any field set to *AI explanation* in *Settings → Anki →
  Fields*). Mining never calls the AI by itself.
- **One switch to turn AI off** (*Settings → AI explanations*): no AI button anywhere, nothing sent.

## [0.10.0] — 2026-11-20

### Added
- **French.** Pick it as your study language: lookups of words and phrases (*pomme de terre*,
  *est-ce que*), elided words (*l'homme*, *qu'il*, *j'aime* — with ' or ’), conjugations
  (*mangeait → manger*, *suis → être*, *finissaient → finir*, *commençait → commencer*), plurals and
  agreement (*chevaux → cheval*, *heureuse → heureux*), IPA, the noun's gender on the card (un/une),
  word audio from the French Wiktionary's own recordings (Lingua Libre speakers, `Fr-…ogg`), and OCR
  clean-up for « guillemets », NBSP before punctuation, detached apostrophes (*l' homme*) and words
  hyphenated across lines (*dit- il* stays *dit-il*).
- **French dictionaries in one tap**: Wiktionary French → English (495,000 entries, with conjugated
  forms) and French IPA (recommended), Wiktionary French (monolingual, 77 MB) and its IPA, and small
  French → Portuguese / Spanish / German / Italian / Russian / Chinese / Japanese / Korean glossaries.
- **French pronominal verbs and ALL-CAPS text**: *s'appelle* opens *s'appeler* first (then
  *appeler*), and *m'appelle*/*t'appelles* also try the *s'…* headword; stem-changing verbs resolve
  (*lève → lever*, *répète → répéter*, *appelle → appeler*); unaccented capitals get their accents
  back (*ETE → été*, *ECOLE → école*, *ETAIT → être*).
- **French OCR**: the optional OCR pack (the English one: PaddleOCR, its recognizer reads every French
  letter, œ and « ») reads French subtitles with 2.1 % character / 2.9 % word errors vs the built-in
  reader's 3.6 % / 14.3 % (80 TEDx caption frames, Xiaomi Pad 7).

- **German: separable verbs and compounds** (*Settings → German lookups*, both on, each can be
  switched off): tapping *stehe* in "Ich stehe früh auf" also shows *aufstehen* (the particle must
  end the clause, so "Ich stehe auf dem Tisch" stays *stehen*); a compound no dictionary lists
  (*Fensterputzmittel*) shows its parts (*Fenster* + *Putzmittel*).

### Changed
- **Settings only show what your study language uses**: a lookups card for languages that have
  options (German), the ML Kit two-pass switch only while ML Kit reads your language, word-audio
  text naming your language's sources, the custom audio URL's `{reading}` only for Japanese, and
  the manga reader's on-device *Manga OCR* models (Japanese-only) hidden for other languages
  (a stored choice falls back to *Auto*).
- **Composable lookup engine**: languages are built from a shared toolkit (`lang/kit`: scanner,
  suffix-rule tables, part-of-speech masks, case forms, OCR text helpers) and pick the engine's
  optional steps (`LookupFeature`: form-of entries, homograph separation, grammar-noise skip,
  kanji info). English, German and French now share one scanner and rule engine; lookups are
  unchanged (re-checked on device in all four languages).
- **Inflected-form entries open their lemma** (French, German, English Wiktionary dictionaries): an
  entry that only says "imperfect of *manger*" is replaced by *manger*'s own entry with that
  inflection, like Yomitan — so irregular forms (*suis → être*, *ging → gehen*) get the real
  definition, and the card is for the dictionary form. Chains are followed two levels with every
  step shown (*allées* → plural feminine of *allé* → past participle of *aller*).

### Fixed
- **Sharing an unknown word** (text selection → *Droid Sentence Miner* → *Look up*) looked up its
  first 12 characters, a Japanese fallback; spaced languages now look up the whole first word.
- **Homographs with the same headword were merged** (English, German, French): Wiktionary
  dictionaries number every entry 0, so *suis* (être / suivre) showed only one of them.
- **IPA with combining marks** (French nasals *ɔ̃*, *ɑ̃*) drew the tilde beside the letter on
  HyperOS: the popup's IPA line now uses a serif font that has both.

## [0.9.1] — 2026-11-16

### Fixed
- **No more white halos around lines with AI upscaling**: the AI page was shrunk to the screen with
  a filter that could ring, and the network brightened pixels beside dark strokes. Both are clamped
  now (sharpness is kept: SSIM 0.819 vs 0.712 for a plain stretch on the test pages).

## [0.9.0] — 2026-11-16

### Added
- **On-device manga OCR** (*Reader settings → Manga OCR engine → Download manga OCR models*, 34 MB):
  PP-OCRv6_manga (a PaddleOCR line detector + recognizer trained on manga, vertical and horizontal
  text) plus a speech-bubble detector that groups each bubble's columns. Offline, ~0.4–1 s per page
  on a Xiaomi Pad 7, and on a hand-checked set of 99 bubbles (One Piece, Berserk) it reads 84–88
  bubbles exactly — Google Lens read 65, ML Kit 21, a desktop mokuro run 89. *Auto* uses it once
  downloaded; taps land on the right character (per-glyph boxes).
- **Crisper manga pages** (*Reader settings → Image*, on by default): a page filter that turns grey
  or yellowed paper white and ink solid black, resizes the page to your screen with a sharp Lanczos
  filter instead of a soft stretch, and sharpens line art with AMD's contrast-adaptive sharpening
  (CAS). *Off* / *Soft* / *Crisp* / *Strong*. Black-and-white pages are kept as one grey channel, a
  quarter of the memory. ~0.15 s per page on a Xiaomi Pad 7, prepared ahead while you read.
- **AI upscaling** (*Reader settings → Image*, on by default): low-resolution pages (drawn at 1.2×
  their size or more) go through Anime4K's Restore + 2× CNNs (MIT, 74 KB, bundled — no download)
  before the filter. On blurred, half-size test pages it recovers twice the edge sharpness of the
  old stretch (SSIM 0.82 vs 0.71); about 0.8 s for a black-and-white page on the Pad 7, prepared
  for the next pages so turning a page is instant. The page shows the quick filter first if the AI
  copy isn't ready. OCR always reads the original page.

### Changed
- **Google Lens pages are grouped by the bubble detector** when the models are downloaded: 71 of 99
  bubbles exact instead of 65 (thin kana columns next to kanji were dropped as furigana, and
  bubbles split in two).
- *Prepare OCR for all pages* redoes pages read by another engine than the selected one (mokuro
  and hand-edited pages are kept), so switching engines and preparing the volume upgrades it.
  A 227-page volume takes under 2 minutes on-device.
- The reader's engine choice is a list (*Auto*, *Manga OCR*, *Google Lens*, *ML Kit*) with one line
  on each; the page label shows *Manga OCR* / *Lens* / *ML Kit* / *Mokuro*.
- **Tidier on phones** (checked at Galaxy S24 size, 360 dp wide):
  - Reader top bar: back, title, text-overlay toggle and a ⋮ menu (*Re-read this page*, *Edit text
    boxes*, *Reader settings*), so the volume title and page label fit on one line each.
  - Reader settings: segmented buttons for layout, page order, text boxes and tap zones instead of
    wrapping chips; whole rows toggle their switch; grouped as Layout, Text & taps, OCR, Anki &
    loading.
  - Manga shelf shows two volumes per row, the import buttons stay on one line, and the cover badge
    only appears for *Mokuro* or a running full-volume OCR.
  - The Texts tab has a title like the other tabs; its *Auto OCR text areas* button is visible
    again; Home's test buttons no longer wrap; settings switches keep a gap from their text and the
    auto OCR interval slider lost its row of tick dots.
  - The colour theme sets every Material role, so buttons, chips, cards and sheets are green-grey
    instead of Material's default lilac.

## [0.8.0] — 2026-11-16

### Added
- **Manga `.cbz` reader** (inspired by Manatan and Mokuro Reader): read `.cbz` manga volumes from
  local storage (*From files*) or *Google Drive* (*Browse my Drive* or public Drive links/folders),
  displayed on a cover shelf with reading progress and `Mokuro` / `Auto OCR` badges.
- **`.mokuro` support, Manatan-grade Lens OCR & offline fallback:** attaches paired `.mokuro` JSON files
  (side-by-side at import, embedded inside the `.cbz`, or attached later from the shelf menu) for
  instant Manatan/Mokuro-style text boxes. Without `.mokuro`, *Auto* mode uses Google Lens (the same
  backend as Manatan) with automatic offline fallback to upscaled ML Kit (Japanese) or the downloaded
  PaddleOCR pack (English/German), filtered and clustered into vertical speech bubbles
  (`MangaOcrGrouping`: page-number/noise/furigana removal and Union-Find column merging). Supports
  per-reader *Auto / Google Lens / On-device* engine switching, background *Prepare OCR for all pages*
  with live shelf progress (`OCR X/Y`), Single-page, Double-page spread (with cover offset), and
  Vertical-scroll reading modes, RTL/LTR direction, Transparent / Readable (Light) / Readable (Dark) /
  Hidden overlays, tap/drag lookup, and inline text-box editing (edit or delete misreads).
- **Interactive panel cropper on *+ Anki*:** when mining from the manga reader (`Crop panel on Add
  to Anki`, on by default), a full-screen cropper opens with *Panel*, *Bubble*, and *Full page*
  presets and corner/edge handles or outside redraw so your Anki card gets only the exact panel or
  speech bubble you want instead of the whole page.

### Changed
- **Home screen floating overlay controls & getting started instructions:** Replaced the confusing "Scan now" button on the home screen with in-app floating bubble mode selection (*Quick lookup* vs *Scan region*). When the overlay is running, the button now reads *Stop floating bubble*. Added prominent tips clarifying that the user should switch to their game or app before tapping the bubble, and that holding (long-pressing) the floating bubble opens the full menu (Auto OCR, text areas, live text window, and audio options).

### Fixed
- **Manga reader accidental page turns on word lookup:** consuming pointer-up events on text-box taps, expanding hit-slop/nearest-glyph bubble fallback, and narrowing tap zones prevent tapping a word inside a speech bubble from turning the page.

## [0.7.0] — 2026-11-09

### Added
- **German.** Pick it as your study language: lookups of words and phrases (hyphenated compounds
  like *E-Mail*, capitalized nouns, ALL-CAPS game text), inflections (*ging → gehen*, *gemacht →
  machen*, *aufgemacht → aufmachen*, *anzufangen → anfangen*, *schnelleren → schnell*), IPA, word
  audio from Wiktionary and Lingua Libre recordings, and OCR clean-up for „quotes“ and words
  hyphenated across lines.
- **German dictionaries in one tap**: Wiktionary German → English (600,000 entries, with inflected
  forms) and German IPA (recommended), Wiktionary German (monolingual) and its IPA, and small
  German → Portuguese / Spanish / French / Italian / Russian / Chinese / Japanese / Korean
  glossaries. Which is which: [docs/dictionaries.md](docs/dictionaries.md).
- **Anki per language.** Deck, note type and tags are kept per study language
  (Japanese keeps your current settings). English and German get their own note types and decks
  (*Droid Sentence Miner (English)* in *Droid Sentence Miner::English*, same for German): an
  *IPA* field instead of Reading, no pitch graph, and for German a *Gender* field with the noun's
  article (der/die/das).
- **Leave contents out with *Nothing*.** In *Settings → Anki → Fields*, a field set to *Nothing*
  now also means that content isn't fetched at all (no word audio download, no sentence clip, no
  screenshot) — handy for simple word + sentence + glossary cards.
- **What's new**: after an update, a one-time dialog shows what changed since the version you had.

### Fixed
- The English OCR models download (*Settings → Language*) failed with "HTTP 404": the pack was never
  published. It is now, and German uses the same pack.
- An Anki note type with a *Sentence Audio* field got the sentence clip even with sentence audio
  turned off.
- Small one-tap dictionaries show "<1 MB" instead of "~0 MB".
- Word audio prefers the recording spelled exactly like the word (*De-Haus* before *De-haus*).

## [0.6.0] — 2026-11-08

### Added
- **Study language: Japanese or English.** A first-run screen asks what you are learning
  (Japanese stays the main focus and is preselected); switch any time in *Settings → Language*.
  Only the chosen language downloads anything: its dictionaries and, optionally, its OCR models.
  Dictionaries of the other language stay installed but are hidden and unused.
- **English support** (requested in #1): lookups of whole words and phrases (*look up*,
  *ice cream*, up to five words), inflections (*running → run*, *happiest → happy*, *wolves →
  wolf*, *looked up → look up*), case folding, IPA pronunciation in the popup and as the card's
  reading, and word audio from Wiktionary and Lingua Libre recordings on Wikimedia Commons (each
  language only queries its own sources: Japanese cards still use JapanesePod101 and Jisho).
- **English dictionaries in one tap**: Wiktionary English (wty-en-en, 1.6 M entries), Wiktionary
  English IPA, and small English → Portuguese / Spanish / German / French / Italian / Russian /
  Chinese / Japanese / Korean glossaries (all Wiktionary via kaikki/wty, updatable).
- **English OCR pack** (*Settings → Language*, 23 MB, optional): PaddleOCR PP-OCRv5 detector +
  PP-OCRv6 recognizer. On game/subtitle-style English it makes about a third of the word errors of
  the built-in reader (WER 3.5 % vs 12.2 %) and runs 2.5× faster on a Xiaomi Pad 7.

### Changed
- OCR clean-up, line joining, the Book tab and the Home/Dictionaries pages follow the study
  language (English keeps spaces and Latin punctuation; Japanese behaves exactly as before).

## [0.5.1] — 2026-10-31

### Changed
- *Settings → Cards → Glossary* lists the default, *First dictionary only*, first.

## [0.5.0] — 2026-10-31

### Added
- **In-app updates.** When a newer release is out on GitHub, Home shows *Update available* with an
  *Update* button: it downloads the APK and opens Android's installer (allow installs from Droid
  Sentence Miner once). Dictionaries, settings and history are kept. *Settings → About* has
  *Check for updates* and a switch to turn the automatic check off.
- **Edit a card before adding it.** Next to *+ Anki* in the lookup popup (and on each headword's
  button) a ✎ opens the card editor: trim the sentence (type, or tap one of its sentences to keep
  just that one — handy for long visual-novel lines) and the sentence audio (waveform, play, one two-handle slider), or drop the audio.
  Works over games and videos too; the keyboard opens there for the sentence.
- **Pick the dictionary for the card in the popup.** Each dictionary's name in the lookup popup is a
  button: the one going on the card shows *✓ On card*, the others *Use on card*. Tap one to put only
  its definitions on that word's card (per headword); tap it again to go back to the setting.
- **Pixiv Light** (MarvNC's Pixiv encyclopedia summaries: anime, games, characters, slang) in the
  one-tap dictionaries. Installs fetch the latest release, and it updates like Jitendex.

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

[Unreleased]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.9.1...v0.10.0
[0.9.1]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/WilsonNet/droid-sentence-miner/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/WilsonNet/droid-sentence-miner/releases/tag/v0.1.0
