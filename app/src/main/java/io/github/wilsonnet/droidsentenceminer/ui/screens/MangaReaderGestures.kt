package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.unit.IntSize
import io.github.wilsonnet.droidsentenceminer.manga.MangaBlock
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageOcr
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import kotlin.math.max
import kotlin.math.min

private const val MAX_PAGE_ZOOM = 5f
private const val ZOOM_ACTIVE_EPS = 1.02f

internal enum class PageGestureOutcome {
    LONG_PRESS,
    TAP,
    DRAG_OR_MULTI,
}

internal data class PageCoords(val fit: Float, val fitOffset: Offset, val ocrToBmpX: Float, val ocrToBmpY: Float)

internal data class SlotGestureInput(
    val downId: PointerId,
    val downPos: Offset,
    val touchSlop: Float,
    val viewport: IntSize,
    val currentZoom: Float,
    val currentPan: Offset,
)

internal data class TextBoxGestureContext(
    val down: PointerInputChange,
    val initialHit: Pair<Int, Int>,
    val blocks: List<MangaBlock>,
    val coords: PageCoords,
    val windowOrigin: Offset,
    val zoom: Float,
    val hitSlack: Float,
    val isEditMode: Boolean,
    val haptics: HapticFeedback,
    val longPressTimeoutMs: Long,
    val touchSlop: Float,
)

private data class LongPressDragConfig(
    val down: PointerInputChange,
    val blockIndex: Int,
    val anchorChar: Int,
    val blocks: List<MangaBlock>,
    val coords: PageCoords,
    val hitSlack: Float,
    val isEditMode: Boolean,
)

internal fun computePageCoords(surfaceSize: IntSize, bitmap: Bitmap, ocr: MangaPageOcr?): PageCoords {
    val fit =
        if (surfaceSize.width > 0 && surfaceSize.height > 0) {
            min(a = surfaceSize.width.toFloat() / bitmap.width, b = surfaceSize.height.toFloat() / bitmap.height)
        } else {
            1f
        }
    val fitOffset =
        Offset(
            x = (surfaceSize.width - bitmap.width * fit) / 2f,
            y = (surfaceSize.height - bitmap.height * fit) / 2f,
        )
    val ocrWidth = ocr?.imgWidth
    val ocrW = if (ocrWidth != null && ocrWidth > 0) ocrWidth else bitmap.width
    val ocrHeight = ocr?.imgHeight
    val ocrH = if (ocrHeight != null && ocrHeight > 0) ocrHeight else bitmap.height
    return PageCoords(
        fit = fit,
        fitOffset = fitOffset,
        ocrToBmpX = bitmap.width.toFloat() / ocrW,
        ocrToBmpY = bitmap.height.toFloat() / ocrH,
    )
}

internal fun PageCoords.screenToOcr(localPos: Offset): Offset {
    val bmpPos = (localPos - fitOffset) / fit
    return Offset(x = bmpPos.x / ocrToBmpX, y = bmpPos.y / ocrToBmpY)
}

internal fun PageCoords.ocrBoxToLocalRect(box: BoundingBox): Rect {
    val rect =
        Rect(
            left = fitOffset.x + box.left * ocrToBmpX * fit,
            top = fitOffset.y + box.top * ocrToBmpY * fit,
            right = fitOffset.x + box.right * ocrToBmpX * fit,
            bottom = fitOffset.y + box.bottom * ocrToBmpY * fit,
        )
    return rect
}

internal fun PageCoords.anchorForRange(block: MangaBlock, range: IntRange, windowOrigin: Offset, zoom: Float): Rect {
    val union =
        range
            .mapNotNull { block.glyphs.getOrNull(it)?.toBoundingBox() }
            .reduceOrNull(BoundingBox::union)
            ?: block.boundingBox
    val localRect = ocrBoxToLocalRect(union)
    return Rect(
        left = windowOrigin.x + localRect.left * zoom,
        top = windowOrigin.y + localRect.top * zoom,
        right = windowOrigin.x + localRect.right * zoom,
        bottom = windowOrigin.y + localRect.bottom * zoom,
    )
}

internal suspend fun AwaitPointerEventScope.handleSlotGestureLoop(
    input: SlotGestureInput,
    onUpdateTransform: (Float, Offset) -> Unit,
): PointerInputChange? {
    var zoom = input.currentZoom
    var pan = input.currentPan
    var isMultiTouch = false
    var isMoved = false

    while (true) {
        val event = awaitPointerEvent()
        isMultiTouch = isMultiTouch || event.changes.count { it.pressed } > 1
        if (event.changes.none { it.pressed }) {
            return event.changes.firstOrNull { it.id == input.downId }?.takeIf { !isMultiTouch && !isMoved }
        }
        if (isMultiTouch || zoom > ZOOM_ACTIVE_EPS) {
            val updated =
                applyTransformStep(
                    event = event,
                    viewport = input.viewport,
                    zoom = zoom,
                    pan = pan,
                    touchSlop = input.touchSlop,
                )
            zoom = updated.first
            pan = updated.second
            isMoved = isMoved || updated.third
            onUpdateTransform(zoom, pan)
        } else {
            val change = event.changes.firstOrNull { it.id == input.downId }
            val isDragged = change != null && (change.position - input.downPos).getDistance() > input.touchSlop
            isMoved = isMoved || isDragged
        }
    }
}

private fun applyTransformStep(
    event: PointerEvent,
    viewport: IntSize,
    zoom: Float,
    pan: Offset,
    touchSlop: Float,
): Triple<Float, Offset, Boolean> {
    val factor = event.calculateZoom()
    val centroid = event.calculateCentroid(useCurrent = true)
    val panDelta = event.calculatePan()
    val isMoved = panDelta.getDistance() > touchSlop || factor != 1f
    val newZoom = (zoom * factor).coerceIn(minimumValue = 1f, maximumValue = MAX_PAGE_ZOOM)
    val applied = newZoom / zoom
    val movedPan = if (centroid == Offset.Unspecified) pan else centroid - (centroid - pan) * applied
    val maxW = viewport.width.toFloat()
    val maxH = viewport.height.toFloat()
    val clampedPan =
        Offset(
            x = (movedPan.x + panDelta.x).coerceIn(minimumValue = maxW - maxW * newZoom, maximumValue = 0f),
            y = (movedPan.y + panDelta.y).coerceIn(minimumValue = maxH - maxH * newZoom, maximumValue = 0f),
        )
    event.changes.forEach { it.consume() }
    return Triple(newZoom, clampedPan, isMoved)
}

internal suspend fun AwaitPointerEventScope.awaitSingleTapUp(
    downId: PointerId,
    downPos: Offset,
    touchSlop: Float,
): PointerInputChange? {
    var isMoved = false
    var result: PointerInputChange? = null
    var isDone = false
    while (!isDone) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == downId }
        if (change == null) {
            isDone = true
        } else {
            if ((change.position - downPos).getDistance() > touchSlop) isMoved = true
            if (change.changedToUp()) {
                if (!isMoved) result = change
                isDone = true
            }
        }
    }
    return result
}

internal suspend fun AwaitPointerEventScope.processTextBoxGesture(
    ctx: TextBoxGestureContext,
    onDragRangeChange: (Pair<Int, IntRange>?) -> Unit,
    onTapChar: (blockIndex: Int, charIndex: Int, anchor: Rect) -> Unit,
    onSelectRange: (blockIndex: Int, range: IntRange, anchor: Rect) -> Unit,
) {
    val outcome = awaitTapOrLongPress(ctx.down, ctx.longPressTimeoutMs, ctx.touchSlop)
    val (bIdx, cIdx) = ctx.initialHit
    val block = ctx.blocks.getOrNull(bIdx) ?: return
    when (outcome) {
        PageGestureOutcome.TAP -> {
            ctx.down.consume()
            val anchor =
                ctx.coords.anchorForRange(
                    block = block,
                    range = cIdx..cIdx,
                    windowOrigin = ctx.windowOrigin,
                    zoom = ctx.zoom,
                )
            onTapChar(bIdx, cIdx, anchor)
        }

        PageGestureOutcome.LONG_PRESS -> {
            ctx.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val dragConfig =
                LongPressDragConfig(
                    down = ctx.down,
                    blockIndex = bIdx,
                    anchorChar = cIdx,
                    blocks = ctx.blocks,
                    coords = ctx.coords,
                    hitSlack = ctx.hitSlack,
                    isEditMode = ctx.isEditMode,
                )
            val range = collectLongPressRange(config = dragConfig, onDragRangeChange = onDragRangeChange)
            val anchor =
                ctx.coords.anchorForRange(
                    block = block,
                    range = range,
                    windowOrigin = ctx.windowOrigin,
                    zoom = ctx.zoom,
                )
            onSelectRange(bIdx, range, anchor)
        }

        PageGestureOutcome.DRAG_OR_MULTI -> {
            // Drag/pinch handled by the viewport gesture detector.
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitTapOrLongPress(
    down: PointerInputChange,
    longPressTimeoutMs: Long,
    touchSlop: Float,
): PageGestureOutcome {
    var outcome = PageGestureOutcome.LONG_PRESS
    withTimeoutOrNull(longPressTimeoutMs) {
        var isResolved = false
        while (!isResolved) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            when {
                event.changes.count { it.pressed } > 1 || change == null -> {
                    outcome = PageGestureOutcome.DRAG_OR_MULTI
                    isResolved = true
                }

                change.changedToUp() -> {
                    change.consume()
                    outcome = PageGestureOutcome.TAP
                    isResolved = true
                }

                (change.position - down.position).getDistance() > touchSlop -> {
                    outcome = PageGestureOutcome.DRAG_OR_MULTI
                    isResolved = true
                }
            }
        }
    }
    return outcome
}

private suspend fun AwaitPointerEventScope.collectLongPressRange(
    config: LongPressDragConfig,
    onDragRangeChange: (Pair<Int, IntRange>?) -> Unit,
): IntRange {
    var currentRange = config.anchorChar..config.anchorChar
    onDragRangeChange(config.blockIndex to currentRange)
    var isReleased = false
    while (!isReleased) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == config.down.id }
        if (change == null) {
            isReleased = true
        } else {
            change.consume()
            if (change.changedToUp()) {
                isReleased = true
            } else {
                val hit =
                    hitGlyph(
                        blocks = config.blocks,
                        coords = config.coords,
                        localPos = change.position,
                        slack = config.hitSlack,
                        editMode = config.isEditMode,
                    )
                if (hit != null && hit.first == config.blockIndex) {
                    currentRange = min(a = config.anchorChar, b = hit.second)..max(a = config.anchorChar, b = hit.second)
                    onDragRangeChange(config.blockIndex to currentRange)
                }
            }
        }
    }
    onDragRangeChange(null)
    return currentRange
}
