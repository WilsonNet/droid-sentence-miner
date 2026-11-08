package io.github.wilsonnet.droidsentenceminer.lang.ja

/** Scores Japanese OCR output; higher is better. Used by the two-pass refinement. */
object JapaneseTextScore {
    private const val REPLACEMENT = '\uFFFD'

    fun score(text: String): Double {
        if (text.isBlank()) return 0.0
        var japanese = 0
        var letters = 0
        var good = 0
        var bad = 0
        for (ch in text) {
            when {
                ch == REPLACEMENT -> bad += 4
                ch == '\u3000' -> bad += 1
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> {
                    good++
                    letters++
                    if (isJapanese(ch)) japanese++
                }
                else -> good++
            }
        }
        if (letters == 0) return 0.0
        // Punctuation is neutral: 「百花……っ」 is as Japanese as its three letters.
        val japaneseRatio = japanese.toDouble() / letters
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return japaneseRatio * 10.0 + good * 0.01 - penalty * 20.0
    }

    fun isJapanese(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) || // kana
            (code in 0x4E00..0x9FFF) || // CJK unified
            (code in 0x3400..0x4DBF) || // CJK ext A
            (code in 0xF900..0xFAFF) || // compatibility ideographs
            ch == '々' || ch == '〆' || ch == 'ー'
    }
}
