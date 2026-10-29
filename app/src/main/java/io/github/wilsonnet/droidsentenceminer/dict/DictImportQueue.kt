package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dictionary imports from files and Google Drive, one at a time, on the app scope: a batch of
 * twenty big dictionaries outlives the Dictionaries page, tab switches and rotation (the screen's
 * own scope used to cancel it). Picks added while a batch runs join its queue.
 */
class DictImportQueue(
    private val context: Context,
    private val importer: YomitanImporter,
    private val downloader: DictDownloader,
    private val db: DictDatabase,
    private val scope: CoroutineScope,
    private val onInstalled: suspend () -> Unit,
) {
    sealed interface Source {
        val name: String?

        data class Local(val uri: Uri, override val name: String?) : Source
        data class Drive(val fileId: String, override val name: String?) : Source
        data class Recommended(val dictionary: RecommendedDictionary) : Source {
            override val name: String get() = dictionary.name
        }
        data class Update(val update: DictionaryUpdate) : Source {
            override val name: String get() = update.latestTitle
        }

        /** Identifies the row a spinner belongs to on the Dictionaries page. */
        val key: String
            get() = when (this) {
                is Local -> "file:$uri"
                is Drive -> "drive:$fileId"
                is Recommended -> "rec:${dictionary.id}"
                is Update -> "update:${update.dictId}"
            }
    }

    data class State(
        val running: Boolean = false,
        /** 1-based position of the dictionary being installed in the current batch. */
        val current: Int = 0,
        val total: Int = 0,
        val progress: String? = null,
        /** [Source.key] of the dictionary being installed. */
        val activeKey: String? = null,
        val installed: List<DictionaryEntity> = emptyList(),
        val failures: List<String> = emptyList(),
    ) {
        /** One line for the end of a batch, or null while running / before the first batch. */
        val summary: String?
            get() = when {
                running -> null
                installed.size == 1 && failures.isEmpty() -> installed[0].let {
                    "Imported \"${it.title}\" (${it.termCount} terms, ${it.kanjiCount} kanji)"
                }
                installed.isNotEmpty() -> "Imported ${installed.size} of $total dictionaries"
                else -> null
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val pending = ArrayDeque<Source>()

    /** Guarded by `this`: true from enqueue until the worker finds the queue empty. */
    private var draining = false

    /** Display name of a picked file, without `.zip`. */
    fun nameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.removeSuffix(".zip") else null
        }
    }.getOrNull()

    @Synchronized
    fun enqueue(items: List<Source>) {
        if (items.isEmpty()) return
        items.filterIsInstance<Source.Local>().forEach { item ->
            // Keeps read access if the process is restarted mid-batch; not every provider allows it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        pending.addAll(items)
        if (draining) {
            _state.update { it.copy(total = it.total + items.size) }
            return
        }
        draining = true
        _state.value = State(running = true, total = items.size)
        scope.launch { drain() }
    }

    @Synchronized
    private fun next(): Source? = pending.removeFirstOrNull().also {
        if (it == null) {
            draining = false
            _state.update { s -> s.copy(running = false, progress = null, activeKey = null) }
        }
    }

    private suspend fun drain() {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSM:dictImport")
        wakeLock.acquire(3 * 60 * 60 * 1000L)
        try {
            deleteOrphanZips()
            while (true) {
                val item = next() ?: break
                _state.update { it.copy(current = it.current + 1, activeKey = item.key) }
                install(item)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            runCatching { onInstalled() }
        }
    }

    /**
     * Zips in `files/dicts` no dictionary points to, and temporary downloads in the cache: both
     * are left behind when an import was killed or cancelled. Only called while no import runs.
     */
    private suspend fun deleteOrphanZips() {
        context.cacheDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".zip") &&
                (it.name.startsWith("drive-") || it.name.startsWith("recommended-") || it.name.startsWith("update-"))
        }?.forEach {
            Log.i(TAG, "deleting stale download ${it.name} (${it.length() / (1024 * 1024)} MB)")
            it.delete()
        }
        val used = db.dictionaryDao().getAll().mapNotNull { it.zipPath }.toSet()
        File(context.filesDir, "dicts").listFiles()?.filter { it.isFile && it.absolutePath !in used }?.forEach {
            Log.i(TAG, "deleting orphan ${it.name} (${it.length() / (1024 * 1024)} MB)")
            it.delete()
        }
    }

    private suspend fun install(item: Source) {
        var name = item.name ?: "dictionary"
        fun report(text: String) = _state.update {
            it.copy(progress = (if (it.total > 1) "(${it.current}/${it.total}) " else "") + text)
        }
        try {
            val entity = when (item) {
                is Source.Local -> importer.import(item.uri) { report(importText(it.title ?: name, it.stage, it.current)) }
                is Source.Drive -> {
                    val zipFile = File(context.cacheDir, "drive-${item.fileId}.zip")
                    try {
                        report("Downloading $name…")
                        downloader.downloadDrive(item.fileId, zipFile) { downloaded, total ->
                            report(downloadText(name, downloaded, total))
                        }?.let { name = it.removeSuffix(".zip") }
                        importer.import(zipFile) { report(importText(it.title ?: name, it.stage, it.current)) }
                    } finally {
                        zipFile.delete()
                    }
                }
                is Source.Recommended -> {
                    val zipFile = File(context.cacheDir, "recommended-${item.dictionary.id}.zip")
                    try {
                        report("Downloading $name…")
                        downloader.download(item.dictionary.url, zipFile) { downloaded, total ->
                            report(downloadText(name, downloaded, total))
                        }
                        importer.importRecommended(item.dictionary, zipFile) {
                            report(importText(it.title ?: name, it.stage, it.current))
                        }
                    } finally {
                        zipFile.delete()
                    }
                }
                is Source.Update -> {
                    val zipFile = File(context.cacheDir, "update-${item.update.dictId}.zip")
                    try {
                        report("Downloading $name…")
                        downloader.download(item.update.downloadUrl, zipFile) { downloaded, total ->
                            report(downloadText(name, downloaded, total))
                        }
                        importer.import(zipFile) { report(importText(it.title ?: name, it.stage, it.current)) }
                    } finally {
                        zipFile.delete()
                    }
                }
            }
            Log.i(TAG, "installed ${entity.title}: ${entity.termCount} terms")
            _state.update { it.copy(installed = it.installed + entity) }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import of $name ran out of memory", e)
            _state.update { it.copy(failures = it.failures + "$name: too large to import (out of memory)") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "import of $name failed", e)
            _state.update { it.copy(failures = it.failures + "$name: ${e.message ?: "import failed"}") }
        }
    }

    companion object {
        private const val TAG = "DSM-Import"

        fun downloadText(name: String, downloaded: Long, total: Long?): String =
            if (total != null && total > 0) {
                "Downloading $name… ${downloaded * 100 / total}%"
            } else {
                "Downloading $name… ${downloaded / (1024 * 1024)} MB"
            }

        fun importText(title: String, stage: String, current: Long): String = buildString {
            append(title)
            if (stage.isNotBlank()) append(" · ").append(stage)
            if (current > 0) append(" · ").append(current)
        }
    }
}
