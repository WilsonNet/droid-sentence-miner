package io.github.wilsonnet.droidsentenceminer.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.audio.AudioPcm
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.WavEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * GSM-style audio editing window: waveform, playback and trim handles.
 * Used to pick exactly the voice line that goes on the Anki card.
 */
@Composable
fun AudioEditorDialog(
    segment: AudioSegment,
    onDismiss: () -> Unit,
    onApply: (AudioSegment) -> Unit,
) {
    val durationMs = segment.durationMs.toFloat().coerceAtLeast(1f)
    var range by remember(segment) { mutableStateOf(0f..durationMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Trim the voice line that will be attached to the card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AudioTrimmer(segment, range, onRangeChange = { range = it })
            }
        },
        confirmButton = {
            Button(onClick = { trimmed(segment, range)?.let(onApply) }) { Text("Use this audio") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip audio") }
        },
    )
}

/** [segment] cut to [range] (ms) with short fades, as it goes on the card; null if too short. */
fun trimmed(segment: AudioSegment, range: ClosedFloatingPointRange<Float>): AudioSegment? =
    AudioPcm.trim(segment, range.start.roundToLong(), range.endInclusive.roundToLong())?.let { AudioPcm.fadeEdges(it) }

/**
 * Waveform with the kept [range] (ms) highlighted, play/stop of that range and one two-handle
 * slider to move its ends. The player is released when this leaves the composition.
 */
@Composable
fun AudioTrimmer(
    segment: AudioSegment,
    range: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val durationMs = segment.durationMs.toFloat().coerceAtLeast(1f)
    val currentRange by rememberUpdatedState(range)
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0f) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    val peaks = remember(segment) { AudioPcm.peaks(segment, 480) }

    // Build a WAV file once so MediaPlayer can play it.
    val wavFile = remember(segment) { File(context.cacheDir, "audio-edit-${segment.hashCode()}.wav") }
    LaunchedEffect(segment) {
        withContext(Dispatchers.IO) {
            runCatching { wavFile.writeBytes(WavEncoder.encode(segment)) }
        }
    }

    DisposableEffect(segment) {
        onDispose {
            runCatching { player?.release() }
            player = null
            playing = false
            wavFile.delete()
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let { mp ->
                val pos = runCatching { mp.currentPosition.toFloat() }.getOrDefault(0f)
                positionMs = pos
                if (pos >= currentRange.endInclusive - 20f) {
                    playing = false
                    runCatching { mp.pause() }
                }
            }
            delay(80)
        }
    }

    fun togglePlay() {
        if (playing) {
            playing = false
            runCatching { player?.pause() }
            return
        }
        val startMs = range.start.roundToLong().toInt()
        val existing = player
        if (existing == null) {
            val mp = MediaPlayer()
            runCatching {
                mp.setDataSource(wavFile.absolutePath)
                mp.prepare()
                mp.seekTo(startMs)
                mp.start()
                mp.setVolume(1f, 1f)
                player = mp
                playing = true
            }.onFailure { playing = false }
        } else {
            runCatching {
                if (existing.currentPosition < range.start || existing.currentPosition >= range.endInclusive) {
                    existing.seekTo(startMs)
                }
                existing.start()
                playing = true
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val wave = MaterialTheme.colorScheme.primary
        // The cut-off ends: the waveform shows through, faded.
        val shade = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Canvas(Modifier.fillMaxWidth().height(76.dp)) {
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val perBar = w / peaks.size.coerceAtLeast(1)
                for (i in peaks.indices) {
                    val amp = peaks[i] * (h / 2f)
                    val x = i * perBar + perBar / 2f
                    drawRect(
                        color = wave,
                        topLeft = Offset(x, mid - amp),
                        size = Size(perBar * 0.7f, amp * 2f),
                    )
                }
                // trim shading
                val startX = (range.start / durationMs) * w
                val endX = (range.endInclusive / durationMs) * w
                drawRect(color = shade, topLeft = Offset(0f, 0f), size = Size(startX, h))
                drawRect(color = shade, topLeft = Offset(endX, 0f), size = Size(w - endX, h))
                drawLine(wave, Offset(startX, 0f), Offset(startX, h), strokeWidth = 3f)
                drawLine(wave, Offset(endX, 0f), Offset(endX, h), strokeWidth = 3f)
                // playhead
                val px = (positionMs / durationMs) * w
                if (playing && px in 0f..w) {
                    drawLine(Color(0xFFFFC857), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
                }
            }
        }
        RangeSlider(
            value = range,
            onValueChange = { value ->
                if (value.endInclusive - value.start >= MIN_CLIP_MS) onRangeChange(value)
            },
            valueRange = 0f..durationMs,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { togglePlay() }) {
                Icon(
                    if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Play",
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${(range.start / 1000f).format1()}s – ${(range.endInclusive / 1000f).format1()}s",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${((range.endInclusive - range.start) / 1000f).format1()}s of ${(durationMs / 1000f).format1()}s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Float.format1(): String = "%.1f".format(this)

private const val MIN_CLIP_MS = 350f
