package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPcmTest {

    @Test
    fun `downmix averages stereo channels`() {
        val stereo = AudioSegment(shortArrayOf(100, 300, -200, -400), sampleRate = 44_100, channels = 2)
        val mono = AudioPcm.downmixToMono(stereo)
        assertEquals(1, mono.channels)
        assertEquals(2, mono.frameCount)
        assertEquals(200.toShort(), mono.pcm[0])
        assertEquals((-300).toShort(), mono.pcm[1])
    }

    @Test
    fun `downmix is a no-op for mono`() {
        val mono = AudioSegment(shortArrayOf(1, 2, 3), 44_100, 1)
        assertEquals(mono, AudioPcm.downmixToMono(mono))
    }

    @Test
    fun `trim slices by milliseconds`() {
        val frames = 44_100 // 1 second
        val pcm = ShortArray(frames * 2) { it.toShort() }
        val segment = AudioSegment(pcm, 44_100, 2)
        val trimmed = AudioPcm.trim(segment, 250, 750)
        assertNotNull(trimmed)
        assertEquals(500, trimmed!!.durationMs)
    }

    @Test
    fun `trim clamps out of range values`() {
        val segment = AudioSegment(ShortArray(44_100), 44_100, 1)
        val trimmed = AudioPcm.trim(segment, -500, 99_999)
        assertNotNull(trimmed)
        assertEquals(1000, trimmed!!.durationMs)
    }

    @Test
    fun `trim returns null for empty range`() {
        val segment = AudioSegment(ShortArray(44_100), 44_100, 1)
        assertNull(AudioPcm.trim(segment, 500, 500))
        assertNull(AudioPcm.trim(segment, 600, 400))
    }

    @Test
    fun `peaks are normalized between zero and one`() {
        val pcm = ShortArray(1000) { if (it % 2 == 0) 1000 else -2000 }
        val peaks = AudioPcm.peaks(AudioSegment(pcm, 44_100, 1), buckets = 20)
        assertEquals(20, peaks.size)
        assertTrue(peaks.all { it in 0f..1f })
        assertTrue(peaks.any { it > 0.9f })
    }

    @Test
    fun `a silent clip draws small, not full height`() {
        // Nightshade, unvoiced line through the microphone: hiss around 20 RMS.
        val hiss = ShortArray(2000) { if (it % 2 == 0) 60 else -60 }
        val segment = AudioSegment(hiss, 24_000, 1)
        assertTrue(AudioPcm.peaks(segment, buckets = 10).all { it < 0.1f })
        assertTrue(AudioPcm.isSilent(segment))
        assertFalse(AudioPcm.isSilent(AudioSegment(ShortArray(10) { 3000 }, 24_000, 1)))
    }

    @Test
    fun `rms grows with amplitude`() {
        val quiet = AudioPcm.rms(ShortArray(100) { 100 })
        val loud = AudioPcm.rms(ShortArray(100) { 3000 })
        assertTrue(loud > quiet)
    }

    @Test
    fun `fade edges attenuates first and last samples`() {
        val pcm = ShortArray(44_100) { 10_000 }
        val faded = AudioPcm.fadeEdges(AudioSegment(pcm, 44_100, 1), fadeMs = 10)
        assertTrue(Math.abs(faded.pcm.first().toInt()) < 10_000)
        assertTrue(Math.abs(faded.pcm[1000].toInt()) == 10_000)
        assertTrue(Math.abs(faded.pcm.last().toInt()) < 10_000)
    }

    @Test
    fun `wave encoder writes valid riff header`() {
        val segment = AudioSegment(ShortArray(1000) { 500 }, 44_100, 1)
        val bytes = WavEncoder.encode(segment)
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        assertEquals(44 + 1000 * 2, bytes.size)
    }

    @Test
    fun `normalize brings voiced audio to the target and respects the peak ceiling`() {
        val rate = 24_000
        // 0.5 s of a quiet square wave (amplitude 1000), then 0.5 s of silence that must not count.
        val quiet = AudioSegment(ShortArray(rate) { if (it >= rate / 2) 0 else if (it % 2 == 0) 1000 else -1000 }, rate, 1)
        val out = AudioPcm.normalize(quiet)
        val target = Short.MAX_VALUE * Math.pow(10.0, AudioPcm.DEFAULT_TARGET_DBFS / 20)
        assertEquals(target, Math.abs(out.pcm[0].toDouble()), target * 0.02)
        assertEquals(0, out.pcm[rate - 1].toInt())

        // A loud clip with a near-full-scale peak is turned down, never clipped.
        val loud = AudioSegment(ShortArray(rate) { if (it % 2 == 0) 30_000 else -30_000 }, rate, 1)
        assertTrue(Math.abs(AudioPcm.normalize(loud).pcm[0].toInt()) < 30_000)

        val silent = AudioSegment(ShortArray(rate), rate, 1)
        assertTrue(AudioPcm.normalize(silent) === silent)
    }

    @Test
    fun `a single loud peak does not hold the clip down and is limited under the ceiling`() {
        val rate = 24_000
        // Quiet speech-like square wave with one near-full-scale click.
        val pcm = ShortArray(rate) { if (it % 2 == 0) 1500 else -1500 }
        pcm[rate / 2] = 20_000
        val out = AudioPcm.normalize(AudioSegment(pcm, rate, 1))
        // Peak-capped gain would be 0.89 * 32767 / 20000 = 1.46; the limiter allows twice that.
        assertTrue(Math.abs(out.pcm[0].toInt()) > 1500 * 2.5)
        val ceiling = Short.MAX_VALUE * AudioPcm.PEAK_CEILING
        assertTrue(out.pcm.all { Math.abs(it.toInt()) <= ceiling + 1 })
    }
}
