package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deduplicates overlapping ML Kit detections and groups adjacent vertical columns or horizontal
 * rows that belong to the same speech bubble into unified blocks.
 */
internal object MangaOcrGrouping {
    private const val OVERLAP_DEDUP_RATIO = 0.65f
    private const val VERTICAL_ASPECT_RATIO = 1.2f
    private const val MAX_THICKNESS_RATIO = 2.1f
    private const val MAX_VERTICAL_HGAP_RATIO = 1.35f
    private const val MIN_SPAN_OVERLAP_RATIO = 0.25f
    private const val MAX_TOP_DRIFT_RATIO = 3.6f
    private const val MAX_HORIZONTAL_VGAP_RATIO = 1.1f
    private const val MIN_THICKNESS_PX = 12f

    private data class TaggedCluster(
        val lines: List<OcrLine>,
        val box: BoundingBox,
        val isVertical: Boolean,
        val thickness: Float,
    )

    /**
     * Merges split speech-bubble columns from [rawBlocks] into grouped line lists so full
     * sentences stay together for dictionary lookup and Anki context capture.
     */
    fun groupSpeechBubbles(rawBlocks: List<List<OcrLine>>, usesSpaces: Boolean): List<List<OcrLine>> {
        if (rawBlocks.isEmpty()) return emptyList()
        val dedupedBlocks = deduplicateLines(rawBlocks)
        val clusters = dedupedBlocks.mapNotNullTo(ArrayList()) { raw -> toCluster(raw = raw, usesSpaces = usesSpaces) }
        mergeAdjacentClusters(clusters)
        return clusters.map { it.lines }
    }

    private fun toCluster(raw: List<OcrLine>, usesSpaces: Boolean): TaggedCluster? {
        if (raw.isEmpty()) return null
        val isVertical = inferVertical(raw, usesSpaces)
        val thicknesses = raw.map { if (isVertical) it.box.width else it.box.height }.sorted()
        val median = thicknesses.getOrNull(thicknesses.size / 2)?.toFloat() ?: MIN_THICKNESS_PX
        return TaggedCluster(
            lines = raw,
            box = raw.map { it.box }.reduce(BoundingBox::union),
            isVertical = isVertical,
            thickness = max(a = MIN_THICKNESS_PX, b = median),
        )
    }

    private fun deduplicateLines(rawBlocks: List<List<OcrLine>>): List<List<OcrLine>> {
        val kept = ArrayList<OcrLine>()
        val result = ArrayList<List<OcrLine>>(rawBlocks.size)
        for (block in rawBlocks) {
            val cleanedBlock = ArrayList<OcrLine>(block.size)
            for (line in block.sortedByDescending { it.text.trim().length }) {
                val isDup = kept.any { existing -> isOverlappingDuplicate(a = existing.box, b = line.box) }
                if (!isDup) {
                    kept += line
                    cleanedBlock += line
                }
            }
            if (cleanedBlock.isNotEmpty()) result += cleanedBlock
        }
        return result
    }

    private fun isOverlappingDuplicate(a: BoundingBox, b: BoundingBox): Boolean {
        val ix = min(a = a.right, b = b.right) - max(a = a.left, b = b.left)
        val iy = min(a = a.bottom, b = b.bottom) - max(a = a.top, b = b.top)
        if (ix <= 0 || iy <= 0) return false
        val interArea = ix.toLong() * iy
        val minArea = min(a = a.width.toLong() * a.height, b = b.width.toLong() * b.height).coerceAtLeast(1L)
        return interArea.toFloat() / minArea >= OVERLAP_DEDUP_RATIO
    }

    private fun mergeAdjacentClusters(clusters: MutableList<TaggedCluster>) {
        var isChanged = true
        while (isChanged) {
            isChanged = false
            var i = 0
            while (i < clusters.size) {
                if (absorbNeighbors(clusters = clusters, baseIdx = i)) isChanged = true
                i++
            }
        }
    }

    private fun absorbNeighbors(clusters: MutableList<TaggedCluster>, baseIdx: Int): Boolean {
        var isMerged = false
        var j = baseIdx + 1
        while (j < clusters.size) {
            val base = clusters[baseIdx]
            val candidate = clusters[j]
            if (shouldMerge(a = base, b = candidate)) {
                clusters.removeAt(j)
                clusters[baseIdx] =
                    base.copy(
                        lines = base.lines + candidate.lines,
                        box = base.box.union(candidate.box),
                    )
                isMerged = true
            } else {
                j++
            }
        }
        return isMerged
    }

    private fun shouldMerge(a: TaggedCluster, b: TaggedCluster): Boolean {
        val minThick = min(a = a.thickness, b = b.thickness).coerceAtLeast(1f)
        val maxThick = max(a = a.thickness, b = b.thickness)
        val isCompatible = a.isVertical == b.isVertical && maxThick / minThick <= MAX_THICKNESS_RATIO
        return isCompatible &&
            a.lines.any { la ->
                b.lines.any { lb ->
                    if (a.isVertical) {
                        shouldMergeVertical(a = la.box, b = lb.box, colWidth = maxThick)
                    } else {
                        shouldMergeHorizontal(a = la.box, b = lb.box, rowHeight = maxThick)
                    }
                }
            }
    }

    private fun shouldMergeVertical(a: BoundingBox, b: BoundingBox, colWidth: Float): Boolean {
        val hGap = max(a = 0, b = max(a = b.left - a.right, b = a.left - b.right))
        if (hGap > colWidth * MAX_VERTICAL_HGAP_RATIO) return false
        val vOverlap = min(a = a.bottom, b = b.bottom) - max(a = a.top, b = b.top)
        val minH = min(a = a.height, b = b.height).coerceAtLeast(1)
        val hasOverlap = vOverlap >= minH * MIN_SPAN_OVERLAP_RATIO
        val topDrift = abs(a.top - b.top)
        return hasOverlap && topDrift <= colWidth * MAX_TOP_DRIFT_RATIO
    }

    private fun shouldMergeHorizontal(a: BoundingBox, b: BoundingBox, rowHeight: Float): Boolean {
        val vGap = max(a = 0, b = max(a = b.top - a.bottom, b = a.top - b.bottom))
        if (vGap > rowHeight * MAX_HORIZONTAL_VGAP_RATIO) return false
        val hOverlap = min(a = a.right, b = b.right) - max(a = a.left, b = b.left)
        val minW = min(a = a.width, b = b.width).coerceAtLeast(1)
        return hOverlap >= minW * MIN_SPAN_OVERLAP_RATIO
    }

    private fun inferVertical(lines: List<OcrLine>, usesSpaces: Boolean): Boolean {
        val multiChar = lines.filter { it.text.length >= 2 }
        return when {
            usesSpaces -> {
                false
            }

            multiChar.isNotEmpty() -> {
                PageLayout.isVertical(lines)
            }

            else -> {
                val box = lines.map { it.box }.reduce(BoundingBox::union)
                box.height > box.width * VERTICAL_ASPECT_RATIO
            }
        }
    }
}
