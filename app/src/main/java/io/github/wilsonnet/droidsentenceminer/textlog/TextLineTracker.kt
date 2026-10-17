package io.github.wilsonnet.droidsentenceminer.textlog

/**
 * Turns a stream of periodic OCR reads of one screen region into text-line events.
 *
 * A read only counts once it repeats ([stableReads] similar reads in a row), which filters
 * out fades, half-drawn typewriter text and OCR noise over moving video. Event times are
 * when the screen first stopped showing the previous state (even if that first read was
 * noisy), so voice clips aren't cut late.
 *
 * Pure Kotlin, unit tested.
 */
class TextLineTracker(
    private val stableReads: Int = 2,
    private val sameLineSimilarity: Double = 0.7,
) {
    sealed interface Event {
        data class Started(val text: String, val atMs: Long) : Event
        data class Updated(val text: String) : Event
        data class Ended(val atMs: Long) : Event
    }

    private var pendingText = ""
    private var pendingSince = 0L
    private var pendingCount = 0

    /** First read that differed from the stable state; the true moment the screen changed. */
    private var changedAt: Long? = null

    /** Text of the line currently on screen, or null. */
    var current: String? = null
        private set

    fun reset() {
        pendingText = ""
        pendingSince = 0L
        pendingCount = 0
        changedAt = null
        current = null
    }

    fun onRead(rawText: String, atMs: Long): List<Event> {
        val text = TextSimilarity.clean(rawText)
        val stable = current
        val differs = if (stable == null) text.isNotEmpty() else !similar(text, stable)
        if (!differs) changedAt = null else if (changedAt == null) changedAt = atMs

        if (pendingCount > 0 && similar(text, pendingText)) {
            pendingCount++
            // Only typewriter growth replaces the reading; a longer *similar* read is usually noise.
            if (text.length > pendingText.length && grows(pendingText, text)) pendingText = text
        } else {
            pendingText = text
            pendingSince = atMs
            pendingCount = 1
        }
        if (pendingCount < stableReads) return emptyList()

        val open = current
        val since = minOf(changedAt ?: pendingSince, pendingSince)
        return when {
            pendingText.isEmpty() -> {
                if (open == null) emptyList() else {
                    current = null
                    changedAt = null
                    listOf(Event.Ended(since))
                }
            }
            open == null -> {
                current = pendingText
                changedAt = null
                listOf(Event.Started(pendingText, since))
            }
            !similar(open, pendingText) -> {
                current = pendingText
                changedAt = null
                listOf(Event.Ended(since), Event.Started(pendingText, since))
            }
            // Typewriter text keeps growing; keep the fullest reading of the same line.
            pendingText != open && grows(open, pendingText) -> {
                current = pendingText
                listOf(Event.Updated(pendingText))
            }
            else -> emptyList()
        }
    }

    private fun grows(from: String, to: String): Boolean =
        to.length > from.length && to.startsWith(from.dropLast(1))

    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty()
        if (a == b) return true
        // A growing typewriter line is the same line.
        if (b.startsWith(a) || a.startsWith(b)) return true
        return TextSimilarity.ratio(a, b) >= sameLineSimilarity
    }
}

object TextSimilarity {

    private val ignorable = Regex("[\\s\\p{Punct}、。・「」『』（）()【】…‥！？!?ー〜~♪]")

    private val boxEdges = Regex("^[|｜│¦\\[\\]]+|[|｜│¦\\[\\]]+$")

    /** Normalizes OCR output for comparison and storage (joins lines, drops caption-box edges). */
    fun clean(text: String): String =
        text.lines().joinToString("") { it.trim().replace(boxEdges, "").trim() }.trim()

    /** 1.0 = identical (ignoring spacing/punctuation), 0.0 = nothing in common. */
    fun ratio(a: String, b: String): Double {
        val x = a.replace(ignorable, "")
        val y = b.replace(ignorable, "")
        if (x.isEmpty() && y.isEmpty()) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val distance = levenshtein(x, y)
        return 1.0 - distance.toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

/** GSM-style clip bounds: from the line's first appearance to when it was replaced. */
object ClipWindow {

    data class Bounds(val startMs: Long, val endMs: Long)

    fun compute(
        lineStartMs: Long,
        lineEndMs: Long?,
        nextStartMs: Long?,
        nowMs: Long,
        beforeMs: Long,
        afterMs: Long,
        maxLengthMs: Long = 30_000,
    ): Bounds {
        val end = listOfNotNull(lineEndMs, nextStartMs).minOrNull() ?: nowMs
        val start = lineStartMs - beforeMs
        val clipEnd = minOf(end + afterMs, nowMs, start + maxLengthMs)
        return Bounds(start, maxOf(clipEnd, start))
    }
}
