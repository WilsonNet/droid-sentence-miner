package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.manga.MangaBlock
import io.github.wilsonnet.droidsentenceminer.manga.MangaOverlayStyle
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageFilter
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageOcr
import io.github.wilsonnet.droidsentenceminer.manga.MangaSpreadSlot
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
private data class BlockList(val items: List<MangaBlock>)

@Immutable
private data class LineList(val items: List<String>)

private const val DEFAULT_PAGE_ASPECT = 0.7f
private const val HIT_SLACK_DP = 14
private const val DETAIL_ZOOM = 1.15f
private const val EDIT_BORDER_ALPHA = 0.7f
private const val NORMAL_BORDER_ALPHA = 0.28f
private const val HIGHLIGHT_ALPHA = 0.36f
private const val DARK_BOX_BG = 0xFA181A1EL
private const val LIGHT_BOX_BG = 0xFAFFFFFFL
private const val DARK_BOX_FG = 0xFFF3F4F6L
private const val LIGHT_BOX_FG = 0xFF111827L
private const val DARK_BOX_BORDER = 0xFF4B5563L
private const val LIGHT_BOX_BORDER = 0xFFD1D5DBL
private const val MIN_READABLE_DIM = 12f
private const val VERTICAL_COL_FIT = 0.74f
private const val VERTICAL_ROW_FIT = 0.68f
private const val HORIZONTAL_ROW_FIT = 0.74f
private const val HORIZONTAL_COL_FIT = 0.86f
private const val MIN_READABLE_FONT_PX = 8f
private const val MAX_READABLE_FONT_PX = 40f
private const val READABLE_BOX_PAD_DP = 1
private const val READABLE_PAD_PX_FALLBACK = 4f
private const val VERTICAL_LINE_HEIGHT_SCALE = 1.0f
private const val HORIZONTAL_LINE_HEIGHT_SCALE = 1.1f

/** Active word selection highlighted on a manga page. */
data class MangaPick(
    /** Zero-based page index where the word resides. */
    val pageIndex: Int,
    /** Block index inside the page's OCR list. */
    val blockIndex: Int,
    /** Selected character range within `block.text`. */
    val range: IntRange,
    /** Window-coordinate bounding box used to anchor the dictionary popup. */
    val anchor: Rect,
)

/** Immutable state snapshot for a single rendered manga page. */
@Immutable
data class MangaPageRenderState(
    /** Page index inside the volume. */
    val pageIndex: Int,
    /** Decoded bitmap if loaded. */
    val bitmap: Bitmap?,
    /** Cached `.mokuro` or on-device OCR result if ready. */
    val ocr: MangaPageOcr?,
    /** Currently active word selection on this page, if any. */
    val picked: MangaPick?,
    /** Display filter for the artwork. */
    val filter: MangaPageFilter = MangaPageFilter.OFF,
    /** Whether [filter] runs the AI 2x upscaler on small pages. */
    val isAiUpscale: Boolean = false,
)

/** Single-page or 2-page spread slot with pinch-to-zoom, pan, and tap-zone handling. */
@Suppress("FunctionName")
@Composable
fun PagedMangaSlotView(
    slot: MangaSpreadSlot,
    leftState: MangaPageRenderState?,
    rightState: MangaPageRenderState?,
    overlayStyle: MangaOverlayStyle,
    showBorders: Boolean,
    editMode: Boolean,
    onZoomChange: (Float) -> Unit,
    onTapChar: (pageIndex: Int, blockIndex: Int, charIndex: Int, anchor: Rect) -> Unit,
    onSelectRange: (pageIndex: Int, blockIndex: Int, range: IntRange, anchor: Rect) -> Unit,
    onTapEmpty: (normX: Float, normY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(slot) { mutableFloatStateOf(1f) }
    var pan by remember(slot) { mutableStateOf(Offset.Zero) }
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)

    LaunchedEffect(zoom) { currentOnZoomChange(zoom) }

    val slotModifier =
        modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(slot, viewport) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val updated =
                    handleSlotGestureLoop(
                        input =
                        SlotGestureInput(
                            downId = down.id,
                            downPos = down.position,
                            touchSlop = viewConfiguration.touchSlop,
                            viewport = viewport,
                            currentZoom = zoom,
                            currentPan = pan,
                        ),
                        onUpdateTransform = { newZ, newP ->
                            zoom = newZ
                            pan = newP
                        },
                    )
                val tapChange = updated
                val isTapReady = tapChange != null && !tapChange.isConsumed && viewport.width > 0
                if (isTapReady && viewport.height > 0) {
                    onTapEmpty(tapChange.position.x / viewport.width, tapChange.position.y / viewport.height)
                }
            }
        }
    Box(modifier = slotModifier) {
        SpreadRow(
            leftState = leftState,
            rightState = rightState,
            overlayStyle = overlayStyle,
            showBorders = showBorders,
            editMode = editMode,
            zoom = zoom,
            pan = pan,
            onTapChar = onTapChar,
            onSelectRange = onSelectRange,
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun SpreadRow(
    leftState: MangaPageRenderState?,
    rightState: MangaPageRenderState?,
    overlayStyle: MangaOverlayStyle,
    showBorders: Boolean,
    editMode: Boolean,
    zoom: Float,
    pan: Offset,
    onTapChar: (pageIndex: Int, blockIndex: Int, charIndex: Int, anchor: Rect) -> Unit,
    onSelectRange: (pageIndex: Int, blockIndex: Int, range: IntRange, anchor: Rect) -> Unit,
) {
    val rowModifier =
        Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = zoom
            scaleY = zoom
            translationX = pan.x
            translationY = pan.y
        }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leftState != null && rightState != null) {
            MangaPageSurface(
                state = leftState,
                overlayStyle = overlayStyle,
                showBorders = showBorders,
                editMode = editMode,
                zoom = zoom,
                onTapChar = { b, c, a -> onTapChar(leftState.pageIndex, b, c, a) },
                onSelectRange = { b, r, a -> onSelectRange(leftState.pageIndex, b, r, a) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MangaPageSurface(
                state = rightState,
                overlayStyle = overlayStyle,
                showBorders = showBorders,
                editMode = editMode,
                zoom = zoom,
                onTapChar = { b, c, a -> onTapChar(rightState.pageIndex, b, c, a) },
                onSelectRange = { b, r, a -> onSelectRange(rightState.pageIndex, b, r, a) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else {
            val single = leftState ?: rightState
            if (single != null) {
                MangaPageSurface(
                    state = single,
                    overlayStyle = overlayStyle,
                    showBorders = showBorders,
                    editMode = editMode,
                    zoom = zoom,
                    onTapChar = { b, c, a -> onTapChar(single.pageIndex, b, c, a) },
                    onSelectRange = { b, r, a -> onSelectRange(single.pageIndex, b, r, a) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Full-width page item inside the vertical scroll reader. */
@Suppress("FunctionName")
@Composable
fun VerticalMangaPageItem(
    state: MangaPageRenderState,
    overlayStyle: MangaOverlayStyle,
    showBorders: Boolean,
    editMode: Boolean,
    onTapChar: (blockIndex: Int, charIndex: Int, anchor: Rect) -> Unit,
    onSelectRange: (blockIndex: Int, range: IntRange, anchor: Rect) -> Unit,
    onTapEmpty: (normX: Float, normY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = state.bitmap
    val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else DEFAULT_PAGE_ASPECT
    val boxModifier =
        modifier.fillMaxWidth().aspectRatio(aspect).pointerInput(state.pageIndex) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = awaitSingleTapUp(down.id, down.position, viewConfiguration.touchSlop)
                val upChange = up
                val isTapReady = upChange != null && !upChange.isConsumed && size.width > 0
                if (isTapReady && size.height > 0) {
                    onTapEmpty(upChange.position.x / size.width, upChange.position.y / size.height)
                }
            }
        }
    Box(modifier = boxModifier) {
        MangaPageSurface(
            state = state,
            overlayStyle = overlayStyle,
            showBorders = showBorders,
            editMode = editMode,
            zoom = 1f,
            onTapChar = onTapChar,
            onSelectRange = onSelectRange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Renders one decoded manga page with interactive OCR/Mokuro text boxes overlaid on top.
 * Taps on a glyph consume the event and trigger lookup; taps outside fall through to page navigation.
 */
@Suppress("FunctionName")
@Composable
fun MangaPageSurface(
    state: MangaPageRenderState,
    overlayStyle: MangaOverlayStyle,
    showBorders: Boolean,
    editMode: Boolean,
    zoom: Float,
    onTapChar: (blockIndex: Int, charIndex: Int, anchor: Rect) -> Unit,
    onSelectRange: (blockIndex: Int, range: IntRange, anchor: Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = state.bitmap
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White.copy(alpha = 0.7f))
        }
        return
    }

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember(state.pageIndex) { mutableStateOf<Pair<Int, IntRange>?>(null) }

    val coords = remember(surfaceSize, bitmap, state.ocr) { computePageCoords(surfaceSize, bitmap, state.ocr) }
    val slackPx = with(density) { HIT_SLACK_DP.dp.toPx() }
    val hitSlack = slackPx / (coords.fit * zoom * min(a = coords.ocrToBmpX, b = coords.ocrToBmpY))

    val pageModifier =
        modifier
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { windowOrigin = it.positionInWindow() }
            .pointerInput(state.pageIndex, state.ocr, overlayStyle, editMode, surfaceSize, zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val blocks = state.ocr?.blocks
                    val isHidden = overlayStyle == MangaOverlayStyle.HIDDEN && !editMode
                    val initialHit =
                        if (blocks == null || isHidden) {
                            null
                        } else {
                            hitGlyph(
                                blocks = blocks,
                                coords = coords,
                                localPos = down.position,
                                slack = hitSlack,
                                editMode = editMode,
                            )
                        }
                    if (initialHit != null && blocks != null) {
                        val gestureContext =
                            TextBoxGestureContext(
                                down = down,
                                initialHit = initialHit,
                                blocks = blocks,
                                coords = coords,
                                windowOrigin = windowOrigin,
                                zoom = zoom,
                                hitSlack = hitSlack,
                                isEditMode = editMode,
                                haptics = haptics,
                                longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                                touchSlop = viewConfiguration.touchSlop,
                            )
                        processTextBoxGesture(
                            ctx = gestureContext,
                            onDragRangeChange = { dragging = it },
                            onTapChar = onTapChar,
                            onSelectRange = onSelectRange,
                        )
                    }
                }
            }
    PageArtworkAndOverlays(
        pageIndex = state.pageIndex,
        bitmap = bitmap,
        blocks = BlockList(state.ocr?.blocks.orEmpty()),
        coords = coords,
        overlayStyle = overlayStyle,
        showBorders = showBorders,
        editMode = editMode,
        dragging = dragging,
        picked = state.picked,
        filter = state.filter,
        isAiUpscale = state.isAiUpscale,
        isZoomed = zoom > DETAIL_ZOOM,
        modifier = pageModifier,
    )
}

@Suppress("FunctionName")
@Composable
private fun PageArtworkAndOverlays(
    pageIndex: Int,
    bitmap: Bitmap,
    blocks: BlockList,
    coords: PageCoords,
    overlayStyle: MangaOverlayStyle,
    showBorders: Boolean,
    editMode: Boolean,
    dragging: Pair<Int, IntRange>?,
    picked: MangaPick?,
    filter: MangaPageFilter,
    isAiUpscale: Boolean,
    isZoomed: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FilteredPageImage(
            bitmap = bitmap,
            filter = filter,
            isAiUpscale = isAiUpscale,
            isZoomed = isZoomed,
            description = "Manga page ${pageIndex + 1}",
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayStyle != MangaOverlayStyle.HIDDEN || editMode) {
            if (overlayStyle == MangaOverlayStyle.LIGHT_BOX || overlayStyle == MangaOverlayStyle.DARK_BOX) {
                ReadableTextOverlayLayer(
                    blocks = blocks,
                    isDark = overlayStyle == MangaOverlayStyle.DARK_BOX,
                    toLocalRect = coords::ocrBoxToLocalRect,
                )
            }

            val primary = MaterialTheme.colorScheme.primary
            val selectingColor = MaterialTheme.colorScheme.tertiary
            val highlightState =
                HighlightDrawState(
                    hasBorders = showBorders,
                    isEditMode = editMode,
                    dragging = dragging,
                    picked = picked,
                    primary = primary,
                    selectingColor = selectingColor,
                )
            Canvas(Modifier.fillMaxSize()) {
                drawPageHighlights(blocks = blocks.items, coords = coords, state = highlightState)
            }
        }
    }
}

private data class HighlightDrawState(
    val hasBorders: Boolean,
    val isEditMode: Boolean,
    val dragging: Pair<Int, IntRange>?,
    val picked: MangaPick?,
    val primary: Color,
    val selectingColor: Color,
)

private fun DrawScope.drawPageHighlights(blocks: List<MangaBlock>, coords: PageCoords, state: HighlightDrawState) {
    if (state.hasBorders || state.isEditMode) {
        val alpha = if (state.isEditMode) EDIT_BORDER_ALPHA else NORMAL_BORDER_ALPHA
        val strokeW = if (state.isEditMode) 1.5.dp.toPx() else 1.dp.toPx()
        for (item in blocks) {
            val r = coords.ocrBoxToLocalRect(item.boundingBox)
            drawRect(color = state.primary.copy(alpha = alpha), topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
        }
    }
    val active = state.dragging ?: state.picked?.let { it.blockIndex to it.range }
    val block = active?.let { blocks.getOrNull(it.first) } ?: return
    val color = if (state.dragging != null) state.selectingColor else state.primary
    for (i in active.second) {
        val glyphBox = block.glyphs.getOrNull(i)?.toBoundingBox()
        if (glyphBox != null) {
            val r = coords.ocrBoxToLocalRect(glyphBox)
            drawRect(color = color.copy(alpha = HIGHLIGHT_ALPHA), topLeft = r.topLeft, size = r.size)
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReadableTextOverlayLayer(blocks: BlockList, isDark: Boolean, toLocalRect: (BoundingBox) -> Rect) {
    Box(Modifier.fillMaxSize()) {
        for (block in blocks.items) {
            val rect = toLocalRect(block.boundingBox)
            if (rect.width >= MIN_READABLE_DIM && rect.height >= MIN_READABLE_DIM) {
                ReadableTextBubble(block = block, rect = rect, isDark = isDark)
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReadableTextBubble(block: MangaBlock, rect: Rect, isDark: Boolean) {
    val bg = Color(if (isDark) DARK_BOX_BG else LIGHT_BOX_BG)
    val fg = Color(if (isDark) DARK_BOX_FG else LIGHT_BOX_FG)
    val border = Color(if (isDark) DARK_BOX_BORDER else LIGHT_BOX_BORDER)
    val density = LocalDensity.current

    val cols = block.lines.ifEmpty { listOf(block.text) }
    val maxChars = cols.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
    val padPx =
        with(density) {
            (READABLE_BOX_PAD_DP * 2).dp.toPx()
        }.coerceAtLeast(READABLE_PAD_PX_FALLBACK)
    val innerW = (rect.width - padPx).coerceAtLeast(1f)
    val innerH = (rect.height - padPx).coerceAtLeast(1f)
    val rawFontPx =
        if (block.isVertical) {
            min(a = innerW / cols.size * VERTICAL_COL_FIT, b = innerH / maxChars * VERTICAL_ROW_FIT)
        } else {
            min(a = innerH / cols.size * HORIZONTAL_ROW_FIT, b = innerW / maxChars * HORIZONTAL_COL_FIT)
        }
    val fontPx = rawFontPx.coerceIn(minimumValue = MIN_READABLE_FONT_PX, maximumValue = MAX_READABLE_FONT_PX)
    val fontSp =
        with(density) {
            fontPx.toSp()
        }

    val boxModifier =
        Modifier
            .offset { IntOffset(x = rect.left.roundToInt(), y = rect.top.roundToInt()) }
            .size(width = with(density) { rect.width.toDp() }, height = with(density) { rect.height.toDp() })
            .background(bg, RoundedCornerShape(4.dp))
            .border(0.75.dp, border, RoundedCornerShape(4.dp))
            .padding(READABLE_BOX_PAD_DP.dp)
    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        if (block.isVertical) {
            VerticalReadableColumns(cols = LineList(cols), fg = fg, fontSp = fontSp)
        } else {
            Text(text = cols.joinToString("\n"), color = fg, fontSize = fontSp, lineHeight = fontSp * HORIZONTAL_LINE_HEIGHT_SCALE)
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun VerticalReadableColumns(cols: LineList, fg: Color, fontSp: TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Top) {
        for (line in cols.items.reversed()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                for (ch in line) {
                    Text(text = ch.toString(), color = fg, fontSize = fontSp, lineHeight = fontSp * VERTICAL_LINE_HEIGHT_SCALE)
                }
            }
        }
    }
}
