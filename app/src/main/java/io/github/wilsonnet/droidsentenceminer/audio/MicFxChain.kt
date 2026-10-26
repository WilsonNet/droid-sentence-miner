package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.roundToInt

/** What the microphone fallback does to the mic after the platform noise suppressor. */
data class MicFx(
    val noiseSuppression: Boolean = true,
    /** High-pass plus the calibrated speaker correction ([profile]; high-pass only without one). */
    val eq: Boolean = true,
    val profile: MicEqProfile? = null,
    val compressor: Boolean = true,
)

/**
 * The microphone fallback's software chain, run on every recorded chunk:
 * high-pass (rumble, handling and fan noise under 120 Hz, where the speaker plays nothing) →
 * speaker correction EQ ([MicEq], measured per device) → [AudioCompressor]. The clip is then
 * loudness-normalized and limited when mined ([AudioPcm.normalize]). Platform noise suppression
 * sits before all of this, in the recorder's input path.
 *
 * About 20 biquads + a compressor per sample at 24 kHz: well under 1 % of one core.
 */
class MicFxChain(sampleRate: Int, fx: MicFx) {
    private val sections = if (fx.eq) MicEq.sections(fx.profile?.gainsDb?.toDoubleArray(), sampleRate) else emptyList()
    private val compressor = if (fx.compressor) AudioCompressor(sampleRate) else null
    private var work = DoubleArray(0)

    val isActive: Boolean get() = sections.isNotEmpty() || compressor != null

    fun process(pcm: ShortArray, length: Int = pcm.size) {
        if (!isActive || length <= 0) return
        if (work.size < length) work = DoubleArray(length)
        val x = work
        for (i in 0 until length) x[i] = pcm[i] / FULL_SCALE
        for (section in sections) section.process(x, length)
        compressor?.process(x, length)
        for (i in 0 until length) {
            pcm[i] = (x[i] * FULL_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        const val FULL_SCALE = 32768.0
    }
}
