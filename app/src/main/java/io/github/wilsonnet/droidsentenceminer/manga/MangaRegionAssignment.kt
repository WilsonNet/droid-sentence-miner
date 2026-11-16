package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.manga.MangaRegionGrouping.TextRegion
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlin.math.max
import kotlin.math.min

/** Which detected text region an OCR line (or glyph) belongs to, for [MangaRegionGrouping]. */
internal object MangaRegionAssignment {
    private const val OWNED_FRACTION = 0.5f
    private const val TIE_FRACTION = 0.05f

    private fun area(box: BoundingBox): Long = max(a = 0, b = box.width).toLong() * max(a = 0, b = box.height)

    private fun overlap(a: BoundingBox, b: BoundingBox): Long {
        val w = min(a = a.right, b = b.right) - max(a = a.left, b = b.left)
        val h = min(a = a.bottom, b = b.bottom) - max(a = a.top, b = b.top)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }

    /** Index of the region holding at least half of [box] (the smallest among near-equal candidates), or null. */
    fun ownerOf(box: BoundingBox, regions: List<TextRegion>): Int? {
        val boxArea = area(box).coerceAtLeast(1L).toFloat()
        var best = 0f
        var bestIndex: Int? = null
        regions.forEachIndexed { index, region ->
            val fraction = overlap(a = box, b = region.box) / boxArea
            val current = bestIndex
            val isClearlyBetter = fraction > best + TIE_FRACTION
            val isSmallerTie =
                current != null && fraction > best - TIE_FRACTION && area(region.box) < area(regions[current].box)
            if (isClearlyBetter || isSmallerTie) {
                best = max(a = best, b = fraction)
                bestIndex = index
            }
        }
        return if (best >= OWNED_FRACTION) bestIndex else null
    }

    /** [line] cut where its glyphs move into another region (a detector line crossing two bubbles). */
    fun splitByRegions(line: OcrLine, regions: List<TextRegion>): List<OcrLine> {
        val chars = line.chars?.takeIf { it.size == line.text.length }
        val owners = chars?.map { ownerOf(box = it, regions = regions) }.orEmpty()
        val hasSeveralOwners = owners.filterNotNull().toSet().size >= 2
        return if (chars != null && hasSeveralOwners) cutAtOwnerChanges(line = line, chars = chars, owners = owners) else listOf(line)
    }

    private fun cutAtOwnerChanges(line: OcrLine, chars: List<BoundingBox>, owners: List<Int?>): List<OcrLine> {
        val parts = ArrayList<OcrLine>()
        var start = 0
        for (i in 1..owners.size) {
            val isEnd = i == owners.size
            val isChange = !isEnd && owners[i] != null && owners[start] != null && owners[i] != owners[start]
            if (isEnd || isChange) {
                val pieceChars = chars.subList(fromIndex = start, toIndex = i)
                parts +=
                    line.copy(
                        text = line.text.substring(startIndex = start, endIndex = i),
                        box = pieceChars.reduce(BoundingBox::union),
                        chars = pieceChars.toList(),
                    )
                start = i
            }
        }
        return parts
    }
}
