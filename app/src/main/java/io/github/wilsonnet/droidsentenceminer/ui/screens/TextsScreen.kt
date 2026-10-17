package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.WavEncoder
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.overlay.ClickableJapaneseText
import io.github.wilsonnet.droidsentenceminer.overlay.OverlayService
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity
import io.github.wilsonnet.droidsentenceminer.ui.components.AudioEditorDialog
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GSM's texthooker page: every line auto OCR (or a manual scan) recognized, newest first.
 * Tap a line to look words up and mine them with that line's voice clip and screenshot.
 */
@Composable
fun TextsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val lines by remember { container.textLog.dao.observeRecent() }.collectAsState(initial = emptyList())
    val auto by OverlayService.observeAutoOcr().collectAsState()
    val audioEnabled by container.prefs.audioEnabled.collectAsState(initial = true)

    var hasAudioPermission by remember { mutableStateOf(hasRecordAudio(context)) }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
    }

    // Ticks once a second so buffer/age indicators stay current.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var lookup by remember { mutableStateOf<Pair<TextLineEntity, String>?>(null) }
    var editing by remember { mutableStateOf<Pair<TextLineEntity, AudioSegment>?>(null) }
    val editedAudio = remember { mutableStateMapOf<Long, AudioSegment>() }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val player = remember { ClipPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val listState = rememberLazyListState()
    val newestId = lines.firstOrNull()?.id
    LaunchedEffect(newestId) {
        // Follow new lines unless the user scrolled away to read older ones.
        if (newestId != null && listState.firstVisibleItemIndex <= HEADER_ITEMS) listState.animateScrollToItem(0)
    }

    // The header scrolls with the list so landscape (where games run) keeps room for lines.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
    ) {
        item(key = "auto") {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto OCR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (auto.running) {
                                    "Watching the saved region · ${auto.lines} new lines"
                                } else {
                                    "Reads the saved capture region while you play"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = auto.running,
                            onCheckedChange = { on ->
                                if (on) OverlayService.startAutoOcr(context) else OverlayService.stopAutoOcr(context)
                            },
                        )
                    }
                    auto.error?.takeIf { auto.running }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val buffer = container.textLog.audioBuffer
                    val bufferedSec = buffer.bufferedMs / 1000
                    val newest = buffer.newestMs
                    val live = newest != null && now - newest < 3_000
                    Text(
                        when {
                            !audioEnabled -> "Sentence audio is off (Settings)"
                            !hasAudioPermission -> "Audio capture needs the microphone permission"
                            live -> "Replay buffer: ${formatDuration(bufferedSec)} of ${formatDuration(buffer.capacitySeconds.toLong())}"
                            else -> "Replay buffer idle — starts with screen capture"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (audioEnabled && !hasAudioPermission) {
                        FilledTonalButton(onClick = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Allow audio capture")
                        }
                    }
                }
            }
        }

        item(key = "count") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${lines.size} lines",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { confirmClear = true }, enabled = lines.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all lines")
                }
            }
        }

        if (lines.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No text yet. Pick a capture region from the bubble, then turn on auto OCR — " +
                        "every line the game shows will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        items(lines, key = { it.id }) { line ->
            val hasAudio = remember(line, now) {
                container.textLog.audioBuffer.covers(line.startMs - 500, line.endMs ?: now)
            }
            LineCard(
                line = line,
                now = now,
                expanded = expandedId == line.id,
                hasAudio = hasAudio,
                edited = editedAudio.containsKey(line.id),
                playing = player.playingId == line.id,
                onClick = { expandedId = if (expandedId == line.id) null else line.id },
                onWord = { word -> lookup = line to word },
                onPlay = {
                    if (player.playingId == line.id) {
                        player.stop()
                    } else {
                        scope.launch {
                            val segment = editedAudio[line.id] ?: container.textLog.clip(line)?.segment
                            if (segment == null) message = "No audio for this line" else player.play(line.id, segment)
                        }
                    }
                },
                onEditAudio = {
                    scope.launch {
                        // Edit the untrimmed window so the trim can be widened too.
                        val segment = container.textLog.clip(line, trim = false)?.segment
                        if (segment == null) message = "No audio for this line" else editing = line to segment
                    }
                },
                onCopy = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("line", line.text))
                    message = "Copied"
                },
                onDelete = {
                    scope.launch { container.textLog.delete(line) }
                },
            )
        }
    }

    lookup?.let { (line, word) ->
        WordLookupDialog(
            word = word,
            sentence = line.text,
            image = null,
            onDismiss = { lookup = null },
            onMine = { details -> container.miner.mineLine(line, word, details, audio = editedAudio[line.id]) },
        )
    }

    editing?.let { (line, segment) ->
        AudioEditorDialog(
            segment = segment,
            onDismiss = { editing = null },
            onApply = { edited ->
                editedAudio[line.id] = edited
                editing = null
                message = "Audio saved for this line"
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all lines?") },
            text = { Text("Removes every logged line and its screenshot. Cards already in Anki are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.textLog.clear() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LineCard(
    line: TextLineEntity,
    now: Long,
    expanded: Boolean,
    hasAudio: Boolean,
    edited: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onWord: (String) -> Unit,
    onPlay: () -> Unit,
    onEditAudio: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val duration = ((line.endMs ?: now) - line.startMs).coerceAtLeast(0) / 100 / 10.0
                Text(
                    buildString {
                        append(timeFormat.format(Date(line.startMs)))
                        if (line.source == TextLineEntity.SOURCE_AUTO) append(" · ${duration}s")
                        if (line.endMs == null) append(" · on screen")
                        if (line.source == TextLineEntity.SOURCE_MANUAL) append(" · manual scan")
                        if (line.minedCount > 0) append(" · mined ×${line.minedCount}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (hasAudio || edited) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Audio available",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (!expanded) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
            } else {
                var matches by remember(line.text) { mutableStateOf<List<ScannedWord>>(emptyList()) }
                LaunchedEffect(line.text) { matches = container.lookup.scan(line.text) }
                ClickableJapaneseText(text = line.text, matches = matches, onWordClick = onWord)

                var screenshot by remember(line.screenshotPath) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(line.screenshotPath) { screenshot = container.textLog.screenshot(line) }
                screenshot?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Screenshot",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasAudio || edited) {
                        IconButton(onClick = onPlay) {
                            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = "Play line audio")
                        }
                        IconButton(onClick = onEditAudio, enabled = hasAudio) {
                            Icon(Icons.Default.GraphicEq, contentDescription = "Edit line audio")
                        }
                    } else {
                        AssistChip(onClick = {}, enabled = false, label = { Text("Audio left the buffer") })
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete line") }
                }
                Text(
                    "Tap a word to look it up and mine it with this line's audio and screenshot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Plays a clip through a temporary WAV; one at a time. */
private class ClipPlayer(private val context: Context) {
    var playingId by mutableStateOf<Long?>(null)
        private set
    private var player: MediaPlayer? = null

    suspend fun play(id: Long, segment: AudioSegment) {
        stop()
        val file = File(context.cacheDir, "line-preview.wav")
        withContext(Dispatchers.IO) { file.writeBytes(WavEncoder.encode(segment)) }
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
        playingId = id
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    fun release() = stop()
}

private fun hasRecordAudio(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private const val HEADER_ITEMS = 2

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
