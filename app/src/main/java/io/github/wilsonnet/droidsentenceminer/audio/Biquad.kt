package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * One second-order IIR section (RBJ Audio EQ Cookbook), transposed direct form II, doubles.
 * Keeps its state across calls, so a stream can be fed chunk by chunk.
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: DoubleArray, length: Int = x.size) {
        var s1 = z1
        var s2 = z2
        for (i in 0 until length) {
            val input = x[i]
            val out = b0 * input + s1
            s1 = b1 * input - a1 * out + s2
            s2 = b2 * input - a2 * out
            x[i] = out
        }
        // Denormals would slow the loop down to a crawl during digital silence.
        z1 = if (kotlin.math.abs(s1) < 1e-20) 0.0 else s1
        z2 = if (kotlin.math.abs(s2) < 1e-20) 0.0 else s2
    }

    /** Magnitude response in dB at [freq] Hz. */
    fun magnitudeDb(freq: Double, sampleRate: Int): Double {
        val w = 2 * PI * freq / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val num = hypot(b0 + b1 * c1 + b2 * c2, -(b1 * s1 + b2 * s2))
        val den = hypot(1 + a1 * c1 + a2 * c2, -(a1 * s1 + a2 * s2))
        return 20 * log10(num / den)
    }

    companion object {
        fun peaking(sampleRate: Int, freq: Double, gainDb: Double, q: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }

        fun highPass(sampleRate: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
