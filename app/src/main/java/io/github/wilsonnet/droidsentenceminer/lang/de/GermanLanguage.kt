package io.github.wilsonnet.droidsentenceminer.lang.de

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource

/**
 * German: spaced Latin script with umlauts and ß. Scanning works like English (whole words and
 * phrases of up to [GermanScanner.MAX_WORDS] words, hyphenated compounds such as "E-Mail"), and
 * every surface is tried as written (nouns are capitalized), lower-cased (sentence starts) and
 * capitalized (ALL-CAPS game text). Deinflection: [GermanDeinflector]. Cards get the noun's
 * article (der/die/das) from Wiktionary's gender tags.
 */
object GermanLanguage : LanguageModule {
    override val code = "de"
    override val displayName = "German"
    override val nativeName = "Deutsch"
    override val sample = "Deutsch lesen"
    override val usesSpaces = true
    override val supportsMeikiOcr = false
    override val maxTermLength = 64
    override val linguaLibrePrefix = "LL-Q188 (deu)"
    override val linguaLibreArticles = listOf("der", "die", "das")
    override val wiktionaryRegions = listOf("", "at", "ch")
    override val wordAudioSources = listOf(WordAudioSource.WIKTIONARY, WordAudioSource.LINGUA_LIBRE)
    override val hasNounGender = true

    /**
     * The English pack's PaddleOCR models: the PP-OCRv6 recognizer's character set includes
     * äöüÄÖÜß, so the same zip serves German (installed separately under `files/lang/de`).
     */
    @Suppress("MultilineExpressionWrapping") // A property, not a function: the wrapping below is ktlint's own output.
    override val pack =
        LanguagePack(
            language = "de",
            version = 1,
            name = "German OCR models",
            description = "PaddleOCR (PP-OCRv5 detector + PP-OCRv6 recognizer, reads äöüß): more accurate than the " +
                "built-in reader on game and subtitle text.",
            url = "https://github.com/WilsonNet/droid-sentence-miner/releases/download/lang-en-1/dsm-lang-en-1.zip",
            sha256 = "068d48a7590871c297bd17a41774c7ad7ea53d4f8c59ff905638211b77b17813",
            sizeMb = 23.1,
        )

    /** Part-of-speech bit for nouns (Yomitan rule "n"). */
    const val NOUN = 1L

    /** Part-of-speech bit for verbs (Yomitan rule "v"). */
    const val VERB = 2L

    /** Part-of-speech bit for adjectives (Yomitan rule "adj"). */
    const val ADJ = 4L

    /** Part-of-speech bit for adverbs (Yomitan rule "adv"). */
    const val ADV = 8L

    private val GENDER_ARTICLES = listOf("masc" to "der", "fem" to "die", "neut" to "das")

    override fun isTargetChar(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    override fun isLookupable(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    override fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray =
        GermanScanner.candidateLengths(text = text, position = position, maxLength = maxLength)

    override fun deinflect(surface: String): List<Candidate> = GermanDeinflector.deinflect(surface)

    /** Wiktionary (wty) tags nouns `masc`/`fem`/`neut`; nouns with two genders get both ("der/das"). */
    override fun nounArticle(definitionTags: Collection<String>): String? =
        GENDER_ARTICLES.filter { (tag, _) -> tag in definitionTags }.joinToString("/") { it.second }.ifEmpty { null }

    /** Unknown or missing parts of speech (phrases, symbols) accept any deinflection. */
    override fun termMatches(conditions: Long, ruleMask: Long): Boolean =
        conditions == 0L || ruleMask == 0L || ruleMask and conditions != 0L

    override fun ruleMask(rules: String): Long = GermanDeinflector.ruleMask(rules)

    override fun textScore(text: String): Double = GermanScanner.textScore(text)

    override fun looksLikeText(text: String): Boolean = GermanScanner.looksLikeText(text)

    override fun normalizeOcr(text: String): String = GermanScanner.normalizeOcr(text)

    override fun joinRows(previous: String, next: String): String =
        GermanScanner.joinRows(previous = previous, next = next)
}
