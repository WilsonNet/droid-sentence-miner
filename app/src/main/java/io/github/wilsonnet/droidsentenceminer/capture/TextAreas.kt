package io.github.wilsonnet.droidsentenceminer.capture

/**
 * Auto OCR text areas as screen fractions. Kept per orientation, because a box drawn over a
 * portrait layout points at the wrong place once a game or video goes fullscreen landscape.
 *
 * Pure Kotlin, unit tested.
 */
data class TextArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): TextArea = TextArea(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    companion object {
        /** "l,t,r,b;l,t,r,b" — tolerant of junk so a bad pref never crashes capture. */
        fun decode(value: String?): List<TextArea> =
            value.orEmpty().split(';').mapNotNull { part ->
                val n = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (n.size != 4) return@mapNotNull null
                TextArea(n[0], n[1], n[2], n[3]).clamped().takeIf { it.width > 0.01f && it.height > 0.01f }
            }

        fun encode(areas: List<TextArea>): String =
            areas.joinToString(";") { "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, it.left, it.top, it.right, it.bottom) }

        /** Reading order for multi-box layouts (e.g. name box + dialogue box): top to bottom, then left to right. */
        fun ordered(areas: List<TextArea>): List<TextArea> =
            areas.sortedWith(compareBy({ (it.top * 20).toInt() }, { it.left }))
    }
}
