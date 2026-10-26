package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Streaming soft-knee compressor for the microphone fallback. A speaker heard through the mic has
 * loud peaks (shouts, effects) and quiet, thin speech; squeezing the peaks lets [AudioPcm.normalize]
 * lift the whole clip further, so dialogue sounds fuller on the card. Last stage of [MicFxChain]
 * (after the EQ, so the speaker's harsh treble peak doesn't drive it), keeping its envelope across
 * chunks.
 */
class AudioCompressor(
    sampleRate: Int,
    private val thresholdDb: Double = -30.0,
    private val ratio: Double = 4.0,
    attackMs: Double = 5.0,
    releaseMs: Double = 150.0,
    private val kneeDb: Double = 6.0,
) {
    private val attack = exp(-1.0 / (attackMs / 1000 * sampleRate))
    private val release = exp(-1.0 / (releaseMs / 1000 * sampleRate))

    /** Current gain reduction in dB (≤ 0), smoothed by attack/release. */
    private var reductionDb = 0.0

    /** Static curve: gain change in dB for an input level in dBFS. */
    fun gainDb(levelDb: Double): Double {
        val over = levelDb - thresholdDb
        val slope = 1 / ratio - 1
        return when {
            2 * over < -kneeDb -> 0.0
            2 * abs(over) <= kneeDb -> slope * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            else -> slope * over
        }
    }

    fun process(pcm: ShortArray, length: Int = pcm.size) {
        val x = DoubleArray(length) { pcm[it] / FULL_SCALE }
        process(x, length)
        for (i in 0 until length) {
            pcm[i] = (x[i] * FULL_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Samples in [-1, 1], in place. */
    fun process(x: DoubleArray, length: Int = x.size) {
        for (i in 0 until length) {
            val levelDb = 20 * log10(max(abs(x[i]), 1e-6))
            val target = gainDb(levelDb)
            val coeff = if (target < reductionDb) attack else release
            reductionDb = coeff * reductionDb + (1 - coeff) * target
            x[i] *= 10.0.pow(reductionDb / 20)
        }
    }

    private companion object {
        const val FULL_SCALE = 32768.0
    }
}
