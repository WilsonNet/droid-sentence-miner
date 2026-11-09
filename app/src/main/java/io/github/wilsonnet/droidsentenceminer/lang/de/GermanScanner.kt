package io.github.wilsonnet.droidsentenceminer.lang.de

import java.text.Normalizer

/** German word scanning and OCR text helpers (see [GermanLanguage]). Pure Kotlin, unit tested. */
internal object GermanScanner {
    /** Longest phrase, in words, tried at one position. */
    const val MAX_WORDS = 4

    private const val MIN_TARGET_CHARS = 3
    private const val MIN_SCORE = 3.0
    private const val SCRIPT_WEIGHT = 10.0
    private const val CHAR_WEIGHT = 0.01
    private const val BAD_PENALTY = 20.0
    private const val BAD_CHAR_COST = 4
    private val EMPTY = IntArray(0)
    private val SPACES = Regex("""[ \t]+""")
    private val SPACE_BEFORE_PUNCT = Regex(""" (?=[,.!?;:])""")

    // OCR models without „ read the low quote as two commas.
    private val LOW_QUOTE = Regex("""(^|\s),,(?=\S)""")

    // A line-end hyphen before one of these is an elision ("Haus- und Gartenbau"), not a word break.
    private val ELISION_FOLLOWERS = setOf("und", "oder", "bis", "sowie", "bzw.", "noch", "wie")

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'

    /** Ends of the words and phrases starting at [position], shortest first; empty mid-word. */
    fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray {
        val isWordStart = text[position].isLetterOrDigit() && (position == 0 || !isWordChar(text[position - 1]))
        if (!isWordStart) return EMPTY
        val limit = minOf(a = text.length, b = position + maxLength)
        val lengths = ArrayList<Int>(MAX_WORDS)
        var end = wordEnd(text = text, from = position, limit = limit)
        while (end != null && lengths.size < MAX_WORDS) {
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
        return if (isCutOff || !text[i - 1].isLetterOrDigit()) null else i
    }

    // Start of the next word of a phrase: one space or hyphen, then a letter or digit.
    private fun nextWordStart(text: String, at: Int, limit: Int): Int? {
        val isJoined = at + 1 < limit && (text[at] == ' ' || text[at] == '-') && text[at + 1].isLetterOrDigit()
        return if (isJoined) at + 1 else null
    }

    /** Scores OCR output as German (Latin letters, no replacement characters); higher is better. */
    fun textScore(text: String): Double {
        val letters = text.count { it.isLetterOrDigit() }
        val bad = text.count { it == '�' } * BAD_CHAR_COST
        val good = text.count { !it.isWhitespace() && it != '�' }
        val latin = text.count { GermanLanguage.isTargetChar(it) }
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return if (letters == 0) 0.0 else latin.toDouble() / letters * SCRIPT_WEIGHT + good * CHAR_WEIGHT - penalty * BAD_PENALTY
    }

    /** True when [text] reads as German (auto OCR drops reads that don't). */
    fun looksLikeText(text: String): Boolean {
        val isLatin = text.count { GermanLanguage.isTargetChar(it) } >= MIN_TARGET_CHARS
        return isLatin && textScore(text) >= MIN_SCORE
    }

    /** Width, spacing and „ quotes. */
    fun normalizeOcr(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(oldChar = ' ', newChar = ' ').replace(oldChar = '　', newChar = ' ')
        t = t.replace(SPACES, " ")
        t = t.replace(SPACE_BEFORE_PUNCT, "")
        t = t.replace(LOW_QUOTE, "$1„")
        return t.trim()
    }

    /**
     * A line ending in a hyphen is a word broken across lines (Wör- terbuch → Wörterbuch) unless
     * the next line starts with a capital (a compound: Nord- Ostsee → Nord-Ostsee) or "und"/"oder"
     * (an elision: Haus- und Gartenbau); anything else gets a space.
     */
    fun joinRows(previous: String, next: String): String {
        val nextWord = next.substringBefore(' ')
        return when {
            previous.isEmpty() -> next
            next.isEmpty() -> previous
            previous.last().isWhitespace() -> previous + next
            previous.last() != '-' -> "$previous $next"
            nextWord.lowercase() in ELISION_FOLLOWERS -> "$previous $next"
            next.first().isUpperCase() -> previous + next
            else -> previous.dropLast(1) + next
        }
    }
}
