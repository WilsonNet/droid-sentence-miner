package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioCompressorTest {

    private val rate = 24_000

    private fun sine(dbfs: Double, seconds: Double = 1.0): ShortArray {
        val amp = 32768 * 10.0.pow(dbfs / 20)
        return ShortArray((rate * seconds).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toInt().toShort() }
    }

    private fun peakDb(pcm: ShortArray, from: Int): Double =
        20 * log10((from until pcm.size).maxOf { kotlin.math.abs(pcm[it].toInt()) } / 32768.0)

    @Test
    fun `loud signal is squeezed by the ratio above the threshold`() {
        val pcm = sine(-6.0)
        AudioCompressor(rate).process(pcm)
        // Peaks at -6 dBFS: 24 dB over a -30 threshold at 4:1 come out 6 dB over it, give or take
        // the envelope following the waveform within each cycle.
        assertEquals(-24.0, peakDb(pcm, rate / 2), 2.0)
    }

    @Test
    fun `quiet signal passes unchanged`() {
        val pcm = sine(-45.0)
        val before = pcm.copyOf()
        AudioCompressor(rate).process(pcm)
        val diff = sqrt(pcm.indices.sumOf { (pcm[it] - before[it]).toDouble().pow(2) } / pcm.size)
        assertEquals(0.0, diff, 1.0)
    }

    @Test
    fun `soft knee is continuous at its edges`() {
        val c = AudioCompressor(rate)
        assertEquals(0.0, c.gainDb(-33.0), 1e-9)
        assertEquals(-2.25, c.gainDb(-27.0), 1e-9)
        assertEquals(-15.0, c.gainDb(-10.0), 1e-9)
    }
}
