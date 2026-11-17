package io.github.wilsonnet.droidsentenceminer.manga

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lanczos-3 weights for resizing one axis from [srcLen] to [dstLen] samples. Downscaling widens the
 * kernel by the scale (a proper low-pass, so screentones don't turn into moiré). Every result is
 * clamped to the source samples under the kernel's centre ([clampFrom]..[clampTo], mpv's
 * anti-ringing), so black lines on white paper get no halo in either direction — shrinking the
 * AI-sharpened 2x page rang visibly before the clamp covered downscaling too.
 *
 * @property srcLen source samples
 * @property dstLen output samples
 */
internal class ResampleKernel(val srcLen: Int, val dstLen: Int) {
    /** Taps per output sample. */
    val taps: Int

    /** First source index of each output sample's anti-ringing range. */
    val clampFrom = IntArray(dstLen)

    /** Last source index (inclusive) of each output sample's anti-ringing range. */
    val clampTo = IntArray(dstLen)

    /** [taps] normalized weights per output sample. */
    val weights: FloatArray

    /** [taps] clamped source indices per output sample. */
    val indices: IntArray

    init {
        val scale = dstLen.toDouble() / srcLen
        val stretch = if (scale < 1.0) 1.0 / scale else 1.0
        val support = LOBES * stretch
        taps = ceil(support * 2).toInt() + 1
        weights = FloatArray(dstLen * taps)
        indices = IntArray(dstLen * taps)
        for (o in 0 until dstLen) {
            val center = (o + HALF) / scale - HALF
            val first = floor(center - support).toInt() + 1
            val reach = if (scale < 1.0) stretch * HALF else 0.0
            clampFrom[o] = floor(center - reach).toInt().coerceIn(minimumValue = 0, maximumValue = srcLen - 1)
            clampTo[o] = (floor(center + reach).toInt() + 1).coerceIn(minimumValue = 0, maximumValue = srcLen - 1)
            var sum = 0.0
            for (t in 0 until taps) {
                val w = lanczos((first + t - center) / stretch)
                weights[o * taps + t] = w.toFloat()
                indices[o * taps + t] = (first + t).coerceIn(minimumValue = 0, maximumValue = srcLen - 1)
                sum += w
            }
            if (sum != 0.0) for (t in 0 until taps) weights[o * taps + t] = (weights[o * taps + t] / sum).toFloat()
        }
    }

    private fun lanczos(x: Double): Double {
        val ax = abs(x)
        return when {
            ax < EPSILON -> {
                1.0
            }

            ax >= LOBES -> {
                0.0
            }

            else -> {
                val px = PI * x
                LOBES * sin(px) * sin(px / LOBES) / (px * px)
            }
        }
    }

    private companion object {
        const val LOBES = 3.0
        const val HALF = 0.5
        const val EPSILON = 1e-7
    }
}

/** Separable resize of one 8-bit plane, split by output rows so callers can run it in parallel. */
internal object PageResampler {
    private const val BYTE = 0xFF
    private const val ROUND = 0.5f

    /** Resizes rows [rows] of [src] (`kernel.srcLen` wide) into [dst] (`kernel.dstLen` wide). */
    fun horizontal(src: ByteArray, dst: ByteArray, kernel: ResampleKernel, rows: IntRange) {
        val srcW = kernel.srcLen
        val dstW = kernel.dstLen
        val taps = kernel.taps
        for (y in rows) {
            val srcRow = y * srcW
            val dstRow = y * dstW
            for (o in 0 until dstW) {
                var acc = 0f
                val base = o * taps
                for (t in 0 until taps) {
                    acc += kernel.weights[base + t] * (src[srcRow + kernel.indices[base + t]].toInt() and BYTE)
                }
                var lo = BYTE
                var hi = 0
                for (i in kernel.clampFrom[o]..kernel.clampTo[o]) {
                    val v = src[srcRow + i].toInt() and BYTE
                    lo = min(a = lo, b = v)
                    hi = max(a = hi, b = v)
                }
                dst[dstRow + o] = (acc.coerceIn(minimumValue = lo.toFloat(), maximumValue = hi.toFloat()) + ROUND).toInt().toByte()
            }
        }
    }

    /** Resizes columns of [src] ([width] wide, `kernel.srcLen` tall) into output rows [rows] of [dst]. */
    fun vertical(src: ByteArray, dst: ByteArray, width: Int, kernel: ResampleKernel, rows: IntRange) {
        val acc = FloatArray(width)
        val lo = IntArray(width)
        val hi = IntArray(width)
        val taps = kernel.taps
        for (o in rows) {
            acc.fill(0f)
            val base = o * taps
            for (t in 0 until taps) {
                val w = kernel.weights[base + t]
                if (w != 0f) {
                    val srcRow = kernel.indices[base + t] * width
                    for (x in 0 until width) acc[x] += w * (src[srcRow + x].toInt() and BYTE)
                }
            }
            columnRange(src = src, width = width, rows = kernel.clampFrom[o]..kernel.clampTo[o], lo = lo, hi = hi)
            val dstRow = o * width
            for (x in 0 until width) {
                dst[dstRow + x] = (acc[x].coerceIn(minimumValue = lo[x].toFloat(), maximumValue = hi[x].toFloat()) + ROUND).toInt().toByte()
            }
        }
    }

    // Per-column min and max of source rows [rows] (the anti-ringing range of one output row).
    private fun columnRange(src: ByteArray, width: Int, rows: IntRange, lo: IntArray, hi: IntArray) {
        lo.fill(BYTE)
        hi.fill(0)
        for (i in rows) {
            val srcRow = i * width
            for (x in 0 until width) {
                val v = src[srcRow + x].toInt() and BYTE
                if (v < lo[x]) lo[x] = v
                if (v > hi[x]) hi[x] = v
            }
        }
    }
}
