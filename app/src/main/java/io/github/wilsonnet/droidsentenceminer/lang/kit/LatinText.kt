package io.github.wilsonnet.droidsentenceminer.lang.kit

import java.text.Normalizer

/** OCR text helpers for Latin-script languages: scoring a read and cleaning its spacing. */
object LatinText {
    private const val MIN_TARGET_CHARS = 3
    private const val MIN_SCORE = 3.0
    private const val SCRIPT_WEIGHT = 10.0
    private const val CHAR_WEIGHT = 0.01
    private const val BAD_PENALTY = 20.0
    private const val BAD_CHAR_COST = 4
    private val SPACES = Regex("""[ \t]+""")

    /** A Latin letter. */
    fun isLatinLetter(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    /** Scores OCR output: share of [isTarget] letters, minus replacement characters; higher is better. */
    fun score(text: String, isTarget: (Char) -> Boolean = ::isLatinLetter): Double {
        val letters = text.count { it.isLetterOrDigit() }
        val bad = text.count { it == '�' } * BAD_CHAR_COST
        val good = text.count { !it.isWhitespace() && it != '�' }
        val target = text.count(isTarget)
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return if (letters == 0) 0.0 else target.toDouble() / letters * SCRIPT_WEIGHT + good * CHAR_WEIGHT - penalty * BAD_PENALTY
    }

    /** True when [text] has a few [isTarget] letters and scores as text (auto OCR drops reads that don't). */
    fun looksLikeText(text: String, isTarget: (Char) -> Boolean = ::isLatinLetter): Boolean =
        text.count(isTarget) >= MIN_TARGET_CHARS && score(text = text, isTarget = isTarget) >= MIN_SCORE

    /**
     * NFKC, every kind of space (NBSP, narrow NBSP, ideographic) to one space, and no space before
     * the characters in [noSpaceBefore] (English: all of `,.!?;:`; French only `,.`).
     */
    fun normalizeSpacing(text: String, noSpaceBefore: String = ",.!?;:"): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(oldChar = ' ', newChar = ' ').replace(oldChar = ' ', newChar = ' ').replace(oldChar = '　', newChar = ' ')
        t = t.replace(SPACES, " ")
        if (noSpaceBefore.isNotEmpty()) t = t.replace(Regex(" (?=[" + Regex.escape(noSpaceBefore) + "])"), "")
        return t.trim()
    }
}
