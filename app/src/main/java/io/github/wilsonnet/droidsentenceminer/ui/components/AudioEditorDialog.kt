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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val context = LocalContext.current
    val durationMs = segment.durationMs.toFloat().coerceAtLeast(1f)

    var startMs by remember(segment) { mutableFloatStateOf(0f) }
    var endMs by remember(segment) { mutableFloatStateOf(durationMs) }
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

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
            wavFile.delete()
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let { mp ->
                val pos = runCatching { mp.currentPosition.toFloat() }.getOrDefault(0f)
                positionMs = pos
                if (pos >= endMs - 20f) {
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
        val existing = player
        if (existing == null) {
            val mp = MediaPlayer()
            runCatching {
                mp.setDataSource(wavFile.absolutePath)
                mp.prepare()
                mp.seekTo(startMs.roundToLong().toInt())
                mp.start()
                mp.setVolume(1f, 1f)
                player = mp
                playing = true
            }.onFailure { playing = false }
        } else {
            runCatching {
                if (existing.currentPosition < startMs || existing.currentPosition >= endMs) {
                    existing.seekTo(startMs.roundToLong().toInt())
                }
                existing.start()
                playing = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            runCatching { player?.release() }
            player = null
            onDismiss()
        },
        title = { Text("Audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Trim the voice line that will be attached to the card.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(6.dp),
                ) {
                    Canvas(Modifier.fillMaxWidth().height(98.dp)) {
                        val w = size.width
                        val h = size.height
                        val mid = h / 2f
                        val perBar = w / peaks.size.coerceAtLeast(1)
                        for (i in peaks.indices) {
                            val amp = peaks[i] * (h / 2f)
                            val x = i * perBar + perBar / 2f
                            drawRect(
                                color = Color(0xFF4CD9B4),
                                topLeft = Offset(x, mid - amp),
                                size = Size(perBar * 0.7f, amp * 2f),
                            )
                        }
                        // trim shading
                        val startX = (startMs / durationMs) * w
                        val endX = (endMs / durationMs) * w
                        drawRect(
                            color = Color(0x33000000),
                            topLeft = Offset(0f, 0f),
                            size = Size(startX, h),
                        )
                        drawRect(
                            color = Color(0x33000000),
                            topLeft = Offset(endX, 0f),
                            size = Size(w - endX, h),
                        )
                        drawLine(Color.White, Offset(startX, 0f), Offset(startX, h), strokeWidth = 3f)
                        drawLine(Color.White, Offset(endX, 0f), Offset(endX, h), strokeWidth = 3f)
                        // playhead
                        val px = (positionMs / durationMs) * w
                        if (px in 0f..w) {
                            drawLine(Color(0xFFFFC857), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { togglePlay() }) {
                        Icon(
                            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Stop" else "Play",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Text(
                        "${(positionMs / 1000f).format1()}s / ${(durationMs / 1000f).format1()}s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text("Start: ${(startMs / 1000f).format1()}s", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = startMs,
                    onValueChange = { value ->
                        startMs = value.coerceIn(0f, endMs - MIN_CLIP_MS)
                    },
                    valueRange = 0f..durationMs,
                )
                Text("End: ${(endMs / 1000f).format1()}s", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = endMs,
                    onValueChange = { value ->
                        endMs = value.coerceIn(startMs + MIN_CLIP_MS, durationMs)
                    },
                    valueRange = 0f..durationMs,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "Clip: ${((endMs - startMs) / 1000f).format1()}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { player?.release() }
                player = null
                val trimmed = AudioPcm.trim(segment, startMs.roundToLong(), endMs.roundToLong())
                if (trimmed != null) onApply(AudioPcm.fadeEdges(trimmed))
            }) { Text("Use this audio") }
        },
        dismissButton = {
            TextButton(onClick = {
                runCatching { player?.release() }
                player = null
                onDismiss()
            }) { Text("Skip audio") }
        },
    )
}

private fun Float.format1(): String = "%.1f".format(this)

private const val MIN_CLIP_MS = 350f
