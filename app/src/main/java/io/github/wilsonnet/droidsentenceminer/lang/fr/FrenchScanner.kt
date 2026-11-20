package io.github.wilsonnet.droidsentenceminer.lang.fr

import java.text.Normalizer

/** French word scanning and OCR text helpers (see [FrenchLanguage]). Pure Kotlin, unit tested. */
internal object FrenchScanner {
    /** Longest phrase, in words, tried at one position ("pomme de terre", "il y a"). */
    const val MAX_WORDS = 4

    private const val MIN_TARGET_CHARS = 3
    private const val MIN_SCORE = 3.0
    private const val SCRIPT_WEIGHT = 10.0
    private const val CHAR_WEIGHT = 0.01
    private const val BAD_PENALTY = 20.0
    private const val BAD_CHAR_COST = 4
    private val EMPTY = IntArray(0)
    private val SPACES = Regex("""[ \t]+""")

    // French puts a (thin) space before ; : ! ? and inside « », but never before , or . — OCR does.
    private val SPACE_BEFORE_COMMA = Regex(""" (?=[,.])""")

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

    /** Scores OCR output as French (Latin letters, no replacement characters); higher is better. */
    fun textScore(text: String): Double {
        val letters = text.count { it.isLetterOrDigit() }
        val bad = text.count { it == '�' } * BAD_CHAR_COST
        val good = text.count { !it.isWhitespace() && it != '�' }
        val latin = text.count { FrenchLanguage.isTargetChar(it) }
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return if (letters == 0) 0.0 else latin.toDouble() / letters * SCRIPT_WEIGHT + good * CHAR_WEIGHT - penalty * BAD_PENALTY
    }

    /** True when [text] reads as French (auto OCR drops reads that don't). */
    fun looksLikeText(text: String): Boolean {
        val isLatin = text.count { FrenchLanguage.isTargetChar(it) } >= MIN_TARGET_CHARS
        return isLatin && textScore(text) >= MIN_SCORE
    }

    /** Width and spacing (NBSP, thin spaces), « » read as << >>, and detached elision apostrophes. */
    fun normalizeOcr(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(oldChar = ' ', newChar = ' ').replace(oldChar = ' ', newChar = ' ').replace(oldChar = '　', newChar = ' ')
        t = t.replace(SPACES, " ")
        t = t.replace(SPACE_BEFORE_COMMA, "")
        t = t.replace(OPEN_GUILLEMET, "«").replace(CLOSE_GUILLEMET, "»")
        t =
            t.replace(ELISION_SPACING) { match ->
                val apostrophe = match.groupValues[2].let { if (it == "’") it else "'" }
                match.groupValues[1] + apostrophe
            }
        return t.trim()
    }

    /**
     * A line ending in a hyphen is a word broken across lines (connais- sance → connaissance) unless
     * the next line starts with a capital (Saint- Denis) or an inverted pronoun / compound end
     * (dit- il → dit-il, peut- être → peut-être); anything else gets a space.
     */
    fun joinRows(previous: String, next: String): String {
        val nextWord = next.substringBefore(' ').trimEnd(',', '.', '!', '?', ';', ':')
        return when {
            previous.isEmpty() -> next
            next.isEmpty() -> previous
            previous.last().isWhitespace() -> previous + next
            previous.last() != '-' -> "$previous $next"
            nextWord.lowercase() in HYPHEN_FOLLOWERS -> previous + next
            next.first().isUpperCase() -> previous + next
            else -> previous.dropLast(1) + next
        }
    }
}
