# Droid Sentence Miner

A native Android sentence miner for Japanese immersion: OCR text from any app with a floating
popup, look words up with Yomitan dictionaries, and mine them into Anki — plus the game's voice
line as sentence audio.

Inspired by [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner), rebuilt for Android
with [Yomitan](https://github.com/yomidevs/yomitan) dictionary compatibility.

**Website:** [wilsonnet.github.io/droid-sentence-miner](https://wilsonnet.github.io/droid-sentence-miner/) ·
**User docs:** [Installation](https://wilsonnet.github.io/droid-sentence-miner/docs/getting-started/installation/) ·
[Auto OCR](https://wilsonnet.github.io/droid-sentence-miner/docs/guides/auto-ocr/) ·
[Troubleshooting](https://wilsonnet.github.io/droid-sentence-miner/docs/reference/troubleshooting/) ·
**[Changelog](CHANGELOG.md)**

<p align="center">
  <img src="docs/screenshots/book-reader.jpg" width="30%" alt="Book reader: tap a word on a manga page" />
  <img src="docs/screenshots/texts.jpg" width="30%" alt="Texts: logged lines with screenshot and voice clip" />
  <img src="docs/screenshots/settings-audio.jpg" width="30%" alt="Settings: sentence audio, microphone fallback" />
</p>

## Demo

| Visual novel (Nightshade on Steam via DroidDeck) | YouTube hardsubs |
| --- | --- |
| [![Mining a word from Nightshade](docs/videos/nightshade-mining.gif)](docs/videos/nightshade-mining.mp4) | [![Mining a word from a YouTube video](docs/videos/youtube-mining.gif)](docs/videos/youtube-mining.mp4) |
| Tap 手薄 in the game's text box → definition → *Anki* → ✓ *In Anki* | Tap 消防車 in the burned-in subtitle → *Anki* → ✓ *In Anki* |

Both cards got the sentence, a screenshot, word audio and the sentence's voice clip (click a
GIF for the MP4; recorded on a Xiaomi Pad 7).

## What it does

- **Auto OCR** — box the game's dialogue or a video's subtitles once; every new line is logged
  with its screenshot and voice clip, using OCR trained on game fonts
  ([meikiocr](https://github.com/rtr46/meikiocr), ML Kit as fallback).
- **Tap the real text** — an invisible layer over the on-screen text lets you tap a word in any
  app and get its definition right there. A floating bubble, a live text window and a *Texts* log
  do the same.
- **Yomitan dictionaries** — Jitendex, JMdict, monolingual dictionaries, pitch and frequency
  dictionaries, with images and dictionary CSS. Install them with one tap, from files or from
  Google Drive; reorder by dragging.
- **Anki cards like Yomitan/GSM make them** — through AnkiDroid or AnkiConnect: sentence,
  screenshot, sentence audio, word audio, pitch graph, mapped to your existing note type.
- **Sentence audio** — a replay buffer of the app's audio, a waveform editor, loudness
  normalization, and a calibrated microphone fallback for apps that block capture.
- **Books and manga** — photograph a page and tap the printed text to look it up.

Every feature in detail: [docs/features.md](docs/features.md).

## Get it

Android 8.0+ (sentence audio needs Android 10+), plus [AnkiDroid](https://github.com/ankidroid/Anki-Android)
or desktop Anki with [AnkiConnect](https://foosoft.net/projects/anki-connect/). Signed APKs are on
[GitHub Releases](https://github.com/WilsonNet/droid-sentence-miner/releases); newer releases
upgrade in place.

**Quick start:** *Settings → Dictionaries* → install Jitendex · *Settings → OCR* → download
meikiocr · *Settings → Anki* → pick a backend, deck and note type · start the bubble, open your
game, long-press the bubble → *Start auto OCR*. The full walkthrough is in the
[installation guide](https://wilsonnet.github.io/droid-sentence-miner/docs/getting-started/installation/)
and [docs/usage.md](docs/usage.md).

## Documentation

| For | Read |
| --- | --- |
| Users | [Website docs](https://wilsonnet.github.io/droid-sentence-miner/docs/) (guides, settings, troubleshooting) · [docs/usage.md](docs/usage.md) |
| What changed | [CHANGELOG.md](CHANGELOG.md) |
| How it works | [docs/architecture.md](docs/architecture.md) — pipeline, dictionary format support, compatibility notes |
| Measurements | [docs/validation.md](docs/validation.md) — OCR accuracy, latency, audio and lookup benchmarks |
| What's next | [docs/roadmap.md](docs/roadmap.md) |
| Contributors | [docs/building.md](docs/building.md) · [AGENTS.md](AGENTS.md) (toolchain, debug API, design notes and pitfalls) · [`.opencode/skills/`](.opencode/skills/) (device testing recipes) |

## Contributing

Everything builds from the command line — no Android Studio needed:

```bash
./gradlew testDebugUnitTest assembleDebug   # JDK 21 + Android SDK, see docs/building.md
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds include a localhost HTTP API for driving and checking the app over adb, so most
changes can be validated with `curl` on a real device. Before opening a PR, read
[AGENTS.md](AGENTS.md): it lists the pitfalls already paid for, and every change updates the docs
in the same commit ([checklist](.opencode/skills/dsm-docs/SKILL.md)). The website lives in
[`site/`](site/) (Astro + Starlight).

## Credits

This project stands on the shoulders of these projects — thank you:

- **[GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner)** (GPL-3.0) — the whole
  concept: OCR on untranslated games, popup lookups, one-press Anki cards with audio, the two-pass
  OCR pipeline and the sentence-audio editing workflow.
- **[Yomitan](https://github.com/yomidevs/yomitan)** (GPL-3.0) — the dictionary format, deinflection
  approach, structured-content glossaries and popup UX that this app implements for Android.
- **[Yomihon](https://github.com/yomihon/yomihon)** (Apache-2.0) — the Kotlin Japanese deinflector in
  `JapaneseDeinflector.kt` is adapted from this project (which in turn credits Jiten). Also a great
  reference for Android Yomitan-dictionary parsing.
- **[Jiten](https://github.com/Sirush/Jiten)** — deinflection rule data used by the deinflector.
- **[meikiocr](https://github.com/rtr46/meikiocr)** by rtr46 (code Apache-2.0, models
  [meiki.text.detect.v0](https://huggingface.co/rtr46/meiki.text.detect.v0) and
  [meiki.txt.recognition.v0](https://huggingface.co/rtr46/meiki.txt.recognition.v0), LGPL-3.0) —
  the default OCR engine; the models are downloaded from Hugging Face at runtime and the
  pre/post-processing in `MeikiDecoder.kt` is ported from its reference pipeline. The APK ships
  modified graphs of both models (shorter detector inputs, any-width recognizer; LGPL-3.0, built
  by `tools/meiki-variants` from the originals, whose weights they read at runtime).
- **[meikidroid](https://github.com/rtr46/meikidroid)** by rtr46 — Android game OCR overlay that
  served as a reference for the region-capture + overlay workflow and the hotkey idea.
- **[ONNX Runtime](https://onnxruntime.ai/)** (MIT) — runs meikiocr on device.
- **[ML Kit](https://developers.google.com/ml-kit)** (Google) — on-device Japanese text recognition (alternative engine).
- **[AnkiDroid](https://github.com/ankidroid/Anki-Android)** (GPL-3.0) — the AnkiDroid API used for
  card creation.
- **[AnkiConnect](https://foosoft.net/projects/anki-connect/)** — the HTTP API used for desktop Anki.
- **[OwOCR](https://github.com/AuroraWright/owocr)** — two-pass OCR concepts used by GSM.
- **[Aozora Bunko](https://www.aozora.gr.jp/)** — the bundled sample image uses a passage from
  走れメロス by 太宰治 (public domain), with ruby markup, which doubles as a furigana-filter test case.
- **[Kanjium](https://github.com/mifunetoshiro/kanjium)** (CC BY-SA 4.0) — the one-click pitch accent
  data. "The pitch accent notation, verb particle data, phonetics, homonyms and other additions or
  modifications to EDICT, KANJIDIC or KRADFILE were provided by Uros O. through his free database."
- **[Jitendex](https://jitendex.org/)** and **[jmDict for Yomitan](https://github.com/yomidevs/jmdict-yomitan)**
  — the one-click recommended dictionaries (installed from their official releases).

Droid Sentence Miner is not affiliated with Anki, AnkiDroid, Yomitan, Jiten or GameSentenceMiner.

## License

[GPL-3.0](LICENSE). The adapted Yomihon deinflector remains available under its Apache-2.0 license
wherever required; see the file header for details.
