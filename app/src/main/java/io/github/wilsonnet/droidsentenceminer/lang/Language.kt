package io.github.wilsonnet.droidsentenceminer.lang

import io.github.wilsonnet.droidsentenceminer.lang.en.EnglishLanguage
import io.github.wilsonnet.droidsentenceminer.lang.ja.JapaneseLanguage

/**
 * Everything that differs between study languages, behind one small interface.
 *
 * A module is plain Kotlin (no assets, no native code): the heavy, language-specific parts —
 * dictionaries, OCR models — are downloaded on demand once the user picks the language, so a
 * Japanese learner never stores English data and vice versa. To add a language, implement this
 * interface in `lang/<code>/`, list it in [Languages.ALL], and add its recommended dictionaries
 * to `RecommendedDictionaries` with that language code.
 */
interface LanguageModule {
    /** ISO 639-1 code; also what Yomitan dictionaries put in `index.json` `sourceLanguage`. */
    val code: String

    /** English name ("Japanese"), used in the UI. */
    val displayName: String

    /** The language's own name ("日本語"), shown beside [displayName] in the picker. */
    val nativeName: String

    /** A short sample used in the picker and empty states. */
    val sample: String

    /** True when words are separated by spaces: scanning starts and ends only at word edges. */
    val usesSpaces: Boolean

    /** Whether the game-trained meikiocr models apply (they only read horizontal Japanese). */
    val supportsMeikiOcr: Boolean

    /**
     * Downloadable data for this language (OCR models), fetched only when the user asks; null when
     * the language needs none. See [LanguagePacks].
     */
    val pack: LanguagePack? get() = null

    /** Whether word audio from JapanesePod101/Jisho applies. */
    val japaneseWordAudio: Boolean get() = false

    /** Longest surface, in characters, tried at one position while scanning. */
    val maxTermLength: Int

    /** A character of this language's script (OCR filters count these). */
    fun isTargetChar(c: Char): Boolean

    /** A character that may be part of a dictionary headword. */
    fun isLookupable(c: Char): Boolean

    /**
     * Lengths of the surfaces to look up at [position] of [text], shortest first. Japanese tries
     * every length; spaced languages only whole words and multi-word phrases.
     */
    fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray

    /** Dictionary-form candidates for [surface] (the surface itself included). */
    fun deinflect(surface: String): List<Candidate>

    /** A dictionary row's Yomitan `rules` string as this language's condition bits (parsed once per row). */
    fun ruleMask(rules: String): Long

    /** Whether a dictionary row with [ruleMask] fits a deinflection candidate with [conditions]. */
    fun termMatches(conditions: Long, ruleMask: Long): Boolean

    /**
     * A ruby/furigana character: small kana-only rows above bigger text are dropped by OCR
     * post-processing. False for languages without ruby.
     */
    fun isRubyChar(c: Char): Boolean = false

    /** Scores OCR output as text of this language; higher is better (two-pass refinement). */
    fun textScore(text: String): Double

    /** True when [text] reads as this language (auto OCR drops reads that don't). */
    fun looksLikeText(text: String): Boolean

    /** OCR text normalization (width, punctuation, spacing). */
    fun normalizeOcr(text: String): String

    /** Joins OCR rows / wrapped lines into one sentence. */
    fun joinRows(previous: String, next: String): String
}

/** The registry and the language the user studies. */
object Languages {
    val ALL: List<LanguageModule> = listOf(JapaneseLanguage, EnglishLanguage)

    const val DEFAULT_CODE = "ja"

    /**
     * The active study language. Set from preferences at startup and on every change
     * (`AppContainer`); pure helpers read it so they need no plumbing.
     */
    @Volatile
    var current: LanguageModule = JapaneseLanguage

    fun byCode(code: String?): LanguageModule = ALL.firstOrNull { it.code == code } ?: JapaneseLanguage

    /** The language a dictionary is for: its `sourceLanguage`, Japanese for dictionaries imported before languages existed. */
    fun codeOfDictionary(sourceLanguage: String?): String =
        sourceLanguage?.lowercase()?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: DEFAULT_CODE
}
