package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    private val sampleRate = 44_100
    private val channels = 2

    private fun tone(ms: Int, amplitude: Short): ShortArray {
        val frames = sampleRate * ms / 1000
        val out = ShortArray(frames * channels)
        for (i in out.indices) out[i] = amplitude
        return out
    }

    private fun silence(ms: Int): ShortArray = tone(ms, 0)

    @Test
    fun `speech followed by silence produces a segment`() {
        val vad = VadSegmenter(sampleRate, channels, endSilenceMs = 300)
        assertTrue(vad.process(tone(600, 3000)).isEmpty())
        val segments = vad.process(silence(500))
        assertEquals(1, segments.size)
        // pre-roll is included, so it can never be shorter than the speech
        assertTrue(segments.first().durationMs in 500..2000)
    }

    @Test
    fun `pure silence never produces segments`() {
        val vad = VadSegmenter(sampleRate, channels)
        repeat(10) {
            assertTrue(vad.process(silence(200)).isEmpty())
        }
        assertNull(vad.latestSegment())
    }

    @Test
    fun `very short blips are ignored`() {
        val vad = VadSegmenter(sampleRate, channels, startSpeechMs = 200)
        vad.process(tone(50, 3000))
        assertTrue(vad.process(silence(500)).isEmpty())
    }

    @Test
    fun `ongoing speech is exposed as current segment`() {
        val vad = VadSegmenter(sampleRate, channels)
        vad.process(tone(800, 3000))
        val current = vad.currentSegment()
        assertNotNull(current)
        assertTrue(current!!.durationMs > 400)
    }

    @Test
    fun `latest segment prefers completed segments`() {
        val vad = VadSegmenter(sampleRate, channels, endSilenceMs = 250)
        vad.process(tone(500, 2500))
        vad.process(silence(400))
        val completed = vad.latestSegment()
        assertNotNull(completed)
        // no active speech -> it must come from the completed queue
        assertNull(vad.currentSegment())
        assertTrue(completed!!.durationMs >= 500)
    }

    @Test
    fun `max segment length is enforced`() {
        val vad = VadSegmenter(sampleRate, channels, maxSegmentMs = 1000)
        val longAudio = tone(2600, 3000)
        val completed = vad.process(longAudio)
        assertTrue(completed.isNotEmpty())
        assertTrue(completed.first().durationMs <= 1200)
    }

    @Test
    fun `segments queue keeps only recent entries`() {
        val vad = VadSegmenter(sampleRate, channels, endSilenceMs = 250)
        repeat(8) {
            vad.process(tone(500, 2500))
            vad.process(silence(400))
        }
        assertTrue(vad.recentSegments().size <= VadSegmenter.MAX_KEPT_SEGMENTS)
    }

    @Test
    fun `reset clears state`() {
        val vad = VadSegmenter(sampleRate, channels, endSilenceMs = 250)
        vad.process(tone(500, 2500))
        vad.process(silence(400))
        vad.reset()
        assertNull(vad.latestSegment())
        assertTrue(vad.recentSegments().isEmpty())
    }

    @Test
    fun `instrumented parity sine tone mono 250ms`() {
        val frames = 44_100 * 600 / 1000
        val tone = ShortArray(frames) { i -> (Math.sin(2 * Math.PI * 440.0 * i / 44_100.0) * 12_000).toInt().toShort() }
        val v = VadSegmenter(sampleRate = 44_100, channels = 1, endSilenceMs = 250)
        assertTrue(v.process(tone).isEmpty())
        val completed = v.process(ShortArray(44_100 * 500 / 1000))
        assertTrue("expected at least one segment", completed.isNotEmpty())
    }
}
