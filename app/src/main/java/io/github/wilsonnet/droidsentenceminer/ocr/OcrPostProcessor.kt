package io.github.wilsonnet.droidsentenceminer.ocr

import io.github.wilsonnet.droidsentenceminer.lang.Languages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cleans up raw OCR line detections the way GSM/OwOCR does for visual novels:
 * furigana filtering, UI-noise filtering, line merging and Japanese text normalization.
 */
object OcrPostProcessor {

    data class MergedLine(val text: String, val box: BoundingBox)

    fun merge(rawLines: List<OcrLine>, dropNoise: Boolean = true): List<MergedLine> {
        if (rawLines.isEmpty()) return emptyList()

        val normalized = rawLines
            .mapNotNull { line ->
                val text = normalize(line.text)
                if (text.isBlank()) null else line.copy(text = text)
            }
        if (normalized.isEmpty()) return emptyList()

        val furiganaFiltered = dropFurigana(normalized)
        val hasJapanese = furiganaFiltered.any { japaneseRatio(it.text) >= 0.35 }
        val noiseFiltered = if (hasJapanese && dropNoise) {
            furiganaFiltered.filter { japaneseRatio(it.text) >= 0.20 || it.text.length <= 1 }
        } else {
            furiganaFiltered
        }
        if (noiseFiltered.isEmpty()) return emptyList()

        val sorted = noiseFiltered.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val group = groups.lastOrNull()
            if (group != null && sameTextRow(group, line)) {
                group += line
            } else {
                groups += mutableListOf(line)
            }
        }

        val merged = groups.map { group ->
            val ordered = group.sortedBy { it.box.left }
            val text = joinRows(ordered.map { it.text })
            val box = ordered.map { it.box }.reduce { acc, box -> acc.union(box) }
            MergedLine(text, box)
        }

        // Remove duplicated text produced by overlapping detections.
        val seen = HashSet<String>()
        return merged.filter { seen.add(it.text) }
    }

    fun toSentenceText(merged: List<MergedLine>): String {
        return merged.joinToString("\n") { OcrTextFixes.apply(it.text) }.trim()
    }

    private fun sameTextRow(group: List<OcrLine>, candidate: OcrLine): Boolean {
        val ref = group.last()
        val refCenter = ref.box.centerY
        val candCenter = candidate.box.centerY
        val avgHeight = (ref.box.height + candidate.box.height) / 2f
        if (abs(refCenter - candCenter) > max(avgHeight * 0.55f, 10f)) return false

        val horizontalGap = when {
            candidate.box.left >= ref.box.right -> candidate.box.left - ref.box.right
            ref.box.left >= candidate.box.right -> ref.box.left - candidate.box.right
            else -> 0
        }
        val allowedGap = max(avgHeight * 0.9f, 24f)
        return horizontalGap <= allowedGap
    }

    /**
     * A conservative furigana heuristic: kana-only lines that are much smaller than
     * the median line and overlap a taller line horizontally are dropped.
     */
    private fun dropFurigana(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val heights = lines.map { it.box.height.toFloat() }.sorted()
        val median = heights[heights.size / 2]
        if (median <= 0f) return lines
        return lines.filterNot { line ->
            val height = line.box.height
            val isSmall = height < median * 0.62f
            val isKanaOnly = line.text.all { Languages.current.isRubyChar(it) }
            val overlapsTaller = lines.any { other ->
                other !== line && other.box.height > height * 1.4f &&
                    horizontalOverlap(line.box, other.box) > 0.3f
            }
            isSmall && isKanaOnly && overlapsTaller && line.text.length <= 10
        }
    }

    private fun horizontalOverlap(a: BoundingBox, b: BoundingBox): Float {
        val overlap = (min(a.right, b.right) - max(a.left, b.left)).toFloat()
        val minWidth = min(a.width, b.width).toFloat()
        if (minWidth <= 0f) return 0f
        return (overlap / minWidth).coerceIn(0f, 1f)
    }

    private fun japaneseRatio(text: String): Float {
        var significant = 0
        var japanese = 0
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                significant++
                if (Languages.current.isTargetChar(ch)) japanese++
            }
        }
        return if (significant == 0) 0f else japanese.toFloat() / significant
    }

    private fun joinRows(parts: List<String>): String =
        parts.fold("") { acc, part -> Languages.current.joinRows(acc, part) }.trim()

    /** OCR text normalization for the study language (NFKC, punctuation, spacing). */
    fun normalize(text: String): String = Languages.current.normalizeOcr(text)
}
