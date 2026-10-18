package io.github.wilsonnet.droidsentenceminer.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wilsonnet.droidsentenceminer.capture.AutoOcrController
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineDao
import kotlinx.coroutines.flow.StateFlow

/**
 * Small always-on-top feed of the newest auto OCR lines, so you can follow and mine the
 * text without leaving the game. Tap a line to open it in the lookup panel.
 */
@Composable
fun LiveTextWindow(
    dao: TextLineDao,
    status: StateFlow<AutoOcrController.Status>,
    onLineClick: (Long) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onEditAreas: () -> Unit,
    onClose: () -> Unit,
) {
    val lines by remember { dao.observeRecent(VISIBLE_LINES) }.collectAsState(initial = emptyList())
    val auto by status.collectAsState()

    Column(
        Modifier
            .widthIn(min = 240.dp, max = 420.dp)
            .background(Color(0xE6101C18), RoundedCornerShape(14.dp))
            .padding(bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                }
                .padding(start = 8.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DragIndicator, contentDescription = "Move", tint = Color(0xFF9FB8AE), modifier = Modifier.size(18.dp))
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(8.dp)
                    .background(if (auto.running && auto.error == null) Color(0xFF4CD9B4) else Color(0xFFFFB74D), CircleShape),
            )
            Text(
                text = when {
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
            Icon(
                Icons.Default.Edit,
                contentDescription = "Auto OCR text areas",
                tint = Color(0xFF9FB8AE),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onEditAreas)
                    .padding(6.dp),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "Hide live text",
                tint = Color(0xFF9FB8AE),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose)
                    .padding(6.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Oldest first so the newest line sits at the bottom, like a chat.
            lines.reversed().forEachIndexed { index, line ->
                val newest = index == lines.lastIndex
                Text(
                    text = line.text,
                    color = if (newest) Color.White else Color(0xB3FFFFFF),
                    fontSize = if (newest) 17.sp else 14.sp,
                    lineHeight = if (newest) 24.sp else 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLineClick(line.id) }
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
            if (lines.isEmpty()) {
                Text(
                    "Lines will appear here as they show up in the game.",
                    color = Color(0xB3FFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val VISIBLE_LINES = 3
