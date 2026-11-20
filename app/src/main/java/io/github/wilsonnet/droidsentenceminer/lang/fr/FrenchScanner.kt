package io.github.wilsonnet.droidsentenceminer.lang.fr

import io.github.wilsonnet.droidsentenceminer.lang.kit.LatinText
import io.github.wilsonnet.droidsentenceminer.lang.kit.RowJoiner
import io.github.wilsonnet.droidsentenceminer.lang.kit.SpacedScanner

/** French scanning and OCR text helpers (see [FrenchLanguage]), built from `lang/kit`. Unit tested. */
internal object FrenchScanner {
    /** Longest phrase, in words, tried at one position ("pomme de terre", "il y a"). */
    const val MAX_WORDS = 4

    /** Words and phrases of up to [MAX_WORDS] words; "l'homme" is one word, "est-ce" a phrase. */
    val SCANNER = SpacedScanner(maxWords = MAX_WORDS)

    // OCR reads « » as << >> on some fonts.
    private val OPEN_GUILLEMET = Regex("""<<""")
    private val CLOSE_GUILLEMET = Regex(""">>""")

    // An elided word whose apostrophe the OCR detached ("l' homme", "qu 'il") or read as ` or ´.
    private val ELISION_SPACING =
        Regex("""(?<![\p{L}'’])(?i)(l|d|j|m|t|s|n|c|qu|jusqu|lorsqu|puisqu|quoiqu) ?(['’`´]) ?(?=\p{L})""")

    // A line-end hyphen before one of these is an inversion or compound (dit-il, peut-être), kept.
    private val HYPHEN_FOLLOWERS =
        setOf(
            "il", "ils", "elle", "elles", "on", "je", "tu", "nous", "vous", "moi", "toi", "lui", "leur",
            "le", "la", "les", "y", "en", "ce", "t", "être", "ci", "là", "même", "mêmes",
        )

    /**
     * Width and spacing (NBSP, thin spaces; French keeps its space before ; : ! ? but never before
     * , or .), « » read as << >>, and detached elision apostrophes.
     */
    fun normalizeOcr(text: String): String {
        var t = LatinText.normalizeSpacing(text = text, noSpaceBefore = ",.")
        t = t.replace(OPEN_GUILLEMET, "«").replace(CLOSE_GUILLEMET, "»")
        t =
            t.replace(ELISION_SPACING) { match ->
                val apostrophe = match.groupValues[2].let { if (it == "’") it else "'" }
                match.groupValues[1] + apostrophe
            }
        return t
    }

    /**
     * A line ending in a hyphen is a word broken across lines (connais- sance → connaissance) unless
     * the next line starts with a capital (Saint- Denis) or an inverted pronoun / compound end
     * (dit- il → dit-il, peut- être → peut-être); anything else gets a space.
     */
    fun joinRows(previous: String, next: String): String {
        val joined =
            RowJoiner.join(previous = previous, next = next) { row ->
                val nextWord = row.substringBefore(' ').trimEnd(',', '.', '!', '?', ';', ':')
                val isKept = nextWord.lowercase() in HYPHEN_FOLLOWERS || row.first().isUpperCase()
                if (isKept) RowJoiner.Hyphen.KEEP else RowJoiner.Hyphen.JOIN
            }
        return joined
    }
}
