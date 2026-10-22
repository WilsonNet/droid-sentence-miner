package io.github.wilsonnet.droidsentenceminer.overlay

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.wilsonnet.droidsentenceminer.OverlayPanelState
import io.github.wilsonnet.droidsentenceminer.PanelMode
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.ui.components.AudioEditorDialog

data class OverlayCallbacks(
    val onClose: () -> Unit,
    val onScanRegion: () -> Unit,
    val onScanFullScreen: () -> Unit,
    val onStopOverlay: () -> Unit,
    val onHideBubble: () -> Unit,
    val onWordClick: (String) -> Unit,
    val onMine: () -> Unit,
    val onCopyText: () -> Unit,
    val onEditAudio: () -> Unit = {},
    val onAudioApplied: (AudioSegment) -> Unit = {},
    val onAudioSkipped: () -> Unit = {},
    val onToggleAutoOcr: () -> Unit = {},
    val onLatestLine: () -> Unit = {},
    val onSelectAreas: () -> Unit = {},
    val onShowLiveText: () -> Unit = {},
    val onRestartCapture: () -> Unit = {},
    val onToggleMicFallback: () -> Unit = {},
)

@Composable
fun OverlayPanel(
    state: OverlayPanelState,
    dark: Boolean,
    callbacks: OverlayCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Header(state, callbacks)
                when (state.mode) {
                    PanelMode.MENU -> MenuContent(state, callbacks)
                    PanelMode.RESULT -> ResultContent(state, dark, callbacks)
                }
                state.status?.let { status ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val audioSegment = state.audioSegment
        if (state.audioEditorVisible && audioSegment != null) {
            AudioEditorDialog(
                segment = audioSegment,
                onDismiss = callbacks.onAudioSkipped,
                onApply = callbacks.onAudioApplied,
            )
        }
    }
}

@Composable
private fun Header(state: OverlayPanelState, callbacks: OverlayCallbacks) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Droid Sentence Miner",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (state.mode == PanelMode.RESULT) {
            IconButton(onClick = callbacks.onScanRegion) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan again")
            }
            IconButton(onClick = callbacks.onCopyText) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy text")
            }
        }
        IconButton(onClick = callbacks.onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun MenuContent(state: OverlayPanelState, callbacks: OverlayCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        if (state.autoOcrRunning && state.captureStopped) {
            Button(onClick = callbacks.onRestartCapture, modifier = Modifier.fillMaxWidth()) {
                Text("Restart screen capture")
            }
        }
        FilledTonalButton(
            onClick = callbacks.onToggleAutoOcr,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.autoOcrRunning) "Stop auto OCR" else "Start auto OCR")
        }
        FilledTonalButton(
            onClick = callbacks.onSelectAreas,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Auto OCR text areas")
        }
        FilledTonalButton(
            onClick = callbacks.onLatestLine,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Mine the latest line")
        }
        if (state.autoOcrRunning) {
            TextButton(onClick = callbacks.onShowLiveText, modifier = Modifier.fillMaxWidth()) {
                Text("Show live text window")
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = callbacks.onToggleMicFallback)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Microphone fallback", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Record the mic when the game blocks audio capture",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.micFallback, onCheckedChange = { callbacks.onToggleMicFallback() })
        }
        FilledTonalButton(
            onClick = callbacks.onScanRegion,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan once: pick a region")
        }
        FilledTonalButton(
            onClick = callbacks.onScanFullScreen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan once: full screen")
        }
        TextButton(onClick = callbacks.onHideBubble, modifier = Modifier.fillMaxWidth()) {
            Text("Hide bubble")
        }
        TextButton(onClick = callbacks.onStopOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("Stop overlay")
        }
    }
}

@Composable
private fun ResultContent(state: OverlayPanelState, dark: Boolean, callbacks: OverlayCallbacks) {
    if (state.scanning) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                ClickableJapaneseText(
                    text = state.ocrText,
                    scanned = state.matches,
                    onWordClick = callbacks.onWordClick,
                )
            }
        }

        val selected = state.selectedWord
        if (selected != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                state.selectedDetails?.reading?.takeIf { it.isNotBlank() }?.let { reading ->
                    Text(
                        text = reading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                FilledTonalButton(onClick = callbacks.onMine) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Anki")
                }
                state.audioSegment?.let {
                    Spacer(Modifier.width(6.dp))
                    FilledTonalButton(onClick = callbacks.onEditAudio) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.audioReady) "Audio ✓" else "Audio")
                    }
                }
            }
            state.selectedDetails?.deinflection?.takeIf { it.isNotEmpty() }?.let { reasons ->
                Text(
                    text = reasons.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val html = state.definitionsHtml
            when {
                state.loadingDetails -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                html != null -> WebViewBlock(html = html, dark = dark, modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp))
                else -> Text(
                    text = "No dictionary entry found. Import a Yomitan dictionary in the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = "Tap a highlighted word to see its definition.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBlock(html: String, dark: Boolean, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    Box(modifier = modifier.background(if (dark) ComposeColor(0xFF1E1E1E) else ComposeColor.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    webViewClient = WebViewClient()
                    webView = this
                }
            },
            update = { view ->
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null)
                }
            },
        )
    }
}

@Composable
fun ClickableJapaneseText(
    text: String,
    scanned: List<ScannedWord>,
    onWordClick: (String) -> Unit,
) {
    // The text can change (auto OCR updates the line) before its scan does: drop stale offsets.
    val matches = remember(text, scanned) {
        scanned.filter { it.start >= 0 && it.end <= text.length && it.start < it.end && text.regionMatches(it.start, it.text, 0, it.text.length) }
    }
    if (text.isBlank()) {
        Text("(no text found)", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated: AnnotatedString = remember(text, matches, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            val sorted = matches.sortedBy { it.start }
            for (match in sorted) {
                if (match.start < cursor) continue
                if (match.start > cursor) append(text.substring(cursor, match.start))
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                append(text.substring(match.start, match.end))
                pop()
                cursor = match.end
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        onTextLayout = { layout = it },
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
        modifier = Modifier.pointerInput(text, matches) {
            detectTapGestures { position ->
                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                val match = matches.firstOrNull { offset >= it.start && offset < it.end }
                if (match != null) {
                    onWordClick(match.text)
                } else if (offset in text.indices) {
                    val char = text[offset]
                    if (char.isLetter()) {
                        onWordClick(
                            run {
                                // Expand to the run of Japanese characters around the tap.
                                var start = offset
                                var end = offset + 1
                                while (start > 0 && text[start - 1].isJapaneseLetter()) start--
                                while (end < text.length && text[end].isJapaneseLetter()) end++
                                text.substring(start, end)
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun Char.isJapaneseLetter(): Boolean {
    val code = code
    return (code in 0x3040..0x30FF) || (code in 0x4E00..0x9FFF) ||
        (code in 0x3400..0x4DBF) || (code in 0xF900..0xFAFF) ||
        this == '々' || this == '〆' || this == 'ー'
}
