package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.sqrt

/**
 * Trims leading/trailing non-speech from a line's audio window (GSM's post-cut VAD step).
 *
 * Energy-based with an adaptive threshold so quiet BGM under the voice doesn't count as
 * speech, smoothed over ~160 ms so gaps between syllables don't delay the start. Tuned to
 * err on keeping audio: it only ever shrinks the clip, and returns it untouched when
 * nothing stands out.
 *
 * Pure Kotlin, unit tested.
 */
object VoiceTrimmer {

    data class Result(val segment: AudioSegment, val startMs: Long, val endMs: Long, val trimmed: Boolean)

    fun trim(
        segment: AudioSegment,
        windowMs: Int = 20,
        minSpeechMs: Int = 120,
        smoothMs: Int = 160,
        padStartMs: Int = 300,
        padEndMs: Int = 500,
        absoluteFloor: Double = 180.0,
        /** Share of the noise → peak gap a window must clear. */
        relativeThreshold: Double = 0.05,
    ): Result {
        val mono = AudioPcm.downmixToMono(segment)
        val windowFrames = (mono.sampleRate * windowMs / 1000).coerceAtLeast(1)
        val windows = mono.frameCount / windowFrames
        val untouched = Result(segment, 0, segment.durationMs, trimmed = false)
        if (windows < 3) return untouched

        val energies = DoubleArray(windows) { w ->
            var sum = 0.0
            val base = w * windowFrames
            for (i in base until base + windowFrames) {
                val v = mono.pcm[i].toDouble()
                sum += v * v
            }
            sqrt(sum / windowFrames)
        }
        val sorted = energies.sorted()
        val noise = sorted[(sorted.size * 0.2).toInt()]
        val peak = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        if (peak < absoluteFloor) return untouched
        val threshold = maxOf(absoluteFloor, noise * 2.0, noise + (peak - noise) * relativeThreshold)

        val smoothed = smooth(energies, (smoothMs / windowMs).coerceAtLeast(1))
        val minRun = (minSpeechMs / windowMs).coerceAtLeast(1)
        val first = firstRun(smoothed, threshold, minRun, forward = true) ?: return untouched
        val last = firstRun(smoothed, threshold, minRun, forward = false) ?: return untouched

        val startMs = (first.toLong() * windowMs - padStartMs).coerceAtLeast(0)
        val endMs = ((last + 1).toLong() * windowMs + padEndMs).coerceAtMost(segment.durationMs)
        if (endMs - startMs < 250) return untouched
        val sliced = AudioPcm.trim(segment, startMs, endMs) ?: return untouched
        return Result(AudioPcm.fadeEdges(sliced), startMs, endMs, trimmed = startMs > 0 || endMs < segment.durationMs)
    }

    /** Centered moving average. */
    private fun smooth(values: DoubleArray, width: Int): DoubleArray {
        if (width <= 1) return values
        val half = width / 2
        return DoubleArray(values.size) { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i - half + width).coerceAtMost(values.size)
            var sum = 0.0
            for (j in from until to) sum += values[j]
            sum / (to - from)
        }
    }

    /** Index of the outermost window that starts (or ends) a run of [minRun] loud windows. */
    private fun firstRun(energies: DoubleArray, threshold: Double, minRun: Int, forward: Boolean): Int? {
        val indices = if (forward) energies.indices else energies.indices.reversed()
        var run = 0
        var runStart = -1
        for (i in indices) {
            if (energies[i] >= threshold) {
                if (run == 0) runStart = i
                run++
                if (run >= minRun) return runStart
            } else {
                run = 0
            }
        }
        return null
    }
}
