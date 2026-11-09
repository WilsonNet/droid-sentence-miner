---
name: dsm-language
description: Add or improve a study-language module in Droid Sentence Miner (lang/<code>/ — scanning, deinflection, OCR normalization, recommended dictionaries, downloadable language pack with OCR models). Use when adding a new language, improving English (or any non-Japanese) support, building/publishing a language pack, or when generic code needs language-specific behavior.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Language modules

Japanese is the project's focus; other languages are modules anyone can improve. A module is
plain Kotlin in the APK (a few KB); everything heavy (dictionaries, OCR models) is downloaded only
when a user studies that language. Architecture table: `docs/architecture.md` → *Languages*.

## Rules (enforced by review and the architecture tests)

- Generic packages (`dict`, `ocr`, `capture`, `textlog`, `book`, `overlay`, `anki`, `ui`) never
  import `lang.ja.*` / `lang.en.*` or hard-code a script check. Ask `Languages.current`
  (`isTargetChar`, `isLookupable`, `candidateLengths`, `deinflect`, `ruleMask`/`termMatches`,
  `normalizeOcr`, `joinRows`, `isRubyChar`, `textScore`, `looksLikeText`, `pack`).
  Need a new behavior? Add a method with a default to `LanguageModule`, override it per language.
- A language package only imports `lang.*` (its own) and generic utilities — never another
  language's package.
- `ArchitectureTest` (unit tests) and detekt's `ForbiddenImport` enforce these rules in PR CI.
- Downloadable data goes in a language pack (or a recommended dictionary), never in `assets/`.
- Japanese behavior must not change when you touch shared code: run `JapaneseDeinflectorTest`,
  `OcrPostProcessorTest`, `PageLayoutTest` and a device lookup (`/lookup_bench`) in Japanese.

## Adding a language (e.g. Spanish)

1. `app/src/main/java/.../lang/es/SpanishLanguage.kt`: `object SpanishLanguage : LanguageModule`.
   Start from `EnglishLanguage` for spaced Latin scripts (word/phrase `candidateLengths`, suffix
   rules with POS bits from the dictionaries' `rules` column). Look at Yomitan's
   `ext/js/language/<code>/` transforms for rule ideas.
2. Register it in `Languages.ALL` (`lang/Language.kt`) and add a one-line blurb in
   `ui/screens/LanguageScreens.kt` → `blurb()`.
3. Dictionaries: append `RecommendedDictionary(language = "es", …)` to
   `RecommendedDictionaries.ALL`. Wiktionary via wty has most languages:
   `https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict/<src>/<target>/wty-<src>-<target>.zip?download=true`
   (+ `index/wty-<src>-<target>-index.json?download=true` as `indexUrl`, `installedTitle = "wty-<src>-<target>"`).
   Check sizes with `curl -sIL`; inspect `rules`/tags of `term_bank_1.json` before writing deinflection.
4. OCR: the bundled ML Kit (Japanese model) reads Latin script. For better accuracy add a pack:
   PP-OCRv6 recognizers read 50 languages. Add an entry to `PACKS` in
   `tools/lang-packs/build_pack.py` (pinned Hugging Face revisions:
   `curl -s https://huggingface.co/api/models/PaddlePaddle/<repo> | jq .sha`), build, then set
   `override val pack = LanguagePack(...)` with the printed SHA-256 and size.
5. Benchmark before claiming accuracy: `dsm-ocr-eval` → *Latin-script / English OCR*.
6. Word audio: set `wordAudioSources` (and `linguaLibrePrefix` = `LL-<Wikidata Q-id> (<ISO 639-3>)`
   for Lingua Libre). Only listed sources are queried; add a `WordAudioSource` + a branch in
   `WordAudio.urls` for a new provider.
7. Unit tests in `app/src/test/.../lang/` (see `EnglishLanguageTest`), docs pass (`dsm-docs`).

## Publishing a language pack

```bash
python3 -m venv /tmp/v && /tmp/v/bin/pip install pyyaml
/tmp/v/bin/python tools/lang-packs/build_pack.py en out/      # prints path, sha256, size
gh release create lang-en-1 out/dsm-lang-en-1.zip --latest=false \
  --title "Language pack: English OCR v1" --notes "Not an app release. ..."
```

`--latest=false` is mandatory: the in-app updater (`AppUpdater`) reads `releases/latest`, and a
pack must never become "latest". The tag must not start with `v` (that triggers the APK release
workflow). Bump `version` in both `PACKS` and the module's `pack` for a new build; installed packs
older than `pack.version` show *Download* again.

Testing a pack before it is published (debug build):

```bash
unzip out/dsm-lang-en-1.zip -d /tmp/pack_en
adb push /tmp/pack_en /data/local/tmp/lang_en
adb shell "run-as io.github.wilsonnet.droidsentenceminer.debug sh -c 'mkdir -p files/lang; rm -rf files/lang/en; cp -r /data/local/tmp/lang_en files/lang/en'"
curl -s localhost:9099/lang_pack            # installedVersion, engine name
curl -s "localhost:9099/ocr?engine=paddle&file=/data/user/0/io.github.wilsonnet.droidsentenceminer.debug/files/x.png"
```

## Debug API

- `/language` → current + available; `?set=en` switches (waits until applied); `?setup=1` shows
  the first-run screen again without touching data.
- `/lang_pack` → pack status and last PaddleOCR timings; `?install=1` downloads (blocking),
  `?remove=1` deletes.
- `/dicts` rows have `language`. `/word_audio?term=` tests the language's word-audio sources.

## Known numbers (Xiaomi Pad 7, 2026-11-08)

English OCR (80 game/subtitle-style frames): ML Kit CER 6.0 % / WER 12.2 % / 420 ms; PaddleOCR
pack 3.7 % / 3.5 % / 165 ms. YouTube TED captions: 20/20 lines exact. Wiktionary English import
5 min (1.63 M entries); English tap → popup 68 ms warm.
