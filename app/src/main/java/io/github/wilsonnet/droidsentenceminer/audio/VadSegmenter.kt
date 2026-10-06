package io.github.wilsonnet.droidsentenceminer.audio

import kotlin.math.max

/**
 * Energy-based voice activity detection that turns a continuous PCM stream into
 * sentence-sized segments. Mirrors the role of GSM's VAD audio trimming.
 *
 * Pure Kotlin, unit tested.
 */
class VadSegmenter(
    private val sampleRate: Int,
    private val channels: Int,
    /** RMS threshold below which audio is considered silence. */
    private val silenceThreshold: Double = 260.0,
    /** How much silence ends a segment. */
    private val endSilenceMs: Int = 700,
    /** How much speech begins a segment. */
    private val startSpeechMs: Int = 120,
    /** Pre-roll kept before detected speech. */
    private val preRollMs: Int = 450,
    /** Hard cap for one segment. */
    private val maxSegmentMs: Int = 20_000,
) {
    private val framesPerMs = sampleRate / 1000.0

    private var preRoll = ArrayDeque<Short>()
    private var current = ArrayList<Short>()
    private var currentSpeechFrames = 0
    private var currentSilenceFrames = 0
    private var inSpeech = false

    private val preRollFrames = (preRollMs * framesPerMs).toInt()
    private val startSpeechFrames = (startSpeechMs * framesPerMs).toInt()
    private val endSilenceFrames = (endSilenceMs * framesPerMs).toInt()
    private val maxSegmentFrames = (maxSegmentMs * framesPerMs).toInt()
    private val analysisWindowFrames = (20 * framesPerMs).toInt().coerceAtLeast(1)

    private val recent = ArrayDeque<AudioSegment>()

    /** Feeds a chunk of interleaved PCM and returns any segments completed by it. */
    fun process(chunk: ShortArray): List<AudioSegment> {
        val completed = ArrayList<AudioSegment>(1)
        val totalFrames = chunk.size / channels
        var frame = 0
        while (frame < totalFrames) {
            val windowEnd = minOf(totalFrames, frame + analysisWindowFrames)

            // Windowed RMS: robust against zero-crossings of periodic waveforms.
            var sumSquares = 0.0
            var samples = 0
            for (f in frame until windowEnd) {
                val base = f * channels
                for (ch in 0 until channels) {
                    val value = chunk[base + ch].toDouble()
                    sumSquares += value * value
                    samples++
                }
            }
            val rms = if (samples == 0) 0.0 else kotlin.math.sqrt(sumSquares / samples)
            val isVoice = rms > silenceThreshold

            for (f in frame until windowEnd) {
                val base = f * channels
                if (!inSpeech) {
                    appendPreRoll(base, chunk)
                    if (isVoice) {
                        currentSpeechFrames++
                        currentSilenceFrames = 0
                        if (currentSpeechFrames >= startSpeechFrames) {
                            inSpeech = true
                            current = ArrayList(preRoll)
                            preRoll.clear()
                        }
                    } else {
                        currentSpeechFrames = 0
                    }
                } else {
                    for (ch in 0 until channels) current.add(chunk[base + ch])
                    val framesSoFar = current.size / channels
                    if (framesSoFar >= maxSegmentFrames) {
                        finishSegment(current)?.let { completed += it }
                        current = ArrayList()
                        inSpeech = false
                        currentSpeechFrames = 0
                        currentSilenceFrames = 0
                    } else if (isVoice) {
                        currentSilenceFrames = 0
                    } else {
                        currentSilenceFrames++
                        if (currentSilenceFrames >= endSilenceFrames) {
                            finishSegment(current)?.let { completed += it }
                            current = ArrayList()
                            inSpeech = false
                            currentSpeechFrames = 0
                            currentSilenceFrames = 0
                        }
                    }
                }
            }
            frame = windowEnd
        }
        return completed
    }

    /** The active segment so far (only while speech is in progress), including pre-roll. */
    fun currentSegment(): AudioSegment? {
        if (!inSpeech) return null
        if (current.size / channels < sampleRate / 4) return null
        return AudioSegment(current.toShortArray(), sampleRate, channels)
    }

    /** Most recent completed segment (or the active one if it is long enough). */
    fun latestSegment(): AudioSegment? =
        recent.lastOrNull() ?: currentSegment()

    fun recentSegments(): List<AudioSegment> = recent.toList()

    fun reset() {
        preRoll.clear()
        current = ArrayList()
        recent.clear()
        inSpeech = false
        currentSpeechFrames = 0
        currentSilenceFrames = 0
    }

    private fun appendPreRoll(base: Int, chunk: ShortArray) {
        for (ch in 0 until channels) preRoll.addLast(chunk[base + ch])
        while (preRoll.size > preRollFrames * channels) preRoll.removeFirst()
    }

    private fun finishSegment(frames: List<Short>): AudioSegment? {
        if (frames.size / channels < sampleRate / 4) return null
        val segment = AudioSegment(frames.toShortArray(), sampleRate, channels)
        recent.addLast(segment)
        while (recent.size > MAX_KEPT_SEGMENTS) recent.removeFirst()
        return segment
    }

    companion object {
        const val MAX_KEPT_SEGMENTS = 4
    }
}
