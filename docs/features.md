# Features

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

Everything the app does, in detail. The short version is in the [README](../README.md).

- **Auto OCR mode** — draw boxes over the game's text areas once (dialogue box, name box,
  subtitles; kept separately for portrait and landscape) and keep playing. The areas are read
  every ~400 ms; each new line is logged with the moment it appeared, the moment it went away and
  a screenshot. Repeated reads must agree before a line counts, and the stored text is the
  consensus of all reads of that line, so fades, typewriter text and OCR noise over moving video
  don't create junk; background signage is filtered out. Visual novel text that types itself out
  a few characters at a time is followed to the end of the line (each frame is compared with the
  last one OCR'd, cell by cell), so lines aren't cut mid-sentence.
- **Screen dictionary (tap the real text, in any app)** — while auto OCR runs, each read is laid
  as an invisible text layer exactly over the subtitles/dialogue on screen (meikiocr's and ML Kit's
  per-character positions). Tap a character in a YouTube hardsub or a game's text box and the word
  covering it lights up with its definition right next to it; hold and drag to select characters.
  The layer is one invisible window per line, fitted to it, so touches elsewhere (a game's
  buttons, the empty part of a text box) still reach the app. Videos are fast, so a
  lookup pauses playback (media key: YouTube and most players) and resumes it when the popup closes;
  if you pause yourself, the layer stays and you can tap word after word. Mining links the
  log line, so the card gets the sentence, screenshot and voice clip.
- **Already in Anki** — like Yomitan, the popup checks your note type for the word: if it already
  has a note the button shows ✓ *In Anki* instead of making a duplicate. A card keeps being added
  if you close the popup right after tapping *Anki*.
- **Tablets and landscape** — tabs move to a side rail and screens sit in a centered column.
- **Live text window** — a floating feed of the lines over the game, updated in real time. Tap
  a word in it and the definition pops up right next to it (same popup as the on-screen text
  layer; mining uses that line's audio and screenshot); hold a line to open it in the full panel. Scroll back through the history (new
  lines only pull it down while you're at the bottom; *Latest* jumps back), drag the corner grip
  to resize, shrink it to a one-line ticker (⌃⌄), or open it fullscreen with big text to read
  back (auto OCR pauses there, since it would cover the game). Size and position are remembered
  per orientation (a big portrait window would cover a landscape video), and so is the mode;
  fullscreen always reopens as the normal window.
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
- **One add button per reading, like Yomitan** — when a lookup finds several headwords (呼び and
  呼ぶ, 取り and 取る, different readings of one kanji), the popup lists each with its own
  *+ Anki* button and in-Anki state; the card gets only that headword's reading, glossary,
  pitch and frequency. The header button adds the first one.
- **Mining feedback** — the popup's Anki button spins while the card is built, turns green
  (*Added*) when it's in, and a toast confirms it (or says why not) even if the popup was closed.
- **Card editor** — the ✎ joined to every *+ Anki* button opens a dialog to fine-tune the card
  first: the sentence (a text field with the mined word in bold, plus one chip per sentence of a
  multi-sentence line to keep only that one; a 「」 split across sentences loses its unmatched half) and the sentence audio (waveform, playback, a
  two-handle range slider that starts on the detected voice, or a switch to leave the audio off;
  silent clips of unvoiced lines start off). *Add to Anki* mines it like the
  plain button, so the popup's state follows. Over other apps it is its own focusable overlay
  window (the keyboard works there); in the app a dialog.
- **Pick what goes on the card** — every dictionary header in the popup is a button: *✓ On card*
  on the dictionary whose definitions go into the glossary (by default the top one), *Use on card*
  (faded) on the others. Tapping one puts only that dictionary on that headword's card; tapping it
  again returns to the setting.
- **Texts page** (GSM's texthooker) — every recognized line, newest first. Tap a line, tap a word,
  and mine it: the card gets that line's sentence, its screenshot and its voice clip.
- **Replay buffer sentence audio** — the last 5 minutes (configurable 1–10) of game audio are kept
  in memory. Any line still inside the buffer can be mined with audio cut from when it appeared to
  when it was replaced (± configurable offsets). Preview or fine-tune it in the waveform editor
  before mining; optional (experimental) silence trimming is in Settings.
- **Floating popup overlay** — a draggable bubble that stays on screen. Tap it, drag a box over a
  word and its definition pops up right next to it (*Quick lookup*, the default); other words in
  the box can be tapped too. Long-press opens the menu, where *Bubble tap* switches to *Scan
  region* (the region editor with Scan / Full screen / Live and the results panel). The
  notification stays available as a shortcut.
- **Japanese OCR trained on games** — on-device [meikiocr](https://github.com/rtr46/meikiocr)
  (ONNX Runtime), a detector + recognizer trained on Japanese video-game text: 3–19× fewer
  character errors than ML Kit on stylized, outlined game/subtitle fonts (small kana, dakuten).
  One-time 31 MB model download; ML Kit (with a GameSentenceMiner-style two-pass pipeline) is
  built in as the fallback/alternative engine.
- **Yomitan dictionaries & drag-and-drop priority** — import any Yomitan/Yomichan format 3 `.zip`
  (Jitendex, JMdict, JMnedict, KANJIDIC, frequency dictionaries) or install recommended dictionaries
  (Jitendex, JMdict, KANJIDIC, Kanjium pitch accents, Pixiv Light encyclopedia, JMnedict names) with one tap. Dictionaries can
  be reordered via drag-and-drop with priority badges (`#1`, `#2`…) just like in Yomitan to control
  definition ranking and `{glossary-first}` mining. Proper-name dictionaries (JMnedict) automatically
  start at the bottom of the list so they never shadow standard vocabulary.
  Term, kanji, tag, term-meta and kanji-meta banks are all parsed, including structured-content
  glossaries and dictionary images (rendered like Yomitan: preferred size, aspect ratio, em units,
  pixelated/monochrome/collapsed images, captions; stored as Anki media on cards). Updatable dictionaries (Jitendex, JMnedict, or any Yomitan dictionary
  with an `indexUrl` in `index.json`) are checked for newer revisions automatically and can be
  updated in place with one tap, preserving custom priority order and enabled state.
- **Install from files or Google Drive** — *From files* picks one or several `.zip`s at once from
  the device (Downloads, SD card, any document provider). *Google Drive* opens a sheet with two
  ways in: *Browse my Drive* (Android's file picker, where the Google Drive app is a source, so
  private files work and the Drive app handles sign-in), or paste a share link to a file or a
  public folder: DSM lists the folder's `.zip`s (subfolders included), you tick the ones you want
  and they install one after another. Recently opened folders are remembered. No Google API or
  in-app login is involved. The install queue runs app-wide (leaving the page or rotating doesn't
  stop it), recommended installs and updates join the same queue, and banks are streamed, so big
  monolingual dictionaries (広辞苑, 大辞林, Pixiv) import without running out of memory.
- **Deinflection & word scanning** — a full Japanese deconjugator drives longest-match scanning of
  scanned text and tap-to-look-up, with the same rules Yomitan uses.
- **Anki mining** — two backends:
  - **AnkiDroid API** (no network needed; cards, images and audio via the official API), and
  - **AnkiConnect** (desktop Anki with the AnkiConnect add-on; card + media upload over HTTP).
  Cards include Expression, Reading, Meaning (rendered glossary), Sentence, Image (captured
  screenshot, scaled to a max height — 462 px by default, aspect ratio kept, 0 = original — set in
  Settings → Anki), Audio (sentence voice line), **Word audio** and Source. Existing note types are
  filled by field name the way Yomitan/GSM fill them — e.g. Anime Cards: Word, Reading, Glossary,
  Context Sentence (Sentence stays empty so it stays a word card), Word Audio, Audio, Picture.
  **Settings → Anki → Fields** shows what goes into each field of the chosen note type and lets you
  pick the content per field (Word, Reading, Glossary, Sentence, Screenshot, Sentence/Word audio,
  Pitch graph, Source or nothing); the mapping is saved per note type, *Reset to automatic* drops it.
- **Word audio like Yomitan** — the word's pronunciation is fetched from the same sources Yomitan
  uses for Japanese, in the same order: JapanesePod101 (placeholder clip rejected by hash),
  JapanesePod101 dictionary search, Jisho; plus an optional custom URL (`{term}`/`{reading}`,
  e.g. a local audio server) tried first.
- **Glossary like Yomitan's markers** — *Settings → Cards → Glossary*: only the first dictionary
  (`{glossary-first}`, default), all dictionaries without their names (`{glossary-no-dictionary}`),
  or all under their names (`{glossary}`). The popup can override it per card.
- **Pitch accent graph** — shown in the lookup popup under each headword, and on the card: the same SVG Yomitan's `{pitch-accent-graphs}` writes (byte-identical to
  the user's existing Yomitan cards), filled into a pitch/graph field such as Anime Cards' *Graph*.
  Uses any installed Yomitan pitch dictionary, or the free Kanjium pitch accents (124k words,
  one-click install from *Settings → Dictionaries* or the Home setup card).
- **Sentence audio** (Android 10+) — captures playback audio through the existing MediaProjection
  session (the Android equivalent of GSM's loopback capture) into the replay buffer, with a
  waveform **audio editor** to trim the exact line before mining. Every clip is **normalized** to
  the same loudness (on by default, *Settings → Sentence audio*).
- **Microphone fallback** — some apps block audio capture and Android hands over pure silence
  (DroidDeck, which runs Steam games, targets Android 9; Netflix). Turn on *Microphone fallback*
  (Settings or the bubble menu; off until you allow it) and DSM records the microphone whenever
  device audio is silent while something is playing, and switches back as soon as device audio
  returns. The mic goes through an FX chain built for a speaker heard through a microphone (each
  stage on by default, can be turned off): Android's voice **noise suppression**, a **speaker
  correction EQ** — a 120 Hz rumble filter plus a 19-band curve measured on your device by
  *Calibrate* (6 s of pink noise played and recorded back), which restores the missing low mids
  and tames the harsh 4–8 kHz resonance of tablet/phone speakers — then a **compressor** that
  squeezes loud peaks so normalization can lift thin, quiet dialogue. Works with the speaker on;
  picks up room noise; records nothing with headphones.
- **Mining history** — recent cards with a status showing whether they made it into Anki.
- **Share sheet** — text shared to the app (`ACTION_SEND`) opens the same lookup as a selection;
  a shared image opens in Book mode (`SharePhotoActivity` copies it into the cache first).
- **Text selection lookup** — other apps can send selected text via `ACTION_PROCESS_TEXT`, including
  Samsung's text selection on screenshots/images and Circle to Search. A selected word is matched to
  the newest logged line containing it, so the card still gets the full sentence, screenshot and
  audio; a selected sentence is logged as a new line. Handy for text auto OCR can't read (vertical
  or handwritten-style), using Google's cloud OCR by hand.
