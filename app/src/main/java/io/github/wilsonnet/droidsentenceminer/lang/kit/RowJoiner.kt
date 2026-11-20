package io.github.wilsonnet.droidsentenceminer.lang.kit

/** Joins wrapped OCR rows of a spaced language, deciding per language what a line-end hyphen means. */
object RowJoiner {
    /** What to do with a hyphen at the end of a row. */
    enum class Hyphen {
        /** A word broken across rows: drop the hyphen (Wör- terbuch → Wörterbuch). */
        JOIN,

        /** Part of the word: keep it, no space (Nord- Ostsee → Nord-Ostsee, dit- il → dit-il). */
        KEEP,

        /** An elision before a conjunction: keep it, then a space (Haus- und Gartenbau). */
        KEEP_SPACED,
    }

    /** [previous] and [next] joined with a space, or per [onHyphen] (given the next row) when [previous] ends in '-'. */
    fun join(previous: String, next: String, onHyphen: (String) -> Hyphen): String {
        val joined =
            when {
                previous.isEmpty() -> next
                next.isEmpty() -> previous
                previous.last().isWhitespace() -> previous + next
                previous.last() != '-' -> "$previous $next"
                else -> hyphenated(previous = previous, next = next, hyphen = onHyphen(next))
            }
        return joined
    }

    private fun hyphenated(previous: String, next: String, hyphen: Hyphen): String {
        val joined =
            when (hyphen) {
                Hyphen.JOIN -> previous.dropLast(1) + next
                Hyphen.KEEP -> previous + next
                Hyphen.KEEP_SPACED -> "$previous $next"
            }
        return joined
    }
}
