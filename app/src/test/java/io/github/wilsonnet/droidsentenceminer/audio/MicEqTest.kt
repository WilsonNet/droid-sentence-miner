package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class MicEqTest {

    private val rate = 24_000

    /** Pad 7 speaker → mic, pink noise, third-octave points vs 1 kHz (see dsm-audio skill). */
    private val pad7 = mapOf(
        100.0 to -24.7, 125.0 to -22.5, 160.0 to -17.0, 200.0 to -13.1, 250.0 to -10.8, 315.0 to -7.5,
        400.0 to -4.8, 500.0 to -3.1, 630.0 to -4.0, 800.0 to -1.0, 1000.0 to -0.7, 1250.0 to -0.1,
        1600.0 to 1.9, 2000.0 to -5.6, 2500.0 to -13.1, 3150.0 to 0.0, 4000.0 to 5.6, 5000.0 to 6.4,
        6300.0 to 15.4, 8000.0 to 10.4, 10000.0 to 1.7,
    )

    private fun pad7OnGrid(): DoubleArray {
        val keys = pad7.keys.sorted()
        return MicEq.grid(rate).map { f ->
            val lf = log10(f)
            val i = keys.indexOfLast { it <= f }.coerceIn(0, keys.size - 2)
            val (a, b) = keys[i] to keys[i + 1]
            val t = ((lf - log10(a)) / (log10(b) - log10(a))).coerceIn(0.0, 1.0)
            pad7.getValue(a) + t * (pad7.getValue(b) - pad7.getValue(a))
        }.toDoubleArray()
    }

    @Test
    fun `fft matches a direct dft`() {
        val n = 64
        val x = DoubleArray(n) { sin(2 * PI * 5 * it / n) + 0.3 * sin(2 * PI * 11 * it / n + 1) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        MicEq.fft(re, im)
        for (k in 0 until n) {
            var dr = 0.0
            var di = 0.0
            for (t in 0 until n) {
                dr += x[t] * kotlin.math.cos(2 * PI * k * t / n)
                di -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(dr, re[k], 1e-9)
            assertEquals(di, im[k], 1e-9)
        }
    }

    @Test
    fun `peaking filter has its gain at the centre and none far away`() {
        val f = Biquad.peaking(rate, 1000.0, -9.0, 3.0)
        assertEquals(-9.0, f.magnitudeDb(1000.0, rate), 0.01)
        assertEquals(0.0, f.magnitudeDb(100.0, rate), 0.1)
        val hp = Biquad.highPass(rate, 120.0)
        assertEquals(-3.01, hp.magnitudeDb(120.0, rate), 0.05)
        assertEquals(0.0, hp.magnitudeDb(2000.0, rate), 0.05)
    }

    @Test
    fun `fit flattens the measured pad 7 speaker within the caps`() {
        val h = pad7OnGrid()
        val gains = MicEq.fit(h, rate)
        val g = MicEq.grid(rate)
        val eq = MicEq.curveDb(gains, rate, g, highPass = false)
        val band = g.indices.filter { g[it] in 300.0..9000.0 }
        val before = sqrt(band.sumOf { h[it] * h[it] } / band.size)
        val after = sqrt(band.sumOf { (h[it] + eq[it]).let { v -> v * v } } / band.size)
        assertTrue("rms deviation $before → $after", after < before / 3)
        assertTrue(gains.all { it in -MicEq.MAX_CUT_DB..MicEq.MAX_BOOST_DB })
        // The 6.3 kHz peak is cut hard, the 2.5 kHz null only gently filled.
        val at = { f: Double -> eq[g.indices.minBy { abs(g[it] - f) }] }
        assertTrue(at(6300.0) < -12)
        assertTrue(at(2500.0) in 0.0..MicEq.MAX_BOOST_DB + 1)
    }

    @Test
    fun `no correction below the speaker's range`() {
        val gains = MicEq.fit(pad7OnGrid(), rate)
        val eq = MicEq.curveDb(gains, rate, doubleArrayOf(70.0), highPass = false)
        assertTrue("70 Hz gets ${eq[0]} dB", abs(eq[0]) < 2.5)
    }

    @Test
    fun `response recovers a known filter from pink noise`() {
        val pink = MicEq.pinkNoise(rate * 6, -18.0)
        val known = listOf(Biquad.highPass(rate, 300.0), Biquad.peaking(rate, 5000.0, 12.0, 2.0))
        val recorded = pink.copyOf().also { x -> known.forEach { it.process(x) } }
        val noise = MicEq.pinkNoise(rate, -80.0, seed = 3)
        val response = MicEq.response(pink, recorded, noise, rate)
        assertNotNull(response)
        val h = response!!
        val g = MicEq.grid(rate)
        val at = { f: Double -> h[g.indices.minBy { abs(g[it] - f) }] }
        assertEquals(12.0, at(5000.0), 1.5)
        assertEquals(0.0, at(1000.0), 1.0)
        assertTrue(at(150.0) < -6)
    }

    @Test
    fun `response refuses a recording buried in noise`() {
        val pink = MicEq.pinkNoise(rate * 2, -18.0)
        val quiet = DoubleArray(pink.size) { pink[it] * 0.001 }
        val noise = MicEq.pinkNoise(rate, -50.0, seed = 3)
        val recorded = DoubleArray(pink.size) { quiet[it] + noise[it % noise.size] }
        assertNull(MicEq.response(pink, recorded, noise, rate))
    }

    @Test
    fun `profile round-trips through json`() {
        val p = MicEqProfile(listOf(1.5, -3.0), listOf(0.1), "Xiaomi 2410CRP4CG", 42)
        assertEquals(p, MicEqProfile.decode(p.encode()))
        assertNull(MicEqProfile.decode("not json"))
        assertNull(MicEqProfile.decode(""))
    }

    @Test
    fun `chain applies the high-pass even without a calibration`() {
        val chain = MicFxChain(rate, MicFx(eq = true, profile = null, compressor = false))
        val hum = ShortArray(rate) { (8000 * sin(2 * PI * 50 * it / rate)).toInt().toShort() }
        chain.process(hum)
        val rms = sqrt((rate / 2 until rate).sumOf { hum[it].toDouble() * hum[it] } / (rate / 2))
        assertTrue("50 Hz hum left at rms $rms", rms < 8000 / sqrt(2.0) * 0.2)
    }

    @Test
    fun `chain with everything off leaves samples alone`() {
        val chain = MicFxChain(rate, MicFx(eq = false, compressor = false))
        val pcm = ShortArray(100) { (it * 37).toShort() }
        val before = pcm.copyOf()
        chain.process(pcm)
        assertTrue(pcm.contentEquals(before))
    }
}
