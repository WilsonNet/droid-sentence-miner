package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaPreOcrQueue

/** Manga OCR backend selector and full-volume background preparation controls inside Reader Settings. */
@Suppress("FunctionName")
@Composable
internal fun ReaderOcrSettingsSection(
    ocrMode: MangaOcrMode,
    hasMokuro: Boolean,
    batchProgress: MangaPreOcrQueue.VolumeProgress?,
    onOcrMode: (MangaOcrMode) -> Unit,
    onToggleBatchPreOcr: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manga OCR engine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MangaOcrMode.entries.forEach { mode ->
                FilterChip(
                    selected = ocrMode == mode,
                    onClick = { onOcrMode(mode) },
                    label = { Text(mode.label.substringBefore(" (")) },
                )
            }
        }
        Text(
            "Auto uses Google Lens (like Manatan) when online and falls back to offline OCR automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasMokuro) {
            BatchPreOcrButtonRow(batchProgress = batchProgress, onToggleBatchPreOcr = onToggleBatchPreOcr)
        }
    }
}

@Suppress("FunctionName")
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
    }
}
