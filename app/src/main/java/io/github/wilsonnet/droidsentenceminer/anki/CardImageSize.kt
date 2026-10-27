package io.github.wilsonnet.droidsentenceminer.anki

import kotlin.math.roundToInt

/** Size of the picture put on a mined card: capped at a max height, aspect ratio kept. */
object CardImageSize {
    /** Scales (width, height) down so height ≤ [maxHeight]; never upscales. [maxHeight] ≤ 0 = unchanged. */
    fun fit(width: Int, height: Int, maxHeight: Int): Pair<Int, Int> {
        if (maxHeight <= 0 || height <= maxHeight || width <= 0 || height <= 0) return width to height
        val scale = maxHeight.toDouble() / height
        return (width * scale).roundToInt().coerceAtLeast(1) to maxHeight
    }
}
