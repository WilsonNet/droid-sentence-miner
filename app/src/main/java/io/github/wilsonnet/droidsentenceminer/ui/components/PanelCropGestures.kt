package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class CropDragMode {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    MOVE,
    DRAW_NEW,
}

internal data class CropImageMetrics(val left: Float, val top: Float, val width: Float, val height: Float)

internal data class CropDragStart(
    val downId: PointerId,
    val startPos: Offset,
    val startCrop: NormalizedCropRect,
    val mode: CropDragMode,
)

internal data class CropDragStep(val dx: Float, val dy: Float, val startNorm: Offset, val curNorm: Offset)

private val DRAG_HANDLE_MODES =
    mapOf(
        (-1).to(-1) to CropDragMode.TOP_LEFT,
        1.to(-1) to CropDragMode.TOP_RIGHT,
        (-1).to(1) to CropDragMode.BOTTOM_LEFT,
        1.to(1) to CropDragMode.BOTTOM_RIGHT,
        (-1).to(0) to CropDragMode.LEFT,
        1.to(0) to CropDragMode.RIGHT,
        0.to(-1) to CropDragMode.TOP,
        0.to(1) to CropDragMode.BOTTOM,
    )

internal suspend fun AwaitPointerEventScope.trackCropDrag(
    gesture: CropDragStart,
    metrics: CropImageMetrics,
    onCropChange: (NormalizedCropRect) -> Unit,
) {
    val startNorm =
        Offset(
            x = ((gesture.startPos.x - metrics.left) / metrics.width).coerceIn(minimumValue = 0f, maximumValue = 1f),
            y = ((gesture.startPos.y - metrics.top) / metrics.height).coerceIn(minimumValue = 0f, maximumValue = 1f),
        )
    var prevPos = gesture.startPos
    var current = gesture.startCrop
    var isFinished = false
    while (!isFinished) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == gesture.downId }
        if (change == null) {
            isFinished = true
        } else {
            change.consume()
            if (change.changedToUp()) {
                isFinished = true
            } else {
                val dx = (change.position.x - prevPos.x) / metrics.width
                val dy = (change.position.y - prevPos.y) / metrics.height
                prevPos = change.position
                val curNorm =
                    Offset(
                        x = ((change.position.x - metrics.left) / metrics.width).coerceIn(minimumValue = 0f, maximumValue = 1f),
                        y = ((change.position.y - metrics.top) / metrics.height).coerceIn(minimumValue = 0f, maximumValue = 1f),
                    )
                val step = CropDragStep(dx = dx, dy = dy, startNorm = startNorm, curNorm = curNorm)
                current = updateCropForDrag(crop = current, mode = gesture.mode, step = step)
                onCropChange(current)
            }
        }
    }
}

internal fun hitTestCropMode(
    pos: Offset,
    metrics: CropImageMetrics,
    crop: NormalizedCropRect,
    slop: Float,
): CropDragMode {
    val left = metrics.left + crop.left * metrics.width
    val top = metrics.top + crop.top * metrics.height
    val right = metrics.left + crop.right * metrics.width
    val bottom = metrics.top + crop.bottom * metrics.height
    val isInBounds = pos.x in left - slop..right + slop && pos.y in top - slop..bottom + slop
    if (!isInBounds) return CropDragMode.DRAW_NEW

    val hZone =
        when {
            abs(pos.x - left) <= slop -> -1
            abs(pos.x - right) <= slop -> 1
            else -> 0
        }
    val vZone =
        when {
            abs(pos.y - top) <= slop -> -1
            abs(pos.y - bottom) <= slop -> 1
            else -> 0
        }
    return DRAG_HANDLE_MODES[hZone to vZone] ?: CropDragMode.MOVE
}

private fun updateCropForDrag(crop: NormalizedCropRect, mode: CropDragMode, step: CropDragStep): NormalizedCropRect {
    val updated =
        when (mode) {
            CropDragMode.MOVE -> {
                moveCrop(rect = crop, dx = step.dx, dy = step.dy)
            }

            CropDragMode.DRAW_NEW -> {
                PanelCropMath.clamp(
                    NormalizedCropRect(
                        left = min(a = step.startNorm.x, b = step.curNorm.x),
                        top = min(a = step.startNorm.y, b = step.curNorm.y),
                        right = max(a = step.startNorm.x, b = step.curNorm.x),
                        bottom = max(a = step.startNorm.y, b = step.curNorm.y),
                    ),
                )
            }

            CropDragMode.TOP_LEFT,
            CropDragMode.TOP_RIGHT,
            CropDragMode.BOTTOM_LEFT,
            CropDragMode.BOTTOM_RIGHT,
            CropDragMode.LEFT,
            CropDragMode.RIGHT,
            CropDragMode.TOP,
            CropDragMode.BOTTOM,
            -> {
                resizeCrop(rect = crop, mode = mode, dx = step.dx, dy = step.dy)
            }
        }
    return updated
}

private fun moveCrop(rect: NormalizedCropRect, dx: Float, dy: Float): NormalizedCropRect {
    val w = rect.width
    val h = rect.height
    val nl = (rect.left + dx).coerceIn(minimumValue = 0f, maximumValue = 1f - w)
    val nt = (rect.top + dy).coerceIn(minimumValue = 0f, maximumValue = 1f - h)
    return NormalizedCropRect(left = nl, top = nt, right = nl + w, bottom = nt + h)
}

private fun resizeCrop(rect: NormalizedCropRect, mode: CropDragMode, dx: Float, dy: Float): NormalizedCropRect {
    val minF = PanelCropMath.MIN_FRACTION
    var l = rect.left
    var t = rect.top
    var r = rect.right
    var b = rect.bottom
    when (mode) {
        CropDragMode.TOP_LEFT -> {
            l = (l + dx).coerceIn(minimumValue = 0f, maximumValue = r - minF)
            t = (t + dy).coerceIn(minimumValue = 0f, maximumValue = b - minF)
        }

        CropDragMode.TOP_RIGHT -> {
            r = (r + dx).coerceIn(minimumValue = l + minF, maximumValue = 1f)
            t = (t + dy).coerceIn(minimumValue = 0f, maximumValue = b - minF)
        }

        CropDragMode.BOTTOM_LEFT -> {
            l = (l + dx).coerceIn(minimumValue = 0f, maximumValue = r - minF)
            b = (b + dy).coerceIn(minimumValue = t + minF, maximumValue = 1f)
        }

        CropDragMode.BOTTOM_RIGHT -> {
            r = (r + dx).coerceIn(minimumValue = l + minF, maximumValue = 1f)
            b = (b + dy).coerceIn(minimumValue = t + minF, maximumValue = 1f)
        }

        CropDragMode.LEFT -> {
            l = (l + dx).coerceIn(minimumValue = 0f, maximumValue = r - minF)
        }

        CropDragMode.RIGHT -> {
            r = (r + dx).coerceIn(minimumValue = l + minF, maximumValue = 1f)
        }

        CropDragMode.TOP -> {
            t = (t + dy).coerceIn(minimumValue = 0f, maximumValue = b - minF)
        }

        CropDragMode.BOTTOM -> {
            b = (b + dy).coerceIn(minimumValue = t + minF, maximumValue = 1f)
        }

        CropDragMode.MOVE,
        CropDragMode.DRAW_NEW,
        -> {
            // Handled above before resize branches.
        }
    }
    return NormalizedCropRect(left = l, top = t, right = r, bottom = b)
}
