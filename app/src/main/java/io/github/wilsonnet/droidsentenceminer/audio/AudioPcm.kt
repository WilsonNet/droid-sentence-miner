package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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

    /** Slices by time, clamped to bounds. Returns null when the slice is empty. */
    fun trim(segment: AudioSegment, startMs: Long, endMs: Long): AudioSegment? {
        val startFrame = (startMs * segment.sampleRate / 1000).toInt().coerceIn(0, segment.frameCount)
        val endFrame = (endMs * segment.sampleRate / 1000).toInt().coerceIn(0, segment.frameCount)
        if (endFrame <= startFrame) return null
        val channels = segment.channels
        val copy = segment.pcm.copyOfRange(startFrame * channels, endFrame * channels)
        return AudioSegment(copy, segment.sampleRate, channels)
    }

    /** Channels of max amplitude per bucket, normalized to 0..1. Used to draw waveforms. */
    fun peaks(segment: AudioSegment, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val frames = segment.frameCount
        if (frames == 0) return out
        var maxAmp = 1
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
