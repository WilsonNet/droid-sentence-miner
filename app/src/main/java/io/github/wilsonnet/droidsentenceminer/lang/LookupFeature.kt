package io.github.wilsonnet.droidsentenceminer.lang

/**
 * Optional behaviors of the lookup engine (`dict/TermLookup`, implemented in `dict/LookupSteps`).
 * Scanning and deinflection are already per language ([LanguageModule.candidateLengths],
 * [LanguageModule.deinflect]); these are the engine steps around them that only make sense for some
 * languages or dictionaries. A language lists the ones it wants in [LanguageModule.lookupFeatures].
 *
 * Adding one: a value here, its implementation in `LookupSteps` (a post-lookup `LookupStep`
 * step or a scan/rank switch in `TermLookup`), then opt in from the languages that need it.
 */
enum class LookupFeature {
    /**
     * Replace "form of" entries — a glossary that is only `["lemma", ["inflection"]]`, as Wiktionary
     * (wty) dictionaries list conjugated forms — by the lemma's own entry with that inflection,
     * following chains two levels (allées → allé → aller). Yomitan's behavior.
     */
    FORM_OF_ENTRIES,

    /**
     * Keep rows that share headword, reading, tags and sequence apart when their glossaries differ:
     * wty numbers every row 0, so homographs (suis: être / suivre) would collapse into one.
     */
    SEPARATE_HOMOGRAPHS,

    /**
     * While scanning a line, skip one-character kana matches that are only particles, copulas or
     * auxiliaries (Japanese: full dictionaries otherwise turn every は and の into a word).
     */
    SKIP_GRAMMAR_NOISE,

    /** Look up the kanji of the word in kanji dictionaries (KANJIDIC) for the popup. */
    KANJI_INFO,
    ;

    /** Default selections. */
    companion object {
        /** What a language built on Wiktionary (wty) dictionaries wants: English, German, French. */
        val WIKTIONARY: Set<LookupFeature> = setOf(FORM_OF_ENTRIES, SEPARATE_HOMOGRAPHS)
    }
}
