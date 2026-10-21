package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.book.BookOcr
import io.github.wilsonnet.droidsentenceminer.book.BookPage
import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupPopup
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** A run of characters inside one paragraph of the page. */
private data class GlyphRange(val block: Int, val range: IntRange)

/**
 * Overlay dictionary mode (Mokuro-style): the photo full screen with the OCR text laid invisibly
 * over the printed characters. Tap a character to look up the word starting there, hold and drag
 * to select characters yourself, pinch to zoom, drag to pan. The popup opens at the word.
 */
@Composable
fun PageReader(page: BookPage, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val bitmap = page.bitmap

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var picked by remember(page) { mutableStateOf<GlyphRange?>(null) }
    var dragging by remember(page) { mutableStateOf<GlyphRange?>(null) }

    // Page pixels → viewport: fit, centered, then the user's zoom/pan.
    val fit = if (viewport == IntSize.Zero) 1f else min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
    val fitOffset = Offset((viewport.width - bitmap.width * fit) / 2f, (viewport.height - bitmap.height * fit) / 2f)
    fun toScreen(p: Offset) = (fitOffset + p * fit) * zoom + pan
    fun toPage(s: Offset) = ((s - pan) / zoom - fitOffset) / fit

    fun clampPan(p: Offset, z: Float): Offset {
        val w = viewport.width.toFloat()
        val h = viewport.height.toFloat()
        return Offset(p.x.coerceIn(w - w * z, 0f), p.y.coerceIn(h - h * z, 0f))
    }

    fun glyphAt(screen: Offset): Pair<Int, Int>? {
        val p = toPage(screen)
        val slack = with(density) { 6.dp.toPx() } / (fit * zoom)
        page.blocks.forEachIndexed { b, block -> block.glyphAt(p.x, p.y)?.let { return b to it } }
        page.blocks.forEachIndexed { b, block -> block.glyphAt(p.x, p.y, slack)?.let { return b to it } }
        return null
    }

    fun lookupAt(screen: Offset) {
        val (b, i) = glyphAt(screen) ?: run { picked = null; return }
        val text = page.blocks[b].text
        scope.launch { picked = GlyphRange(b, container.lookup.wordAt(text, i)) }
    }

    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(page, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 0 = held (select), 1 = tap, 2 = pan/zoom.
                    var outcome = 0
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) { outcome = 2; break }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) { outcome = 2; break }
                            if (change.changedToUp()) { outcome = 1; break }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) { outcome = 2; break }
                        }
                    }
                    when (outcome) {
                        1 -> lookupAt(down.position)
                        0 -> {
                            val (b, anchor) = glyphAt(down.position) ?: return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            picked = null
                            dragging = GlyphRange(b, anchor..anchor)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (change.changedToUp()) break
                                val (b2, at) = glyphAt(change.position) ?: continue
                                if (b2 == b) dragging = GlyphRange(b, min(anchor, at)..max(anchor, at))
                            }
                            picked = dragging
                            dragging = null
                        }
                        else -> while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val factor = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * factor).coerceIn(1f, 6f)
                            val applied = newZoom / zoom
                            val moved = if (centroid == Offset.Unspecified) pan else centroid - (centroid - pan) * applied
                            zoom = newZoom
                            pan = clampPan(moved + event.calculatePan(), zoom)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "Page photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            val mark = MaterialTheme.colorScheme.primary
            val selecting = MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.fillMaxSize()) {
                val current = dragging ?: picked ?: return@Canvas
                val color = if (dragging != null) selecting else mark
                val glyphs = page.blocks[current.block].glyphs
                for (i in current.range) {
                    val g = glyphs.getOrNull(i) ?: continue
                    drawRect(
                        color.copy(alpha = 0.35f),
                        topLeft = fitOffset + Offset(g.left.toFloat(), g.top.toFloat()) * fit,
                        size = Size(g.width * fit, g.height * fit),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tap text to look it up · hold to select · pinch to zoom",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close reader", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }

    picked?.let { pick ->
        val block = page.blocks[pick.block]
        val last = min(pick.range.last, block.text.lastIndex)
        if (pick.range.first > last) return@let
        val word = block.text.substring(pick.range.first, last + 1)
        val anchor = pick.range.mapNotNull { block.glyphs.getOrNull(it) }
            .reduceOrNull(BoundingBox::union)
            ?.let { g ->
                Rect(toScreen(Offset(g.left.toFloat(), g.top.toFloat())), toScreen(Offset(g.right.toFloat(), g.bottom.toFloat())))
                    .translate(origin)
            } ?: return@let
        WordLookupPopup(
            word = word,
            sentence = remember(pick) { PageLayout.sentenceAt(block.text, pick.range.first) },
            image = remember(block) { BookOcr.cardImage(page, block) },
            anchor = anchor,
            onDismiss = { picked = null },
        )
    }
}
