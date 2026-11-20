package io.github.wilsonnet.droidsentenceminer.lang.de

import io.github.wilsonnet.droidsentenceminer.lang.kit.LatinText
import io.github.wilsonnet.droidsentenceminer.lang.kit.RowJoiner
import io.github.wilsonnet.droidsentenceminer.lang.kit.SpacedScanner

/** German scanning and OCR text helpers (see [GermanLanguage]), built from `lang/kit`. Unit tested. */
internal object GermanScanner {
    /** Longest phrase, in words, tried at one position. */
    const val MAX_WORDS = 4

    /** Words and phrases of up to [MAX_WORDS] words, hyphenated compounds ("E-Mail"). */
    val SCANNER = SpacedScanner(maxWords = MAX_WORDS)

    // OCR models without „ read the low quote as two commas.
    private val LOW_QUOTE = Regex("""(^|\s),,(?=\S)""")

    // A line-end hyphen before one of these is an elision ("Haus- und Gartenbau"), not a word break.
    private val ELISION_FOLLOWERS = setOf("und", "oder", "bis", "sowie", "bzw.", "noch", "wie")

    /** Width, spacing and „ quotes. */
    fun normalizeOcr(text: String): String = LatinText.normalizeSpacing(text).replace(LOW_QUOTE, "$1„")

    /**
     * A line ending in a hyphen is a word broken across lines (Wör- terbuch → Wörterbuch) unless
     * the next line starts with a capital (a compound: Nord- Ostsee → Nord-Ostsee) or "und"/"oder"
     * (an elision: Haus- und Gartenbau); anything else gets a space.
     */
    fun joinRows(previous: String, next: String): String {
        val joined =
            RowJoiner.join(previous = previous, next = next) { row ->
                when {
                    row.substringBefore(' ').lowercase() in ELISION_FOLLOWERS -> RowJoiner.Hyphen.KEEP_SPACED
                    row.first().isUpperCase() -> RowJoiner.Hyphen.KEEP
                    else -> RowJoiner.Hyphen.JOIN
                }
            }
        return joined
    }
}
