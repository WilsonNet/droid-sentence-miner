package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A chunk of PCM audio (16-bit, interleaved channels). */
data class AudioSegment(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channels: Int,
) {
    val frameCount: Int get() = if (channels <= 0) 0 else pcm.size / channels
    val durationMs: Long get() = if (sampleRate <= 0) 0 else frameCount * 1000L / sampleRate

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Pure PCM helpers. No Android dependencies so they can be unit tested. */
object AudioPcm {

    fun downmixToMono(segment: AudioSegment): AudioSegment {
        if (segment.channels <= 1) return segment
        val frames = segment.frameCount
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (ch in 0 until segment.channels) {
                sum += segment.pcm[frame * segment.channels + ch]
            }
            mono[frame] = (sum / segment.channels).toShort()
        }
        return AudioSegment(mono, segment.sampleRate, 1)
    }

    /**
     * Loudness normalization: scales the clip so its voiced part (20 ms frames above
     * [GATE_DBFS], so pauses don't count) averages [targetDbfs] RMS, with the gain at most
     * [MAX_GAIN]. Peaks may go up to [LIMIT_HEADROOM] over [PEAK_CEILING]: a soft limiter rounds
     * them back under it, so one shout no longer keeps the whole line quiet. Evens out quiet games, loud videos and the
     * microphone fallback, which records from the speaker far quieter than device audio.
     */
    fun normalize(segment: AudioSegment, targetDbfs: Double = DEFAULT_TARGET_DBFS): AudioSegment {
        val pcm = segment.pcm
        if (pcm.isEmpty()) return segment
        val frame = (segment.sampleRate * segment.channels / 50).coerceAtLeast(1)
        val gate = Short.MAX_VALUE * 10.0.pow(GATE_DBFS / 20)
        var sum = 0.0
        var count = 0L
        var peak = 0
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frame, pcm.size)
            var frameSum = 0.0
            for (j in i until end) {
                val v = pcm[j].toInt()
                frameSum += v.toDouble() * v
                peak = max(peak, abs(v))
            }
            if (sqrt(frameSum / (end - i)) >= gate) {
                sum += frameSum
                count += end - i
            }
            i = end
        }
        if (count == 0L || peak == 0) return segment
        val rms = sqrt(sum / count)
        val gain = minOf(
            Short.MAX_VALUE * 10.0.pow(targetDbfs / 20) / rms,
            Short.MAX_VALUE * PEAK_CEILING * LIMIT_HEADROOM / peak,
            MAX_GAIN,
        )
        if (abs(gain - 1.0) < 0.05) return segment
        val out = ShortArray(pcm.size) { k ->
            (softLimit(pcm[k] * gain / Short.MAX_VALUE) * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return AudioSegment(out, segment.sampleRate, segment.channels)
    }

    /** Linear below [LIMIT_KNEE] of the ceiling, then a tanh curve that approaches [PEAK_CEILING]. */
    private fun softLimit(x: Double): Double {
        val knee = PEAK_CEILING * LIMIT_KNEE
        val a = abs(x)
        if (a <= knee) return x
        val room = PEAK_CEILING - knee
        return kotlin.math.sign(x) * (knee + room * kotlin.math.tanh((a - knee) / room))
    }

    /** Speech loudness on cards, roughly -12 LUFS (loud, like voice lines in commercial decks). */
    const val DEFAULT_TARGET_DBFS = -14.0
    private const val GATE_DBFS = -50.0
    const val PEAK_CEILING = 0.89 // -1 dBFS
    private const val LIMIT_HEADROOM = 2.0 // 6 dB of peaks squeezed by the limiter
    private const val LIMIT_KNEE = 0.7
    private const val MAX_GAIN = 20.0

    /** Slices by time, clamped to bounds. Returns null when the slice is empty. */
    fun trim(segment: AudioSegment, startMs: Long, endMs: Long): AudioSegment? {
        val startFrame = (startMs * segment.sampleRate / 1000).toInt().coerceIn(0, segment.frameCount)
        val endFrame = (endMs * segment.sampleRate / 1000).toInt().coerceIn(0, segment.frameCount)
        if (endFrame <= startFrame) return null
        val channels = segment.channels
        val copy = segment.pcm.copyOfRange(startFrame * channels, endFrame * channels)
        return AudioSegment(copy, segment.sampleRate, channels)
    }

    /**
     * Channels of max amplitude per bucket, normalized to 0..1. Used to draw waveforms. Scaled to
     * the loudest bucket, but never to less than [WAVEFORM_FLOOR]: a silent clip (an unvoiced
     * line) drew its hiss full height and looked like a loud one.
     */
    fun peaks(segment: AudioSegment, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val frames = segment.frameCount
        if (frames == 0) return out
        var maxAmp = WAVEFORM_FLOOR
        for (bucket in 0 until buckets) {
            val start = bucket * frames / buckets
            val end = max(start + 1, (bucket + 1) * frames / buckets)
            var peak = 0
            for (frame in start until minOf(end, frames)) {
                var sampleAbs = 0
                for (ch in 0 until segment.channels) {
                    sampleAbs = max(sampleAbs, abs(segment.pcm[frame * segment.channels + ch].toInt()))
                }
                peak = max(peak, sampleAbs)
            }
            out[bucket] = peak.toFloat()
            maxAmp = max(maxAmp, peak)
        }
        for (i in out.indices) out[i] /= maxAmp
        return out
    }

    /** −30 dBFS: quieter clips draw small. */
    private const val WAVEFORM_FLOOR = 1_000

    /** Nothing in [segment] reaches −40 dBFS: no voice was captured (an unvoiced line). */
    fun isSilent(segment: AudioSegment): Boolean = segment.pcm.none { abs(it.toInt()) >= SILENT_PEAK }

    private const val SILENT_PEAK = 328

    fun rms(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val value = pcm[i].toDouble()
            sum += value * value
        }
        return kotlin.math.sqrt(sum / (to - from))
    }

    /** Applies a short fade in/out to avoid clicks in trimmed audio. */
    fun fadeEdges(segment: AudioSegment, fadeMs: Int = 12): AudioSegment {
        val fadeFrames = (fadeMs * segment.sampleRate / 1000).coerceAtLeast(1)
        val frames = segment.frameCount
        if (frames <= fadeFrames * 2) return segment
        val copy = segment.pcm.copyOf()
        for (frame in 0 until fadeFrames) {
            val gain = frame.toDouble() / fadeFrames
            for (ch in 0 until segment.channels) {
                val index = frame * segment.channels + ch
                copy[index] = (copy[index] * gain).roundToInt().toShort()
            }
        }
        for (frame in 0 until fadeFrames) {
            val gain = frame.toDouble() / fadeFrames
            val actual = frames - 1 - frame
            for (ch in 0 until segment.channels) {
                val index = actual * segment.channels + ch
                copy[index] = (copy[index] * gain).roundToInt().toShort()
            }
        }
        return AudioSegment(copy, segment.sampleRate, segment.channels)
    }
}
