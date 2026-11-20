package io.github.wilsonnet.droidsentenceminer.lang.kit

/**
 * Scanning for languages that separate words with spaces: candidates start only at a word start
 * and end at word ends, from one word up to [maxWords] words joined by a space or hyphen ("ice
 * cream", "pomme de terre", "E-Mail"). Letters, digits and apostrophes are word characters
 * ("don't", "l'homme").
 */
class SpacedScanner(
    /** Longest phrase, in words. */
    val maxWords: Int,
    /** Whether a word may end in an apostrophe (English possessive plurals: "dogs'"). */
    private val isTrailingApostropheAllowed: Boolean = false,
) {
    /** Ends of the words and phrases starting at [position], shortest first; empty mid-word. */
    fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray {
        val isWordStart = text[position].isLetterOrDigit() && (position == 0 || !isWordChar(text[position - 1]))
        if (!isWordStart) return EMPTY
        val limit = minOf(a = text.length, b = position + maxLength)
        val lengths = ArrayList<Int>(maxWords)
        var end = wordEnd(text = text, from = position, limit = limit)
        while (end != null && lengths.size < maxWords) {
            lengths += end - position
            val next = nextWordStart(text = text, at = end, limit = limit)
            end = next?.let { start -> wordEnd(text = text, from = start, limit = limit) }
        }
        return lengths.toIntArray()
    }

    // End of the word at [from], null when it runs past [limit] (a cut-off word is no candidate).
    private fun wordEnd(text: String, from: Int, limit: Int): Int? {
        var i = from
        while (i < limit && isWordChar(text[i])) i++
        val isCutOff = i >= limit && i < text.length && isWordChar(text[i])
        val last = text[i - 1]
        val isAllowedApostrophe = isTrailingApostropheAllowed && isApostrophe(last)
        val isWordLast = last.isLetterOrDigit() || isAllowedApostrophe
        return if (isCutOff || !isWordLast) null else i
    }

    // Start of the next word of a phrase: one space or hyphen, then a letter or digit.
    private fun nextWordStart(text: String, at: Int, limit: Int): Int? {
        val isJoined = at + 1 < limit && (text[at] == ' ' || text[at] == '-') && text[at + 1].isLetterOrDigit()
        return if (isJoined) at + 1 else null
    }

    /** Shared helpers. */
    companion object {
        private val EMPTY = IntArray(0)

        /** Letters, digits and apostrophes. */
        fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || isApostrophe(c)

        /** A character that may be part of a headword: word characters and hyphens. */
        fun isLookupable(c: Char): Boolean = isWordChar(c) || c == '-'

        /** A straight or curly apostrophe. */
        fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’'
    }
}
