package io.github.wilsonnet.droidsentenceminer.ocr

/**
 * Auto OCR reads a fixed region every few hundred ms, so background text (signs, labels,
 * UI) drifting through it would otherwise get glued onto the dialogue. Dialogue lines share
 * one font size; rows that are much smaller or larger than the main text, or carry almost no
 * Japanese, are dropped.
 *
 * Pure Kotlin, unit tested.
 */
object SubtitleLineFilter {

    fun filter(lines: List<OcrPostProcessor.MergedLine>): List<OcrPostProcessor.MergedLine> {
        if (lines.size <= 1) return lines
        val dialogue = lines.filter { japaneseCount(it.text) >= MIN_JAPANESE }
        if (dialogue.isEmpty()) return lines
        // The longest Japanese row anchors the expected glyph height.
        val anchor = dialogue.maxBy { japaneseCount(it.text) }.box.height.toFloat()
        if (anchor <= 0f) return lines
        return lines.filter { line ->
            val ratio = line.box.height / anchor
            japaneseCount(line.text) >= MIN_JAPANESE && ratio in MIN_HEIGHT_RATIO..MAX_HEIGHT_RATIO
        }
    }

    private fun japaneseCount(text: String) = text.count { JapaneseTextScore.isJapanese(it) }

    private const val MIN_JAPANESE = 3
    private const val MIN_HEIGHT_RATIO = 0.7f
    private const val MAX_HEIGHT_RATIO = 1.4f
}
