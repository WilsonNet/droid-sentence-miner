package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
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
}
