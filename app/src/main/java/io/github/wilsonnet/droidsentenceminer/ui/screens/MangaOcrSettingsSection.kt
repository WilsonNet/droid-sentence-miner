package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.manga.MangaModelDownloads
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrModels
import io.github.wilsonnet.droidsentenceminer.manga.MangaPreOcrQueue

private const val BYTES_PER_MB = 1_000_000L
private const val PERCENT = 100f

/** Manga OCR backend selector, model download and full-volume background preparation in Reader Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun ReaderOcrSettingsSection(
    ocrMode: MangaOcrMode,
    hasMokuro: Boolean,
    batchProgress: MangaPreOcrQueue.VolumeProgress?,
    onOcrMode: (MangaOcrMode) -> Unit,
    onToggleBatchPreOcr: () -> Unit,
) {
    val context = LocalContext.current
    val download by MangaModelDownloads.state.collectAsState()
    LaunchedEffect(Unit) { MangaModelDownloads.refresh(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manga OCR engine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MangaOcrMode.entries.forEach { mode ->
                FilterChip(
                    selected = ocrMode == mode,
                    onClick = { onOcrMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            engineHint(mode = ocrMode, isInstalled = download.isInstalled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelDownloadRow(
            state = download,
            onDownload = { MangaModelDownloads.start(context, AppContainer.from(context).appScope) },
            onRemove = { MangaModelDownloads.remove(context) },
        )
        if (!hasMokuro) {
            BatchPreOcrButtonRow(batchProgress = batchProgress, onToggleBatchPreOcr = onToggleBatchPreOcr)
        }
    }
}

private fun engineHint(mode: MangaOcrMode, isInstalled: Boolean): String {
    val hint =
        when {
            mode == MangaOcrMode.AUTO && isInstalled -> "Auto reads pages on-device with the manga OCR models (offline, close to mokuro)."
            mode == MangaOcrMode.AUTO -> "Auto uses Google Lens until the manga OCR models are downloaded, and ML Kit when offline."
            mode == MangaOcrMode.LOCAL -> "PP-OCRv6 manga + a speech-bubble detector, fully offline. Best for Japanese manga."
            mode == MangaOcrMode.LENS -> "Google Lens (online, like Manatan). Bubbles are grouped with the bubble detector once downloaded."
            else -> "Basic offline OCR. Misses ruby and often scrambles vertical columns."
        }
    return hint
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ModelDownloadRow(state: MangaModelDownloads.State, onDownload: () -> Unit, onRemove: () -> Unit) {
    val sizeMb = MangaOcrModels.totalBytes / BYTES_PER_MB
    val progress = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            progress != null -> {
                Text("Downloading manga OCR models… $progress%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { progress / PERCENT }, modifier = Modifier.fillMaxWidth())
            }

            state.isInstalled -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Manga OCR models installed ($sizeMb MB)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onRemove) { Text("Remove") }
                }
            }

            else -> {
                FilledTonalButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download manga OCR models ($sizeMb MB)")
                }
            }
        }
        state.error?.let { error ->
            Text("Download failed: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun BatchPreOcrButtonRow(batchProgress: MangaPreOcrQueue.VolumeProgress?, onToggleBatchPreOcr: () -> Unit) {
    val isRunning = batchProgress?.isRunning == true
    val buttonText =
        if (batchProgress?.isRunning == true) {
            "Stop preparing OCR (${batchProgress.done} / ${batchProgress.total})"
        } else {
            "Prepare OCR for all pages"
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = onToggleBatchPreOcr, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Close else Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(buttonText)
        }
        if (batchProgress?.isRunning == true && batchProgress.total > 0) {
            LinearProgressIndicator(
                progress = { batchProgress.done.toFloat() / batchProgress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Reads every page in the background with the engine above, so pages open instantly (like a .mokuro file). " +
                "Pages read by another engine are redone; hand edits are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
