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
    fun quietMicrophoneVoiceKeepsItsWholeSpanOnceNormalized() {
        // Microphone fallback level (Nightshade): soft syllables near the trimmer's absolute floor,
        // loud ones well above, room noise all along. Trimming the raw clip kept only the loud
        // part; TextLog.clip normalizes first.
        val segment = concat(
            noise(500, 40),
            tone(1500, 200),
            tone(1500, 1400),
            tone(1500, 200),
            noise(6000, 40, seed = 2),
        )
        val raw = VoiceTrimmer.trim(segment)
        assertTrue("raw trim starts at the loud part", raw.startMs > 1500)
        val normalized = VoiceTrimmer.trim(AudioPcm.normalize(segment))
        assertTrue(normalized.trimmed)
        assertTrue("start ${normalized.startMs}", normalized.startMs <= 500)
        assertTrue("end ${normalized.endMs}", normalized.endMs in 5000..6000)
    }

    @Test
    fun stricterThresholdStopsAtVoiceNotBackgroundMusic() {
        // Microphone fallback: the game's music goes on after the voice, quiet and louder phrases
        // with peaks ~1/7 of the voice's (Nightshade: voice 3000–4900 RMS, music 200–780).
        val music = (0 until 8).map { noise(1000, if (it % 2 == 0) 300 else 1400, seed = it) }.toTypedArray()
        val segment = concat(tone(3000, 8000), *music)
        assertTrue(VoiceTrimmer.trim(segment).endMs > 10_000)
        val strict = VoiceTrimmer.trim(segment, relativeThreshold = 0.15)
        assertTrue("end ${strict.endMs}", strict.endMs in 3000..4000)
    }

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
