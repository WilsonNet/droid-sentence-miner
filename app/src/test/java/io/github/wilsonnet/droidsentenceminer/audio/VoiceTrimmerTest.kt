package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceTrimmerTest {

    private val rate = 16_000

    private fun tone(ms: Int, amplitude: Int) = ShortArray(rate * ms / 1000) {
        (amplitude * sin(2 * PI * 220 * it / rate)).toInt().toShort()
    }

    private fun noise(ms: Int, amplitude: Int, seed: Int = 7): ShortArray {
        val random = java.util.Random(seed.toLong())
        return ShortArray(rate * ms / 1000) { ((random.nextDouble() * 2 - 1) * amplitude).toInt().toShort() }
    }

    private fun concat(vararg parts: ShortArray) = AudioSegment(parts.reduce { a, b -> a + b }, rate, 1)

    @Test
    fun trimsSilenceAroundSpeech() {
        val segment = concat(ShortArray(rate), tone(1500, 8000), ShortArray(rate * 2))
        val result = VoiceTrimmer.trim(segment)
        assertTrue(result.trimmed)
        // Speech is 1000..2500 ms; pads are 300 ms before and 500 ms after (plus smoothing spread).
        assertEquals(700.0, result.startMs.toDouble(), 100.0)
        assertEquals(3000.0, result.endMs.toDouble(), 100.0)
    }

    @Test
    fun voiceOverQuietMusicStillTrims() {
        val bgm = noise(4500, 600)
        val voice = tone(1500, 9000)
        val mixed = bgm.copyOf()
        for (i in voice.indices) mixed[rate + i] = (mixed[rate + i] + voice[i]).coerceIn(-32768, 32767).toShort()
        val result = VoiceTrimmer.trim(AudioSegment(mixed, rate, 1))
        assertTrue(result.trimmed)
        assertEquals(700.0, result.startMs.toDouble(), 120.0)
        assertEquals(3000.0, result.endMs.toDouble(), 120.0)
    }

    @Test
    fun silenceIsLeftUntouched() {
        val segment = AudioSegment(ShortArray(rate * 2), rate, 1)
        val result = VoiceTrimmer.trim(segment)
        assertFalse(result.trimmed)
        assertEquals(segment.durationMs, result.endMs)
    }

    @Test
    fun continuousSpeechIsKept() {
        val segment = AudioSegment(tone(2000, 8000), rate, 1)
        val result = VoiceTrimmer.trim(segment)
        assertEquals(0L, result.startMs)
        assertEquals(segment.durationMs, result.endMs)
    }
}
