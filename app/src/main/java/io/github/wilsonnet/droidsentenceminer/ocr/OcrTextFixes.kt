package io.github.wilsonnet.droidsentenceminer.ocr

/**
 * Corrections for confusions every Japanese OCR engine makes on game fonts.
 *
 * Pure Kotlin, unit tested.
 */
object OcrTextFixes {

    // After kana, 一 is still the numeral before counters/nouns like these (もう一つ, お一人, もう一度).
    private const val NUMERAL_FOLLOWERS =
        "つ人度番日回緒生方本杯枚個歩部員口切息時線気応発年月週階台匹冊通見瞬層般体同定心途段帯面律連斉家円万億千百十品着軒件曲話球点戦歳言粒滴羽頭杯"

    fun apply(text: String): String = longVowelMarks(text)

    /** 一 (kanji "one") right after kana is almost always the long-vowel mark ー (おー, わー, メニュー). */
    fun longVowelMarks(text: String): String {
        if ('一' !in text) return text
        val chars = text.toCharArray()
        for (i in chars.indices) {
            if (chars[i] != '一' || i == 0) continue
            if (!isKana(chars[i - 1])) continue
            val next = chars.getOrNull(i + 1)
            if (next != null && next in NUMERAL_FOLLOWERS) continue
            chars[i] = 'ー'
        }
        return String(chars)
    }

    private fun isKana(c: Char) = c.code in 0x3041..0x3096 || c.code in 0x30A1..0x30FA
}
