package io.github.wilsonnet.droidsentenceminer.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wilsonnet.droidsentenceminer.capture.AutoOcrController
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineDao
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How much of the screen the live text window takes. */
enum class LiveMode {
    /** One line: the newest, scrolling sideways when it doesn't fit. */
    COMPACT,

    /** A resizable box with the scrollable history. */
    NORMAL,

    /** The whole screen with big text, for reading back. Auto OCR pauses (it would read this window). */
    FULLSCREEN,
}

/**
 * Always-on-top feed of the auto OCR lines, so you can follow and mine the text without leaving
 * the game. Tap a word for the dictionary popup next to it ([ScreenDictionary.lookupInLiveLine]),
 * hold a line to open it in the lookup panel. Scroll back through the history; new lines
 * only pull the list down while you are at the bottom ("Latest" brings you back otherwise).
 */
@Composable
fun LiveTextWindow(
    dao: TextLineDao,
    status: StateFlow<AutoOcrController.Status>,
    mode: LiveMode,
    onMode: (LiveMode) -> Unit,
    onLineClick: (Long) -> Unit,
    onCharTap: (CharTap) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
    onEditAreas: () -> Unit,
    onClose: () -> Unit,
) {
    val lines by remember { dao.observeRecent(HISTORY_LINES) }.collectAsState(initial = emptyList())
    val auto by status.collectAsState()
    val fullscreen = mode == LiveMode.FULLSCREEN
    val shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(14.dp)
    val background = if (fullscreen) Color(0xFF101C18) else Color(0xE6101C18)

    if (mode == LiveMode.COMPACT) {
        CompactBar(lines.firstOrNull()?.text, auto, background, onLineClick = { lines.firstOrNull()?.let { onLineClick(it.id) } }, onMode, onDrag, onDragEnd)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier else Modifier.dragToMove(onDrag, onDragEnd))
                .padding(start = 8.dp, end = 4.dp, top = if (fullscreen) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!fullscreen) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Move", tint = MUTED, modifier = Modifier.size(18.dp))
            }
            StatusDot(auto)
            Text(
                text = when {
                    fullscreen && auto.running -> "Live text · paused while fullscreen"
                    !auto.running -> "Auto OCR off"
                    auto.error != null -> auto.error.orEmpty()
                    else -> "Live text"
                },
                color = Color(0xFFCFE3DB),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!fullscreen) {
                HeaderButton(Icons.Default.UnfoldLess, "Compact: one line") { onMode(LiveMode.COMPACT) }
                HeaderButton(Icons.Default.Edit, "Auto OCR text areas", onClick = onEditAreas)
                HeaderButton(Icons.Default.Fullscreen, "Fullscreen") { onMode(LiveMode.FULLSCREEN) }
                HeaderButton(Icons.Default.Close, "Hide live text", onClick = onClose)
            } else {
                HeaderButton(Icons.Default.FullscreenExit, "Exit fullscreen", size = 44) { onMode(LiveMode.NORMAL) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LineList(lines.map { it.id to it.text }, fullscreen, onLineClick, onCharTap)
            if (!fullscreen) {
                ResizeHandle(
                    Modifier.align(Alignment.BottomEnd),
                    onResize,
                    onResizeEnd,
                )
            }
        }
    }
}

/** A tap on character [index] of logged line [lineId]; [box] gives a character's screen rect in px. */
class CharTap(val lineId: Long, val text: String, val index: Int, val box: (Int) -> Rect)

@Composable
private fun LineList(lines: List<Pair<Long, String>>, big: Boolean, onLineClick: (Long) -> Unit, onCharTap: (CharTap) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Reversed layout: index 0 (the newest line) sits at the bottom, like a chat.
    val atLatest by remember { derivedStateOf { state.firstVisibleItemIndex <= 1 } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(atLatest) { follow = atLatest }
    LaunchedEffect(lines.firstOrNull()?.first) {
        if (follow && lines.isNotEmpty()) state.scrollToItem(0)
    }
    Box(Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Text(
                "Lines will appear here as they show up in the game.",
                color = DIM,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            state = state,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(bottom = 6.dp),
        ) {
            items(lines, key = { it.first }) { (id, text) ->
                val newest = id == lines.first().first
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                Text(
                    text = text,
                    color = if (newest) Color.White else DIM,
                    fontSize = when {
                        big && newest -> 26.sp
                        big -> 21.sp
                        newest -> 17.sp
                        else -> 14.sp
                    },
                    lineHeight = when {
                        big && newest -> 36.sp
                        big -> 30.sp
                        newest -> 24.sp
                        else -> 19.sp
                    },
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (big) 20.dp else 12.dp, vertical = if (big) 6.dp else 3.dp)
                        .onGloballyPositioned { coords = it }
                        // Tap a character: dictionary popup next to it, like the screen text layer.
                        // Hold: the whole line in the panel (audio editor, word list).
                        .pointerInput(id, text) {
                            detectTapGestures(
                                onLongPress = { onLineClick(id) },
                                onTap = { offset ->
                                    val result = layout ?: return@detectTapGestures
                                    val origin = coords?.positionOnScreen() ?: return@detectTapGestures
                                    if (text.isEmpty()) return@detectTapGestures
                                    val index = result.getOffsetForPosition(offset).coerceIn(0, text.lastIndex)
                                    onCharTap(CharTap(id, text, index) { i -> result.getBoundingBox(i.coerceIn(0, text.lastIndex)).translate(origin) })
                                },
                            )
                        },
                )
            }
        }
        if (!atLatest) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(Color(0xFF2E7D6B), RoundedCornerShape(50))
                    .clickable { scope.launch { state.scrollToItem(0) } }
                    .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Latest", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactBar(
    text: String?,
    auto: AutoOcrController.Status,
    background: Color,
    onLineClick: () -> Unit,
    onMode: (LiveMode) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(50))
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.dragToMove(onDrag, onDragEnd).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Move", tint = MUTED, modifier = Modifier.size(18.dp))
                StatusDot(auto)
            }
        }
        Text(
            text = text ?: if (auto.running) "Waiting for text…" else "Auto OCR off",
            color = if (text != null) Color.White else DIM,
            fontSize = 16.sp,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = text != null, onClick = onLineClick)
                // A long line scrolls sideways once instead of being cut off.
                .basicMarquee(iterations = 1, initialDelayMillis = 1500)
                .padding(vertical = 6.dp),
        )
        HeaderButton(Icons.Default.UnfoldMore, "Show history") { onMode(LiveMode.NORMAL) }
    }
}

@Composable
private fun StatusDot(auto: AutoOcrController.Status) {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(8.dp)
            .background(if (auto.running && auto.error == null) Color(0xFF4CD9B4) else Color(0xFFFFB74D), CircleShape),
    )
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, size: Int = 32, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = MUTED,
        modifier = Modifier
            .size(size.dp)
            .clickable(onClick = onClick)
            .padding((size / 5).dp),
    )
}

/** Bottom-right grip: drag to resize the window. */
@Composable
private fun ResizeHandle(modifier: Modifier, onResize: (Float, Float) -> Unit, onResizeEnd: () -> Unit) {
    Canvas(
        modifier
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onResizeEnd, onDragCancel = onResizeEnd) { change, drag ->
                    change.consume()
                    onResize(drag.x, drag.y)
                }
            }
            .padding(7.dp),
    ) {
        val stroke = 1.5.dp.toPx()
        for (i in 1..3) {
            val d = size.width * i / 3f
            drawLine(MUTED, Offset(size.width - d, size.height), Offset(size.width, size.height - d), stroke)
        }
    }
}

private fun Modifier.dragToMove(onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) = pointerInput(Unit) {
    detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, drag ->
        change.consume()
        onDrag(drag.x, drag.y)
    }
}

private val MUTED = Color(0xFF9FB8AE)
private val DIM = Color(0xB3FFFFFF)
private const val HISTORY_LINES = 200
