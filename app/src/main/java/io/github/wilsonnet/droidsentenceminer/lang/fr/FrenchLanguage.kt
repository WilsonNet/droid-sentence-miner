package io.github.wilsonnet.droidsentenceminer.lang.fr

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource

/**
 * French: spaced Latin script with accents, elision (l'homme, qu'il, j'ai) and hyphenated forms
 * (est-ce, peut-être, dit-il). Scanning works like English and German (whole words and phrases of
 * up to [FrenchScanner.MAX_WORDS] words); an elided article or pronoun is stripped during
 * deinflection, so a tap on "l'homme" finds homme. Deinflection: [FrenchDeinflector] (a fallback,
 * Wiktionary lists most inflected forms). Cards get the noun's gender as un/une.
 */
object FrenchLanguage : LanguageModule {
    override val code = "fr"
    override val displayName = "French"
    override val nativeName = "Français"
    override val sample = "Lire en français"
    override val usesSpaces = true
    override val supportsMeikiOcr = false
    override val maxTermLength = 64
    override val linguaLibrePrefix = "LL-Q150 (fra)"
    override val linguaLibreArticles = listOf("le", "la", "les", "un", "une")
    override val wiktionaryRegions = listOf("", "fr", "ca")
    override val wordAudioSources = listOf(WordAudioSource.WIKTIONARY_PAGE, WordAudioSource.LINGUA_LIBRE)
    override val hasNounGender = true

    /**
     * The English pack's PaddleOCR models: the PP-OCRv6 recognizer's character set has every French
     * letter (éèêëàâîïôûùüÿçœæ, capitals too) and « », so the same zip serves French (installed
     * separately under `files/lang/fr`).
     */
    @Suppress("MultilineExpressionWrapping") // A property, not a function: the wrapping below is ktlint's own output.
    override val pack =
        LanguagePack(
            language = "fr",
            version = 1,
            name = "French OCR models",
            description = "PaddleOCR (PP-OCRv5 detector + PP-OCRv6 recognizer, reads accents, œ and « »): more accurate " +
                "than the built-in reader on game and subtitle text.",
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

    private val GENDER_ARTICLES = listOf("masc" to "un", "fem" to "une")

    override fun isTargetChar(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    override fun isLookupable(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    override fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray =
        FrenchScanner.candidateLengths(text = text, position = position, maxLength = maxLength)

    override fun deinflect(surface: String): List<Candidate> = FrenchDeinflector.deinflect(surface)

    /** Wiktionary (wty) tags nouns `masc`/`fem`; nouns with both genders get "un/une". */
    override fun nounArticle(definitionTags: Collection<String>): String? =
        GENDER_ARTICLES.filter { (tag, _) -> tag in definitionTags }.joinToString("/") { it.second }.ifEmpty { null }

    /** Unknown or missing parts of speech (phrases, symbols) accept any deinflection. */
    override fun termMatches(conditions: Long, ruleMask: Long): Boolean =
        conditions == 0L || ruleMask == 0L || ruleMask and conditions != 0L

    override fun ruleMask(rules: String): Long = FrenchDeinflector.ruleMask(rules)

    override fun textScore(text: String): Double = FrenchScanner.textScore(text)

    override fun looksLikeText(text: String): Boolean = FrenchScanner.looksLikeText(text)

    override fun normalizeOcr(text: String): String = FrenchScanner.normalizeOcr(text)

    override fun joinRows(previous: String, next: String): String =
        FrenchScanner.joinRows(previous = previous, next = next)
}
