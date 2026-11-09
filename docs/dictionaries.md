# Dictionaries

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Dictionaries](dictionaries.md) · [Roadmap](roadmap.md)

The one-tap dictionaries in *Settings → Dictionaries*, per study language. Only the study
language's list is shown, recommended ones first; anything else Yomitan-compatible can be
imported from a file or a Google Drive link. The source of truth is
`RecommendedDictionaries.ALL` (`app/src/main/java/.../dict/RecommendedDictionaries.kt`) — update
this page when you change it.

Sizes are the download (zip); the installed database is larger. "Updates" means the app checks the
dictionary's `index.json` and offers newer revisions in place (priority and on/off kept).

## Which one do I want?

| You study… | Install first | Add if you like |
| --- | --- | --- |
| Japanese | **Jitendex** (Japanese → English) + **Kanjium pitch accents** | JMnedict for names, Pixiv Light for anime/game terms, KANJIDIC for kanji |
| English | **Wiktionary English** + **Wiktionary English IPA** | a small English → *your language* glossary |
| German | **Wiktionary German → English** + **Wiktionary German IPA** | Wiktionary German (monolingual, bigger), a German → *your language* glossary |

Wiktionary dictionaries come from [wty](https://github.com/yomidevs/wiktionary-to-yomitan)
(kaikki.org extracts, CC BY-SA), rebuilt regularly; names are `wty-<word language>-<gloss
language>`. A "→ X" dictionary is built from **X's** Wiktionary, so its size depends on how much
that Wiktionary covers the word language — the German → Portuguese one is tiny, German → French
is big.

## Japanese

| Dictionary | What it is | Size | Updates |
| --- | --- | --- | --- |
| Jitendex ★ | Japanese → English, the best free Yomitan dictionary: rich formatting, examples, colored tags. | 37 MB | yes |
| JMdict English | The classic EDRDG JMdict → English. Plainer than Jitendex (which is built from it). | 16 MB | — |
| KANJIDIC English | Kanji readings, meanings, stroke counts and grades. | 0.7 MB | — |
| Kanjium pitch accents | Pitch accent for 124,000 words, drawn as the card's pitch graph (Anime Cards *Graph*). Imported from Kanjium's `accents.txt`, not a Yomitan zip. | 3.2 MB | — |
| Pixiv Light | Japanese → Japanese: summaries of 700,000+ Pixiv encyclopedia articles — anime, games, characters, slang. | 54 MB | yes |
| JMnedict | Names: people, places, companies, works. Installed below vocabulary dictionaries so names never shadow words. | 11 MB | yes |

## English

| Dictionary | What it is | Size | Updates |
| --- | --- | --- | --- |
| Wiktionary English ★ (`wty-en-en`) | English → English: 1.6 M entries, idioms, phrasal verbs, examples, inflected forms (*went* → past of *go*). | 110 MB | yes |
| Wiktionary English IPA ★ (`wty-en-en-ipa`) | IPA (UK and US) under the headword and in the card's IPA field. | 2.8 MB | yes |
| Wiktionary English → Portuguese / Spanish / German / French / Italian / Russian / Chinese / Japanese / Korean (`wty-en-<xx>`) | English words glossed in that language, from that language's Wiktionary. Small; pair with Wiktionary English. | 1–7 MB | yes |

## German

| Dictionary | What it is | Size | Updates |
| --- | --- | --- | --- |
| Wiktionary German → English ★ (`wty-de-en`) | German words glossed in English: 600,000 entries, most of them inflected forms pointing at their lemma (*ging* → *gehen*, *Häuser* → *Haus*) — the German deinflector relies on these. Nouns carry gender tags, which fill the card's *Gender* field (der/die/das). | 17 MB | yes |
| Wiktionary German IPA ★ (`wty-de-en-ipa`) | IPA for German words (English Wiktionary), under the headword and in the card's IPA field. | 1.5 MB | yes |
| Wiktionary German (`wty-de-de`) | German → German from the German Wiktionary: definitions, examples, inflected forms. Bigger and more complete than the English one; use either or both. | 55 MB | yes |
| Wiktionary German IPA, German Wiktionary (`wty-de-de-ipa`) | More IPA transcriptions. | 13 MB | yes |
| Wiktionary German → Portuguese / Spanish / French / Italian / Russian / Chinese / Japanese / Korean (`wty-de-<xx>`) | German words glossed in that language. French (17 MB), Chinese and Russian (5 MB) are useful on their own; the others are under 1 MB. | 0.2–17 MB | yes |

Measured on a Xiaomi Pad 7 (2026-11-09): `wty-de-en` imported 602,286 entries; a tap on
*gemacht* takes 54 ms warm (lookup 5 ms, popup HTML 43 ms) with 13 dictionaries installed.

## Adding a dictionary to this list

Append a `RecommendedDictionary(language = "<code>", …)` to `RecommendedDictionaries.ALL` (for
wty: `$WTY/dict/<src>/<target>/wty-<src>-<target>.zip?download=true`, `indexUrl`
`$WTY/index/wty-<src>-<target>-index.json?download=true`, `installedTitle = "wty-<src>-<target>"`),
check the size with `curl -sIL`, and add a row here. Recipe: `.opencode/skills/dsm-language/SKILL.md`.
