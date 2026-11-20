package io.github.wilsonnet.droidsentenceminer.lang.en

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource
import io.github.wilsonnet.droidsentenceminer.lang.kit.LatinText
import io.github.wilsonnet.droidsentenceminer.lang.kit.PosMask
import io.github.wilsonnet.droidsentenceminer.lang.kit.RowJoiner
import io.github.wilsonnet.droidsentenceminer.lang.kit.SpacedScanner

/**
 * English: words separated by spaces, scanning only at word edges (single words and phrases of
 * up to [MAX_WORDS] words, so "look up" and "ice cream" match), case folding and a small
 * suffix deinflector ([EnglishDeinflector]). Dictionaries: Wiktionary via wty
 * (`RecommendedDictionaries`). Built from the shared `lang/kit` pieces.
 */
object EnglishLanguage : LanguageModule {
    /** Longest phrase, in words. */
    const val MAX_WORDS = 5

    /** Part-of-speech bit for nouns (Yomitan rule "n"). */
    const val NOUN = PosMask.NOUN

    /** Part-of-speech bit for verbs (Yomitan rule "v"). */
    const val VERB = PosMask.VERB

    /** Part-of-speech bit for adjectives (Yomitan rule "adj"). */
    const val ADJ = PosMask.ADJ

    /** Part-of-speech bit for adverbs (Yomitan rule "adv"). */
    const val ADV = PosMask.ADV

    override val code = "en"
    override val displayName = "English"
    override val nativeName = "English"
    override val sample = "Read English"
    override val usesSpaces = true
    override val supportsMeikiOcr = false
    override val maxTermLength = 48
    override val linguaLibrePrefix = "LL-Q1860 (eng)"
    override val wordAudioSources = listOf(WordAudioSource.WIKTIONARY, WordAudioSource.LINGUA_LIBRE)

    /**
     * PaddleOCR PP-OCRv5 mobile detector + PP-OCRv6 small recognizer (Apache-2.0). On English
     * game/subtitle text: CER 4.5 %, WER 4.4 % vs ML Kit's 6.0 % / 12.2 % (dsm-ocr-eval skill).
     * Until it is installed English is read by the bundled ML Kit model.
     */
    @Suppress("MultilineExpressionWrapping") // A property, not a function: the wrapping below is ktlint's own output.
    override val pack =
        LanguagePack(
            language = "en",
            version = 1,
            name = "English OCR models",
            description = "PaddleOCR (PP-OCRv5 detector + PP-OCRv6 recognizer): about half the word errors of the " +
                "built-in reader on game and subtitle text.",
            url = "https://github.com/WilsonNet/droid-sentence-miner/releases/download/lang-en-1/dsm-lang-en-1.zip",
            sha256 = "068d48a7590871c297bd17a41774c7ad7ea53d4f8c59ff905638211b77b17813",
            sizeMb = 23.1,
        )

    // Trailing apostrophes ("dogs'") stay part of the surface; the deinflector reads them as possessives.
    private val SCANNER = SpacedScanner(maxWords = MAX_WORDS, isTrailingApostropheAllowed = true)

    override fun isTargetChar(c: Char): Boolean = LatinText.isLatinLetter(c)

    override fun isLookupable(c: Char): Boolean = SpacedScanner.isLookupable(c)

    override fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray =
        SCANNER.candidateLengths(text = text, position = position, maxLength = maxLength)

    override fun deinflect(surface: String): List<Candidate> = EnglishDeinflector.deinflect(surface)

    override fun termMatches(conditions: Long, ruleMask: Long): Boolean =
        PosMask.matches(conditions = conditions, mask = ruleMask)

    override fun ruleMask(rules: String): Long = PosMask.parse(rules)

    override fun textScore(text: String): Double = LatinText.score(text)

    override fun looksLikeText(text: String): Boolean = LatinText.looksLikeText(text)

    override fun normalizeOcr(text: String): String = LatinText.normalizeSpacing(text)

    /** A line ending in a hyphen continues the word (the hyphen is kept); anything else gets a space. */
    override fun joinRows(previous: String, next: String): String =
        RowJoiner.join(previous = previous, next = next) { RowJoiner.Hyphen.KEEP }
}
