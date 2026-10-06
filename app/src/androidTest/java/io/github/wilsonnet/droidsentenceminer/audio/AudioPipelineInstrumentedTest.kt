package io.github.wilsonnet.droidsentenceminer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device checks for the sentence-audio pipeline (VAD segmentation + encoding)
 * that cannot run in a plain JVM test because they use platform codecs.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineInstrumentedTest {

    private fun tone(ms: Int, freq: Double = 440.0): ShortArray {
        val frames = 44_100 * ms / 1000
        return ShortArray(frames) { i ->
            (sin(2 * PI * freq * i / 44_100.0) * 12_000).toInt().toShort()
        }
    }

    private fun silence(ms: Int): ShortArray = ShortArray(44_100 * ms / 1000)

    @Test
    fun vadSegmentsToneFollowedBySilence() {
        val vad = VadSegmenter(sampleRate = 44_100, channels = 1, endSilenceMs = 250)
        assertTrue(vad.process(tone(600)).isEmpty())
        val completed = vad.process(silence(500))
        assertTrue("expected at least one segment", completed.isNotEmpty())
        val duration = completed.first().durationMs
        assertTrue("unexpected duration $duration", duration in 500..1400)
    }

    @Test
    fun silenceProducesNoSegments() {
        val vad = VadSegmenter(sampleRate = 44_100, channels = 1)
        repeat(5) { assertTrue(vad.process(silence(200)).isEmpty()) }
    }

    @Test
    fun encodesPcmUsingPlatformCodecs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val segment = AudioSegment(tone(700), sampleRate = 44_100, channels = 1)
        val output = File(context.cacheDir, "instrumented-audio-test.m4a")
        val encoded = M4aEncoder.encode(segment, output)
        assertNotNull("encoder returned null", encoded)
        assertTrue("encoded audio too small: ${encoded!!.bytes.size}", encoded.bytes.size > 1_000)
        assertTrue(encoded.extension == "m4a" || encoded.extension == "wav")
        output.delete()
        File(context.cacheDir, "instrumented-audio-test.wav").delete()
    }

    @Test
    fun wavFallbackMatchesExpectedSize() {
        val segment = AudioSegment(tone(200), sampleRate = 44_100, channels = 1)
        val bytes = WavEncoder.encode(segment)
        assertTrue(bytes.size > 44 + 44_100 * 2 * 150 / 1000)
    }
}
