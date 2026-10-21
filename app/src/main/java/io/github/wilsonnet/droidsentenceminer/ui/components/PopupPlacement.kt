package io.github.wilsonnet.droidsentenceminer.ui.components

import kotlin.math.max
import kotlin.math.min

/**
 * Where the lookup popup goes, the way Popper.js does it: try the preferred side (below the word),
 * flip to the opposite side when it doesn't have room, then try beside the word; size the popup to
 * the room on the chosen side ("size" modifier) and shift it along that side to stay on screen.
 * A fixed fraction of the screen height fails in landscape, where the screen is short but the room
 * above a bottom subtitle is most of it.
 *
 * All values in pixels. Pure Kotlin, unit tested.
 */
object PopupPlacement {

    enum class Side { BELOW, ABOVE, LEFT, RIGHT }

    data class Plan(val side: Side, val maxHeight: Float)

    /**
     * [preferredHeight]: room that counts as enough for a definition; [width]: the popup's width.
     * [maxFraction] keeps a popup on a tall portrait screen from hiding all of it.
     */
    fun plan(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float,
        width: Float,
        preferredHeight: Float,
        gap: Float,
        maxFraction: Float = 0.6f,
    ): Plan {
        // Only a tall portrait screen needs a cap; in landscape the room is already short.
        val cap = if (screenHeight > screenWidth) max(preferredHeight, screenHeight * maxFraction) else Float.MAX_VALUE
        val below = screenHeight - bottom - 2 * gap
        val above = top - 2 * gap
        val besideRoom = screenHeight - 2 * gap
        val leftRoom = left - 2 * gap
        val rightRoom = screenWidth - right - 2 * gap
        return when {
            below >= preferredHeight -> Plan(Side.BELOW, min(below, cap))
            above >= preferredHeight -> Plan(Side.ABOVE, min(above, cap))
            rightRoom >= width && rightRoom >= leftRoom -> Plan(Side.RIGHT, min(besideRoom, cap))
            leftRoom >= width -> Plan(Side.LEFT, min(besideRoom, cap))
            below >= above -> Plan(Side.BELOW, max(below, 0f))
            else -> Plan(Side.ABOVE, max(above, 0f))
        }
    }

    /** Top-left corner of a measured [w]×[h] popup on [side] of the anchor, kept on screen. */
    fun position(
        side: Side,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Float,
        screenHeight: Float,
        w: Float,
        h: Float,
        gap: Float,
    ): Pair<Float, Float> {
        fun clampX(x: Float) = x.coerceIn(gap, max(gap, screenWidth - w - gap))
        fun clampY(y: Float) = y.coerceIn(gap, max(gap, screenHeight - h - gap))
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return when (side) {
            Side.BELOW -> clampX(centerX - w / 2f) to clampY(bottom + gap)
            Side.ABOVE -> clampX(centerX - w / 2f) to clampY(top - gap - h)
            Side.RIGHT -> clampX(right + gap) to clampY(centerY - h / 2f)
            Side.LEFT -> clampX(left - gap - w) to clampY(centerY - h / 2f)
        }
    }
}
