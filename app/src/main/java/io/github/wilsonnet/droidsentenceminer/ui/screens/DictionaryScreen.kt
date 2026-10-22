package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity
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

    var importing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }

    fun installRecommended(dictionary: RecommendedDictionary) {
        if (importing || installingId != null) return
        installingId = dictionary.id
        errorText = null
        progressText = "Downloading ${dictionary.name}…"
        scope.launch {
            try {
                val zipFile = File(context.cacheDir, "recommended-${dictionary.id}.zip")
                container.dictDownloader.download(dictionary.url, zipFile) { downloaded, total ->
                    progressText = if (total != null && total > 0) {
                        "Downloading ${dictionary.name}… ${downloaded * 100 / total}%"
                    } else {
                        "Downloading ${dictionary.name}… ${downloaded / (1024 * 1024)} MB"
                    }
                }
                progressText = "Importing ${dictionary.name}…"
                val entity = withContext(Dispatchers.IO) {
                    container.importer.importRecommended(dictionary, zipFile) { progress ->
                        progressText = "Importing ${progress.title ?: dictionary.name}… ${progress.current}"
                    }
                }
                zipFile.delete()
                progressText = "Installed \"${entity.title}\" (${entity.termCount} terms, ${entity.kanjiCount} kanji)"
            } catch (e: Exception) {
                errorText = e.message ?: "Install failed"
                progressText = null
            } finally {
                installingId = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            importing = true
            errorText = null
            progressText = "Starting import…"
            scope.launch {
                try {
                    val entity = withContext(Dispatchers.IO) {
                        container.importer.import(uri) { progress ->
                            progressText = buildString {
                                append(progress.title ?: "Importing")
                                if (progress.stage.isNotBlank()) append(" · ").append(progress.stage)
                                if (progress.current > 0) append(" · ").append(progress.current)
                            }
                        }
                    }
                    progressText = "Imported \"${entity.title}\" (${entity.termCount} terms, ${entity.kanjiCount} kanji)"
                } catch (e: Exception) {
                    errorText = e.message ?: "Import failed"
                    progressText = null
                } finally {
                    importing = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dictionaries", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Import Yomitan/Yomichan dictionaries (.zip). Jitendex, JMdict, KANJIDIC and frequency dictionaries all work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            enabled = !importing,
            onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import dictionary (.zip)")
        }

        if (importing || progressText != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    progressText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        errorText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (dictionaries.isEmpty() && !importing) {
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
        } else if (!importing) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add more dictionaries", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Install recommended dictionaries with one tap, or import any Yomitan .zip below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RecommendedDictionaries.ALL.forEach { dict ->
                        val installed = dictionaries.any { it.title.contains(dict.name, ignoreCase = true) }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dict.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (installingId == dict.id) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                OutlinedButton(
                                    enabled = installingId == null && !importing && !installed,
                                    onClick = { installRecommended(dict) },
                                ) { Text(if (installed) "Installed" else "Install") }
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dictionaries, key = { it.id }) { dict ->
                DictionaryRow(
                    dict = dict,
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
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DictionaryRow(
    dict: DictionaryEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(dict.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append("${dict.termCount} terms")
                        if (dict.kanjiCount > 0) append(" · ${dict.kanjiCount} kanji")
                        dict.author?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dict.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete dictionary")
            }
        }
    }
}
