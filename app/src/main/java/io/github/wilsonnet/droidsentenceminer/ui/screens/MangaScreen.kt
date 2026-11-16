package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.manga.MangaImportQueue
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaPreOcrQueue
import io.github.wilsonnet.droidsentenceminer.manga.MangaVolumeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

private const val COVER_ASPECT_RATIO = 0.70f
private const val BADGE_PRIMARY_ALPHA = 0.9f
private const val BADGE_DARK_ALPHA = 0.65f
private const val MENU_CIRCLE_ALPHA = 0.55f
private const val PILL_PERCENT = 50

/**
 * `.cbz` Manga Library + Manatan/Mokuro-style interactive reader.
 * Supports local `.cbz`/`.zip`/`.mokuro` files, Google Drive (personal & shared folders),
 * automatic on-device OCR when no `.mokuro` is present, and panel cropping for Anki cards.
 */
@Suppress("FunctionName")
@Composable
fun MangaScreen(modifier: Modifier = Modifier, onReaderOpenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val currentOnReaderOpenChange by rememberUpdatedState(onReaderOpenChange)

    val volumes by
        remember { container.mangaDb.volumeDao().observeVolumes() }
            .collectAsState(initial = emptyList())
    val importState by container.mangaImports.state.collectAsState()
    val preOcrMap by container.mangaPreOcr.progress.collectAsState()
    val ocrModeId by container.prefs.mangaOcrMode.collectAsState(initial = null)
    val ocrMode = MangaOcrMode.fromId(ocrModeId)

    var openVolumeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var isDriveSheetOpen by remember { mutableStateOf(false) }
    var attachTarget by remember { mutableStateOf<MangaVolumeEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MangaVolumeEntity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val openVolume =
        remember(volumes, openVolumeId) {
            openVolumeId?.let { id -> volumes.firstOrNull { it.id == id } }
        }
    LaunchedEffect(openVolume != null) {
        currentOnReaderOpenChange(openVolume != null)
    }
    LaunchedEffect(importState.isRunning) {
        if (!importState.isRunning) {
            importState.summary?.let { statusMessage = it }
            if (importState.failures.isNotEmpty()) {
                errorMessage = importState.failures.joinToString("\n")
            }
        }
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                statusMessage = null
                errorMessage = null
                container.mangaImports.enqueueLocalUris(uris)
            }
        }
    val mokuroPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = attachTarget
            attachTarget = null
            if (uri != null && target != null) {
                scope.launch {
                    val outcome = attachMokuroSafely(container, target, uri)
                    outcome.fold(
                        onSuccess = { msg ->
                            statusMessage = msg
                            errorMessage = null
                        },
                        onFailure = { err ->
                            errorMessage = err.message ?: "Failed to attach .mokuro"
                        },
                    )
                }
            }
        }

    if (openVolume != null) {
        MangaReaderScreen(volume = openVolume, onClose = { openVolumeId = null }, modifier = modifier)
        return
    }

    MangaScreenDialogs(
        isDriveSheetOpen = isDriveSheetOpen,
        deleteTarget = deleteTarget,
        onDismissDrive = { isDriveSheetOpen = false },
        onBrowseDrive = { filePicker.launch(arrayOf("*/*")) },
        onEnqueueDrive = { files ->
            statusMessage = null
            errorMessage = null
            container.mangaImports.enqueue(files)
        },
        onDismissDelete = { deleteTarget = null },
        onConfirmDelete = { target ->
            deleteTarget = null
            container.mangaPreOcr.cancel(target.id)
            scope.launch { container.mangaRepo.deleteVolume(target) }
        },
    )

    MangaShelfGrid(
        volumes = VolumeList(volumes),
        preOcrMap = PreOcrProgressMap(preOcrMap),
        importState = importState,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        onPickFiles = { filePicker.launch(arrayOf("*/*")) },
        onOpenDrive = { isDriveSheetOpen = true },
        onOpenVolume = { openVolumeId = it.id },
        onAttachMokuro = { volume ->
            attachTarget = volume
            mokuroPicker.launch(arrayOf("*/*"))
        },
        onTogglePreOcr = { volume ->
            if (preOcrMap[volume.id]?.isRunning == true) {
                container.mangaPreOcr.cancel(volume.id)
            } else {
                container.mangaPreOcr.start(volume = volume, mode = ocrMode)
            }
        },
        onResetProgress = { volume ->
            scope.launch { container.mangaRepo.updateProgress(volume.id, 0) }
        },
        onRequestDelete = { deleteTarget = it },
        modifier = modifier,
    )
}

private suspend fun attachMokuroSafely(container: AppContainer, target: MangaVolumeEntity, uri: Uri): Result<String> {
    val result =
        try {
            val count = container.mangaRepo.attachMokuro(target, uri)
            Result.success("Attached .mokuro ($count pages matched) to \"${target.title}\"")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(IllegalStateException(e.message ?: "Could not read .mokuro file", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(IllegalStateException(e.message ?: "Could not match .mokuro file", e))
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException(e.message ?: "Permission denied for .mokuro file", e))
        }
    return result
}

@Suppress("FunctionName")
@Composable
private fun MangaScreenDialogs(
    isDriveSheetOpen: Boolean,
    deleteTarget: MangaVolumeEntity?,
    onDismissDrive: () -> Unit,
    onBrowseDrive: () -> Unit,
    onEnqueueDrive: (List<MangaImportQueue.Source.Drive>) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (MangaVolumeEntity) -> Unit,
) {
    if (isDriveSheetOpen) {
        DriveImportSheet(
            target = DriveSheetTarget.MANGA,
            onDismiss = onDismissDrive,
            onBrowseMyDrive = onBrowseDrive,
            onInstall = { files ->
                onEnqueueDrive(
                    files.map { f ->
                        MangaImportQueue.Source.Drive(
                            fileId = f.fileId,
                            name = f.name ?: "manga.cbz",
                            mokuroFileId = f.mokuroFileId,
                        )
                    },
                )
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteVolumeDialog(
            title = target.title,
            onDismiss = onDismissDelete,
            onConfirm = { onConfirmDelete(target) },
        )
    }
}

@Immutable
private data class VolumeList(val items: List<MangaVolumeEntity>)

@Immutable
private data class PreOcrProgressMap(val items: Map<Long, MangaPreOcrQueue.VolumeProgress>)

@Suppress("FunctionName")
@Composable
private fun MangaShelfGrid(
    volumes: VolumeList,
    preOcrMap: PreOcrProgressMap,
    importState: MangaImportQueue.State,
    statusMessage: String?,
    errorMessage: String?,
    onPickFiles: () -> Unit,
    onOpenDrive: () -> Unit,
    onOpenVolume: (MangaVolumeEntity) -> Unit,
    onAttachMokuro: (MangaVolumeEntity) -> Unit,
    onTogglePreOcr: (MangaVolumeEntity) -> Unit,
    onResetProgress: (MangaVolumeEntity) -> Unit,
    onRequestDelete: (MangaVolumeEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MangaHeader(
                importState = importState,
                statusMessage = statusMessage,
                errorMessage = errorMessage,
                onPickFiles = onPickFiles,
                onOpenDrive = onOpenDrive,
            )
        }
        if (volumes.items.isEmpty() && !importState.isRunning) {
            item(span = { GridItemSpan(maxLineSpan) }) { MangaEmptyCard() }
        }
        items(volumes.items, key = { it.id }) { volume ->
            VolumeGridCard(
                volume = volume,
                preOcr = preOcrMap.items[volume.id],
                onOpen = { onOpenVolume(volume) },
                onAttachMokuro = { onAttachMokuro(volume) },
                onTogglePreOcr = { onTogglePreOcr(volume) },
                onResetProgress = { onResetProgress(volume) },
                onDelete = { onRequestDelete(volume) },
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun MangaHeader(
    importState: MangaImportQueue.State,
    statusMessage: String?,
    errorMessage: String?,
    onPickFiles: () -> Unit,
    onOpenDrive: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manga", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Read .cbz volumes with Mokuro or automatic on-device OCR. " +
                "Tap any word to look it up, then crop the panel for Anki.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(enabled = !importState.isRunning, onClick = onPickFiles, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("From files")
            }
            FilledTonalButton(enabled = !importState.isRunning, onClick = onOpenDrive, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Google Drive")
            }
        }
        if (importState.isRunning) ImportProgressCard(importState)
        statusMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        errorMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ImportProgressCard(importState: MangaImportQueue.State) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    importState.progress ?: "Importing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (importState.total > 1) {
                LinearProgressIndicator(
                    progress = {
                        (importState.current - 1).coerceAtLeast(0) / importState.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun MangaEmptyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Your manga shelf is empty",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "• Pick .cbz / .zip volumes alongside matching .mokuro files to load pre-OCR'd text.\n" +
                    "• Or open Google Drive to browse your Drive or paste a shared folder link — " +
                    "matching .mokuro files are paired automatically.\n" +
                    "• Volumes without .mokuro are OCR'd automatically on-device as you read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun DeleteVolumeDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove volume?") },
        text = { Text("Delete \"$title\" and its cached OCR data from this device?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Suppress("FunctionName")
@Composable
private fun VolumeGridCard(
    volume: MangaVolumeEntity,
    preOcr: MangaPreOcrQueue.VolumeProgress?,
    onOpen: () -> Unit,
    onAttachMokuro: () -> Unit,
    onTogglePreOcr: () -> Unit,
    onResetProgress: () -> Unit,
    onDelete: () -> Unit,
) {
    val pageLabel = "${(volume.currentPage + 1).coerceAtMost(volume.pageCount)} / ${volume.pageCount}"
    val fraction = if (volume.pageCount > 0) (volume.currentPage + 1).toFloat() / volume.pageCount else 0f

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            VolumeCoverBox(
                volume = volume,
                preOcr = preOcr,
                onAttachMokuro = onAttachMokuro,
                onTogglePreOcr = onTogglePreOcr,
                onResetProgress = onResetProgress,
                onDelete = onDelete,
            )
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = volume.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(minimumValue = 0f, maximumValue = 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun VolumeCoverBox(
    volume: MangaVolumeEntity,
    preOcr: MangaPreOcrQueue.VolumeProgress?,
    onAttachMokuro: () -> Unit,
    onTogglePreOcr: () -> Unit,
    onResetProgress: () -> Unit,
    onDelete: () -> Unit,
) {
    val coverBitmap =
        remember(volume.coverPath) {
            volume.coverPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT_RATIO)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = remember(coverBitmap) { coverBitmap.asImageBitmap() },
                contentDescription = volume.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val isBatchRunning = preOcr?.isRunning == true
        val badgeText =
            when {
                preOcr?.isRunning == true -> "OCR ${preOcr.done}/${preOcr.total}"
                volume.hasMokuro -> "Mokuro"
                else -> "Auto OCR"
            }
        val badgeColor =
            if (volume.hasMokuro || isBatchRunning) {
                MaterialTheme.colorScheme.primary.copy(alpha = BADGE_PRIMARY_ALPHA)
            } else {
                Color.Black.copy(alpha = BADGE_DARK_ALPHA)
            }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = badgeColor,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Box(Modifier.align(Alignment.TopEnd)) {
            VolumeOverflowMenu(
                hasMokuro = volume.hasMokuro,
                preOcr = preOcr,
                onAttachMokuro = onAttachMokuro,
                onTogglePreOcr = onTogglePreOcr,
                onResetProgress = onResetProgress,
                onDelete = onDelete,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun VolumeOverflowMenu(
    hasMokuro: Boolean,
    preOcr: MangaPreOcrQueue.VolumeProgress?,
    onAttachMokuro: () -> Unit,
    onTogglePreOcr: () -> Unit,
    onResetProgress: () -> Unit,
    onDelete: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { isMenuOpen = true }) {
        Surface(shape = RoundedCornerShape(PILL_PERCENT), color = Color.Black.copy(alpha = MENU_CIRCLE_ALPHA)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Volume options",
                tint = Color.White,
                modifier = Modifier.padding(4.dp).size(18.dp),
            )
        }
    }
    DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
        if (!hasMokuro) {
            val label =
                if (preOcr?.isRunning == true) {
                    "Stop OCR (${preOcr.done}/${preOcr.total})"
                } else {
                    "Prepare OCR (all pages)"
                }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                onClick = {
                    isMenuOpen = false
                    onTogglePreOcr()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Attach .mokuro") },
            leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
            onClick = {
                isMenuOpen = false
                onAttachMokuro()
            },
        )
        DropdownMenuItem(
            text = { Text("Reset progress") },
            leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            onClick = {
                isMenuOpen = false
                onResetProgress()
            },
        )
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = {
                isMenuOpen = false
                onDelete()
            },
        )
    }
}
