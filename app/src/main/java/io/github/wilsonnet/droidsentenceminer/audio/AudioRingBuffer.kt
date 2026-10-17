package io.github.wilsonnet.droidsentenceminer.audio

/**
 * Rolling replay buffer of mono PCM, the Android stand-in for GSM's OBS replay buffer.
 *
 * Every sample is addressable by wall-clock time, so a text line's timestamps can be
 * turned into its voice clip after the fact. Gaps in delivery (AudioRecord stalls, nothing
 * playing on some devices) are filled with silence to keep the timeline aligned.
 *
 * Pure Kotlin, unit tested.
 */
class AudioRingBuffer(
    val sampleRate: Int,
    val capacitySeconds: Int,
) {
    private val capacity = sampleRate * capacitySeconds
    private val buffer = ShortArray(capacity)

    /** Total samples ever written; the ring index is `written % capacity`. */
    private var written = 0L

    /** Wall-clock time of the sample just after the newest one. */
    private var endAtMs = 0L

    @Synchronized
    fun write(chunk: ShortArray, length: Int = chunk.size, nowMs: Long) {
        if (length <= 0) return
        if (written > 0) {
            // Pad silence when real time ran ahead of the samples we received.
            val expectedStart = endAtMs
            val actualStart = nowMs - length * 1000L / sampleRate
            val gapMs = actualStart - expectedStart
            if (gapMs > MAX_JITTER_MS) {
                val gapSamples = (gapMs * sampleRate / 1000L).coerceAtMost(capacity.toLong()).toInt()
                appendSilence(gapSamples)
            }
        }
        for (i in 0 until length) {
            buffer[(written % capacity).toInt()] = chunk[i]
            written++
        }
        endAtMs = nowMs
    }

    private fun appendSilence(samples: Int) {
        for (i in 0 until samples) {
            buffer[(written % capacity).toInt()] = 0
            written++
        }
    }

    @Synchronized
    fun clear() {
        written = 0
        endAtMs = 0
    }

    /** Oldest wall-clock time still in the buffer, or null when empty. */
    val oldestMs: Long?
        @Synchronized get() = if (written == 0L) null else endAtMs - availableSamples() * 1000L / sampleRate

    val newestMs: Long?
        @Synchronized get() = if (written == 0L) null else endAtMs

    val bufferedMs: Long
        @Synchronized get() = availableSamples() * 1000L / sampleRate

    private fun availableSamples(): Long = minOf(written, capacity.toLong())

    /** Whether any part of [startMs, endMs) is still buffered. */
    @Synchronized
    fun covers(startMs: Long, endMs: Long): Boolean {
        val oldest = oldestMs ?: return false
        return endMs > oldest && startMs < endAtMs
    }

    /** Copies [startMs, endMs) clamped to what is buffered; null when nothing overlaps. */
    @Synchronized
    fun slice(startMs: Long, endMs: Long): AudioSegment? {
        if (written == 0L || endMs <= startMs) return null
        val available = availableSamples()
        val firstIndex = written - available
        fun indexAt(ms: Long): Long = written - (endAtMs - ms) * sampleRate / 1000L
        val from = indexAt(startMs).coerceIn(firstIndex, written)
        val to = indexAt(endMs).coerceIn(firstIndex, written)
        if (to <= from) return null
        val out = ShortArray((to - from).toInt())
        for (i in out.indices) out[i] = buffer[((from + i) % capacity).toInt()]
        return AudioSegment(out, sampleRate, 1)
    }

    companion object {
        private const val MAX_JITTER_MS = 250L
    }
}
