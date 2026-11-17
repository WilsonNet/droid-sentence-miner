package io.github.wilsonnet.droidsentenceminer.manga

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AMD FidelityFX Contrast Adaptive Sharpening (Timothy Lottes, MIT), the non-scaling path on one
 * 8-bit plane. Each pixel is sharpened by its cross neighbours with a weight that falls off where the
 * 3×3 window already spans the full range, so hard ink edges don't ring while soft, blurred line
 * art gets its contrast back.
 */
internal object PageSharpen {
    private const val BYTE = 0xFF
    private const val MAX = 255f
    private const val ROUND = 0.5f
    private const val PEAK_SOFT = 8f
    private const val PEAK_HARD = 5f
    private const val CROSS = 4f
    private const val RANGE = 510

    /** Sharpens rows [rows] of [src] ([width]×[height]) into [dst]; [sharpness] 0..1. */
    @Suppress(
        "CognitiveComplexMethod", // One tight per-pixel loop, kept inline for speed.
        "CyclomaticComplexMethod",
        "LongParameterList", // Planes, their size, the strength and the row range of one parallel task.
    )
    fun cas(src: ByteArray, dst: ByteArray, width: Int, height: Int, sharpness: Float, rows: IntRange) {
        val peak = -1f / (PEAK_SOFT + (PEAK_HARD - PEAK_SOFT) * sharpness)
        for (y in rows) {
            val up = max(a = y - 1, b = 0) * width
            val mid = y * width
            val down = min(a = y + 1, b = height - 1) * width
            for (x in 0 until width) {
                val l = if (x > 0) x - 1 else 0
                val r = if (x < width - 1) x + 1 else x
                val b = src[up + x].toInt() and BYTE
                val d = src[mid + l].toInt() and BYTE
                val e = src[mid + x].toInt() and BYTE
                val f = src[mid + r].toInt() and BYTE
                val h = src[down + x].toInt() and BYTE
                val crossMin = min(a = min(a = min(a = d, b = e), b = min(a = f, b = b)), b = h)
                val crossMax = max(a = max(a = max(a = d, b = e), b = max(a = f, b = b)), b = h)
                val a = src[up + l].toInt() and BYTE
                val c = src[up + r].toInt() and BYTE
                val g = src[down + l].toInt() and BYTE
                val i = src[down + r].toInt() and BYTE
                // Both rings are summed with the cross, as in CAS: 0..510.
                val ringMin = min(a = crossMin, b = min(a = min(a = a, b = c), b = min(a = g, b = i))) + crossMin
                val ringMax = max(a = crossMax, b = max(a = max(a = a, b = c), b = max(a = g, b = i))) + crossMax
                dst[mid + x] =
                    if (ringMax == 0 || crossMin == crossMax) {
                        e.toByte()
                    } else {
                        val amp =
                            sqrt(
                                (min(a = ringMin, b = RANGE - ringMax).toFloat() / ringMax).coerceIn(minimumValue = 0f, maximumValue = 1f),
                            )
                        val w = amp * peak
                        val out = ((b + d + f + h) * w + e) / (1f + CROSS * w)
                        (out.coerceIn(minimumValue = 0f, maximumValue = MAX) + ROUND).toInt().toByte()
                    }
            }
        }
    }
}
