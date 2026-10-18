package io.github.wilsonnet.droidsentenceminer.textlog

/**
 * Turns a stream of periodic OCR reads of one screen region into text-line events.
 *
 * A read only counts once it repeats ([stableReads] similar reads in a row), which filters
 * out fades, half-drawn typewriter text and OCR noise over moving video. Event times are
 * when the screen first stopped showing the previous state (even if that first read was
 * noisy), so voice clips aren't cut late.
 *
 * OCR of stylized fonts over moving video errs differently on each read, so every read of
 * the current line is kept and the line's text settles on their consensus (the read closest
 * to all the others) — [Event.Settled] — once enough reads agree and again when it ends.
 *
 * Pure Kotlin, unit tested.
 */
class TextLineTracker(
    private val stableReads: Int = 2,
    private val sameLineSimilarity: Double = 0.6,
) {
    sealed interface Event {
        data class Started(val text: String, val atMs: Long) : Event

        /** Typewriter growth: the line got longer. */
        data class Updated(val text: String) : Event

        /** Better reading of the same line, agreed on by several reads. */
        data class Settled(val text: String) : Event
        data class Ended(val atMs: Long) : Event
    }

    private var pendingText = ""
    private var pendingSince = 0L
    private var pendingCount = 0
    private val pendingReads = ArrayList<String>()

    /** Every read of the line on screen, for the consensus. */
    private val lineReads = ArrayList<String>()

    /** Text last reported for the current line (Started/Updated/Settled). */
    private var reported: String? = null

    /** First read that differed from the stable state; the true moment the screen changed. */
    private var changedAt: Long? = null

    /** Text of the line currently on screen, or null. */
    var current: String? = null
        private set

    fun reset() {
        pendingText = ""
        pendingSince = 0L
        pendingCount = 0
        pendingReads.clear()
        lineReads.clear()
        reported = null
        changedAt = null
        current = null
    }

    /** Adds an extra reading of the current line (e.g. a slower, more accurate OCR pass). */
    fun addReading(text: String) {
        val cleaned = TextSimilarity.clean(text)
        if (current != null && cleaned.isNotEmpty()) lineReads += cleaned
    }

    fun onRead(rawText: String, atMs: Long): List<Event> {
        val text = TextSimilarity.clean(rawText)
        val stable = current
        val differs = if (stable == null) text.isNotEmpty() else !sameLine(text)
        if (!differs) changedAt = null else if (changedAt == null) changedAt = atMs

        if (pendingCount > 0 && similar(text, pendingText)) {
            pendingCount++
            if (text.isNotEmpty()) pendingReads += text
            // Only typewriter growth replaces the reading; a longer *similar* read is usually noise.
            if (text.length > pendingText.length && grows(pendingText, text)) pendingText = text
        } else {
            pendingText = text
            pendingSince = atMs
            pendingCount = 1
            pendingReads.clear()
            if (text.isNotEmpty()) pendingReads += text
        }
        if (stable != null && text.isNotEmpty() && !differs) lineReads += text
        if (pendingCount < stableReads) return emptyList()

        val open = current
        val since = minOf(changedAt ?: pendingSince, pendingSince)
        return when {
            pendingText.isEmpty() -> {
                if (open == null) emptyList() else {
                    val events = settle(force = true) + Event.Ended(since)
                    endLine()
                    events
                }
            }
            open == null -> {
                startLine()
                listOf(Event.Started(pendingText, since))
            }
            !sameLine(pendingText) -> {
                val events = settle(force = true) + Event.Ended(since)
                endLine()
                startLine()
                events + Event.Started(pendingText, since)
            }
            // Typewriter text keeps growing; keep the fullest reading of the same line.
            pendingText != open && grows(open, pendingText) -> {
                current = pendingText
                reported = pendingText
                lineReads.clear()
                lineReads += pendingText
                listOf(Event.Updated(pendingText))
            }
            else -> settle(force = false)
        }
    }

    private fun startLine() {
        current = pendingText
        reported = pendingText
        changedAt = null
        lineReads.clear()
        lineReads += pendingReads
    }

    private fun endLine() {
        current = null
        reported = null
        changedAt = null
        lineReads.clear()
    }

    /** Reports the consensus once a few reads exist (and always on [force], i.e. line end). */
    private fun settle(force: Boolean): List<Event> {
        if (!force && lineReads.size !in SETTLE_AT) return emptyList()
        if (lineReads.size < 3) return emptyList()
        val best = TextSimilarity.consensus(lineReads)
        if (best == reported) return emptyList()
        reported = best
        return listOf(Event.Settled(best))
    }

    /** Same line as the one on screen: close to its text or to any earlier read of it. */
    private fun sameLine(text: String): Boolean {
        val open = current ?: return false
        return similar(text, open) || lineReads.any { similar(text, it) }
    }

    private fun grows(from: String, to: String): Boolean =
        to.length > from.length && to.startsWith(from.dropLast(1))

    private companion object {
        val SETTLE_AT = setOf(3, 5, 8, 13)
    }

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
    fun clean(text: String): String {
        val rows = text.lines().map { it.trim().replace(boxEdges, "").trim() }.filter { it.isNotEmpty() }
        val out = StringBuilder()
        for (row in rows) {
            // Japanese wraps without spaces; keep one between Latin words split across rows.
            if (out.isNotEmpty() && out.last().isLatinOrDigit() && row.first().isLatinOrDigit()) out.append(' ')
            out.append(row)
        }
        return out.toString()
    }

    private fun Char.isLatinOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * The read with the smallest total edit distance to all reads (a medoid). OCR errors are
     * mostly independent per read, so this lands on the reading most reads agree with.
     */
    fun consensus(reads: List<String>): String {
        val counts = reads.groupingBy { it }.eachCount()
        return counts.keys.minWith(
            compareBy<String> { candidate -> counts.entries.sumOf { (read, n) -> n * levenshtein(candidate, read) } }
                .thenByDescending { counts.getValue(it) },
        )
    }

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
