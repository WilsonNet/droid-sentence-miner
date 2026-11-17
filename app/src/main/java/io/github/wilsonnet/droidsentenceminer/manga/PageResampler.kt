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
 * kernel by the scale (a proper low-pass, so screentones don't turn into moiré); upscaling keeps
 * three lobes and clamps each result between its two nearest source samples (mpv's anti-ringing),
 * so black lines on white paper get no halo.
 *
 * @property srcLen source samples
 * @property dstLen output samples
 */
internal class ResampleKernel(val srcLen: Int, val dstLen: Int) {
    /** True when the axis grows. */
    val isUpscale: Boolean = dstLen > srcLen

    /** Taps per output sample. */
    val taps: Int

    /** Source index just before each output sample's centre (anti-ringing pair = this and the next). */
    val near = IntArray(dstLen)

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
            near[o] = floor(center).toInt().coerceIn(minimumValue = 0, maximumValue = srcLen - 1)
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
    private const val MAX = 255f
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
                if (kernel.isUpscale) {
                    val n = kernel.near[o]
                    val a = src[srcRow + n].toInt() and BYTE
                    val b = src[srcRow + min(a = n + 1, b = srcW - 1)].toInt() and BYTE
                    acc = acc.coerceIn(minimumValue = min(a = a, b = b).toFloat(), maximumValue = max(a = a, b = b).toFloat())
                }
                dst[dstRow + o] = (acc.coerceIn(minimumValue = 0f, maximumValue = MAX) + ROUND).toInt().toByte()
            }
        }
    }

    /** Resizes columns of [src] ([width] wide, `kernel.srcLen` tall) into output rows [rows] of [dst]. */
    fun vertical(src: ByteArray, dst: ByteArray, width: Int, kernel: ResampleKernel, rows: IntRange) {
        val acc = FloatArray(width)
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
            val dstRow = o * width
            val nearRow = kernel.near[o] * width
            val nextRow = min(a = kernel.near[o] + 1, b = kernel.srcLen - 1) * width
            for (x in 0 until width) {
                var v = acc[x]
                if (kernel.isUpscale) {
                    val a = src[nearRow + x].toInt() and BYTE
                    val b = src[nextRow + x].toInt() and BYTE
                    v = v.coerceIn(minimumValue = min(a = a, b = b).toFloat(), maximumValue = max(a = a, b = b).toFloat())
                }
                dst[dstRow + x] = (v.coerceIn(minimumValue = 0f, maximumValue = MAX) + ROUND).toInt().toByte()
            }
        }
    }
}
