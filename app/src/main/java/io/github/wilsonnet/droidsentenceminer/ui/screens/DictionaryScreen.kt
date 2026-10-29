package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.dict.DictImportQueue
import io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity
import io.github.wilsonnet.droidsentenceminer.dict.DictionaryUpdate
import io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries
import io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DictionaryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val dictionaries by remember { container.db.dictionaryDao().observeAll() }
        .collectAsState(initial = emptyList())
    val updates by container.dictUpdates.collectAsState()

    val listState = rememberLazyListState()
    var orderedList by remember { mutableStateOf(dictionaries) }
    var draggingItemId by remember { mutableStateOf<Long?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(dictionaries) {
        if (draggingItemId == null) {
            orderedList = dictionaries
        }
    }

    val importState by container.dictImports.state.collectAsState()
    val importing = importState.running
    var progressText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(importing) {
        if (!importing) {
            importState.summary?.let { progressText = it }
            if (importState.failures.isNotEmpty()) errorText = importState.failures.joinToString("\n")
        }
    }
    var checkingUpdates by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(dictionaries.size) {
        if (dictionaries.isNotEmpty() && !checkedOnce) {
            checkedOnce = true
            checkingUpdates = true
            runCatching { container.refreshDictUpdates() }
            checkingUpdates = false
        }
    }

    val installingId = importState.activeKey?.takeIf { it.startsWith("rec:") }?.removePrefix("rec:")
    val updatingDictId = importState.activeKey?.takeIf { it.startsWith("update:") }?.removePrefix("update:")?.toLongOrNull()
    val busy = importing

    fun installRecommended(dictionary: RecommendedDictionary) {
        errorText = null
        container.dictImports.enqueue(listOf(DictImportQueue.Source.Recommended(dictionary)))
    }

    fun updateDictionary(update: DictionaryUpdate) {
        errorText = null
        container.dictImports.enqueue(listOf(DictImportQueue.Source.Update(update)))
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        container.dictImports.enqueue(uris.map { DictImportQueue.Source.Local(it, container.dictImports.nameOf(it)) })
    }
    val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "*/*")
    var showDriveSheet by remember { mutableStateOf(false) }

    if (showDriveSheet) {
        DriveImportSheet(
            onDismiss = { showDriveSheet = false },
            onBrowseMyDrive = { picker.launch(zipTypes) },
            onInstall = { files ->
                container.dictImports.enqueue(files.map { DictImportQueue.Source.Drive(it.fileId, it.name?.removeSuffix(".zip")) })
            },
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One scrolling list: with a dozen dictionaries the header no longer squeezes the list.
        item(key = "header") {
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dictionaries", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Import Yomitan/Yomichan dictionaries (.zip) from this device or Google Drive, several at once. Jitendex, JMdict, KANJIDIC and frequency dictionaries all work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = { picker.launch(zipTypes) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("From files")
                    }
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = { showDriveSheet = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive")
                    }
                }
                if (dictionaries.isNotEmpty()) {
                    OutlinedButton(
                        enabled = !busy && !checkingUpdates,
                        onClick = {
                            checkingUpdates = true
                            errorText = null
                            scope.launch {
                                try {
                                    val found = container.refreshDictUpdates()
                                    progressText = if (found.isEmpty()) {
                                        "All dictionaries are up to date"
                                    } else {
                                        "${found.size} dictionary update${if (found.size > 1) "s" else ""} available"
                                    }
                                } catch (e: Exception) {
                                    errorText = e.message ?: "Could not check for updates"
                                } finally {
                                    checkingUpdates = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (checkingUpdates) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Check for updates")
                    }
                }

                val shownProgress = if (importing) importState.progress ?: "Starting import…" else progressText
                if (busy || shownProgress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            shownProgress.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (importing && importState.total > 1) {
                        LinearProgressIndicator(
                            progress = { (importState.current - 1).coerceAtLeast(0) / importState.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (dictionaries.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Recommended dictionaries", fontWeight = FontWeight.SemiBold)
                            RecommendedDictionaries.ALL.forEach { dict ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(dict.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${dict.description} (~${dict.approximateSizeMb.toInt()} MB)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (installingId == dict.id) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        Button(
                                            enabled = installingId == null && !importing,
                                            onClick = { installRecommended(dict) },
                                        ) { Text("Install") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add more dictionaries", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Install recommended dictionaries with one tap, or import any Yomitan .zip from files or Google Drive above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            RecommendedDictionaries.ALL.forEach { dict ->
                                val installedDict = dictionaries.firstOrNull { it.title.contains(dict.name, ignoreCase = true) }
                                val installed = installedDict != null
                                val update = installedDict?.let { updates[it.id] }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dict.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (update != null) {
                                            Text(
                                                "Update available: ${update.latestTitle} (rev ${update.latestRevision})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (installingId == dict.id || (installedDict != null && updatingDictId == installedDict.id)) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else if (update != null) {
                                        FilledTonalButton(
                                            enabled = !busy,
                                            onClick = { updateDictionary(update) },
                                        ) { Text("Update") }
                                    } else {
                                        OutlinedButton(
                                            enabled = !busy && !installed,
                                            onClick = { installRecommended(dict) },
                                        ) { Text(if (installed) "Installed" else "Install") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        items(orderedList, key = { it.id }) { dict ->
            val index = orderedList.indexOfFirst { it.id == dict.id }
            val isDragging = dict.id == draggingItemId
            val update = updates[dict.id]

            val dragModifier = Modifier.pointerInput(dict.id) {
                detectDragGestures(
                    onDragStart = {
                        draggingItemId = dict.id
                        dragDeltaY = 0f
                    },
                    onDragEnd = {
                        val finalOrder = orderedList.map { it.id }
                        draggingItemId = null
                        dragDeltaY = 0f
                        scope.launch {
                            container.db.dictionaryDao().updatePriorities(finalOrder)
                        }
                    },
                    onDragCancel = {
                        draggingItemId = null
                        dragDeltaY = 0f
                        orderedList = dictionaries
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragDeltaY += dragAmount.y

                        val currentIdx = orderedList.indexOfFirst { it.id == dict.id }
                        if (currentIdx != -1) {
                            val currentInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dict.id }
                            if (currentInfo != null) {
                                val currentCenter = currentInfo.offset + dragDeltaY + currentInfo.size / 2f
                                if (dragDeltaY > 0 && currentIdx < orderedList.size - 1) {
                                    val nextId = orderedList[currentIdx + 1].id
                                    val nextInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == nextId }
                                    val nextCenter = if (nextInfo != null) {
                                        nextInfo.offset + nextInfo.size / 2f
                                    } else {
                                        currentInfo.offset + currentInfo.size + currentInfo.size / 2f
                                    }
                                    if (currentCenter > nextCenter) {
                                        val step = (nextInfo?.offset ?: (currentInfo.offset + currentInfo.size)) - currentInfo.offset
                                        orderedList = orderedList.toMutableList().apply {
                                            add(currentIdx + 1, removeAt(currentIdx))
                                        }
                                        dragDeltaY -= step
                                    }
                                } else if (dragDeltaY < 0 && currentIdx > 0) {
                                    val prevId = orderedList[currentIdx - 1].id
                                    val prevInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == prevId }
                                    val prevCenter = if (prevInfo != null) {
                                        prevInfo.offset + prevInfo.size / 2f
                                    } else {
                                        currentInfo.offset - currentInfo.size / 2f
                                    }
                                    if (currentCenter < prevCenter) {
                                        val step = currentInfo.offset - (prevInfo?.offset ?: (currentInfo.offset - currentInfo.size))
                                        orderedList = orderedList.toMutableList().apply {
                                            add(currentIdx - 1, removeAt(currentIdx))
                                        }
                                        dragDeltaY += step
                                    }
                                }
                            }
                        }
                    },
                )
            }

            DictionaryRow(
                modifier = Modifier.animateItem(),
                dict = dict,
                priorityIndex = index,
                update = update,
                updating = updatingDictId == dict.id,
                busy = busy,
                isDragging = isDragging,
                dragDeltaY = if (isDragging) dragDeltaY else 0f,
                dragModifier = dragModifier,
                onUpdate = { if (update != null) updateDictionary(update) },
                onToggle = { enabled ->
                    scope.launch { container.db.dictionaryDao().setEnabled(dict.id, enabled) }
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        container.db.termDao().deleteByDict(dict.id)
                        container.db.kanjiDao().deleteByDict(dict.id)
                        container.db.tagDao().deleteByDict(dict.id)
                        container.db.metaDao().deleteTermMetaByDict(dict.id)
                        container.db.metaDao().deleteKanjiMetaByDict(dict.id)
                        container.db.dictionaryDao().delete(dict.id)
                        dict.zipPath?.let { path -> runCatching { File(path).delete() } }
                        runCatching { File(context.cacheDir, "dict-images/${dict.id}").deleteRecursively() }
                        runCatching { container.refreshDictUpdates() }
                    }
                },
            )
        }
    }
}

@Composable
private fun DictionaryRow(
    dict: DictionaryEntity,
    priorityIndex: Int,
    update: DictionaryUpdate?,
    updating: Boolean,
    busy: Boolean,
    isDragging: Boolean,
    dragDeltaY: Float,
    dragModifier: Modifier,
    onUpdate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) dragDeltaY else 0f
                scaleX = if (isDragging) 1.02f else 1.0f
                scaleY = if (isDragging) 1.02f else 1.0f
                shadowElevation = if (isDragging) 12.dp.toPx() else 0f
            },
        colors = if (isDragging) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                "#${priorityIndex + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(dict.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append("${dict.termCount} terms")
                        if (dict.kanjiCount > 0) append(" · ${dict.kanjiCount} kanji")
                        append(" · rev ${dict.revision}")
                        dict.author?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (update != null) {
                    Text(
                        "Update available: ${update.latestTitle} (rev ${update.latestRevision})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (updating) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else if (update != null) {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = onUpdate,
                ) { Text("Update") }
                Spacer(Modifier.width(6.dp))
            }
            Switch(checked = dict.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = "Delete dictionary")
            }
        }
    }
}
