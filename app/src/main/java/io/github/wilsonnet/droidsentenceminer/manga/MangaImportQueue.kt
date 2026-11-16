package io.github.wilsonnet.droidsentenceminer.manga

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import io.github.wilsonnet.droidsentenceminer.dict.DictDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Background `.cbz` + `.mokuro` import queue (local documents and Google Drive), surviving tab
 * switches and screen rotation on [scope].
 */
class MangaImportQueue(
    private val context: Context,
    private val repository: MangaRepository,
    private val downloader: DictDownloader,
    private val scope: CoroutineScope,
) {
    /** Source descriptor for a queued volume import. */
    sealed interface Source {
        /** Display name for progress and error reporting. */
        val name: String

        /** Local Storage Access Framework `.cbz`/`.zip` URI with optional paired `.mokuro` URI. */
        data class Local(
            /** Source `.cbz` document URI. */
            val uri: Uri,
            /** Original filename. */
            override val name: String,
            /** Optional sidecar `.mokuro` document URI. */
            val mokuroUri: Uri? = null,
        ) : Source

        /** Google Drive file ID with optional paired `.mokuro` Drive file ID. */
        data class Drive(
            /** Drive file ID for the `.cbz`/`.zip` archive. */
            val fileId: String,
            /** Archive display filename. */
            override val name: String,
            /** Optional Drive file ID for the `.mokuro` sidecar. */
            val mokuroFileId: String? = null,
        ) : Source

        /** Stable deduplication key for this source. */
        val key: String
            get() =
                when (this) {
                    is Local -> "file:$uri"
                    is Drive -> "drive:$fileId"
                }
    }

    /** UI progress and completion summary for active/recent manga imports. */
    data class State(
        /** True while the worker coroutine is draining queued imports. */
        val isRunning: Boolean = false,
        /** One-based index of the item currently being processed. */
        val current: Int = 0,
        /** Total queued items in the current batch. */
        val total: Int = 0,
        /** Human-readable step progress label. */
        val progress: String? = null,
        /** Successfully imported volumes from the latest batch. */
        val imported: List<MangaVolumeEntity> = emptyList(),
        /** Error messages from failed imports in the latest batch. */
        val failures: List<String> = emptyList(),
    ) {
        /** Short completion message after a batch finishes, or null while idle/running. */
        val summary: String?
            get() =
                when {
                    isRunning -> {
                        null
                    }

                    imported.size == 1 && failures.isEmpty() -> {
                        val suffix = if (imported[0].hasMokuro) ", Mokuro" else ""
                        "Added \"${imported[0].title}\" (${imported[0].pageCount} pages$suffix)"
                    }

                    imported.isNotEmpty() -> {
                        "Added ${imported.size} of $total volumes"
                    }

                    else -> {
                        null
                    }
                }
    }

    private val _state = MutableStateFlow(State())

    /** Observable import queue state. */
    val state: StateFlow<State> = _state

    private val pending = ArrayDeque<Source>()
    private var isDraining = false

    /** Queries the display filename for a SAF [uri], falling back to path segment. */
    fun nameOf(uri: Uri): String {
        val queried =
            runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }
        return queried.getOrNull() ?: uri.lastPathSegment ?: "manga.cbz"
    }

    /**
     * Pairs any picked `.mokuro` URIs with matching `.cbz`/`.zip` URIs by stem name before queuing.
     */
    fun enqueueLocalUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val named = uris.map { it to nameOf(it) }
        val mokuros =
            named
                .filter { (_, n) -> n.endsWith(".mokuro", ignoreCase = true) }
                .associateBy { (_, n) -> stemOf(n) }
        val archives =
            named.filter { (_, n) ->
                n.endsWith(".cbz", ignoreCase = true) || n.endsWith(".zip", ignoreCase = true)
            }
        val sources =
            archives.map { (uri, name) ->
                Source.Local(uri = uri, name = name, mokuroUri = mokuros[stemOf(name)]?.first)
            }
        if (sources.isEmpty()) {
            val msg = "Pick a .cbz or .zip manga archive (optionally alongside its .mokuro file)."
            _state.update { s -> s.copy(failures = listOf(msg)) }
            return
        }
        enqueue(sources)
    }

    /** Queues one or more manga sources and starts the worker if not already draining. */
    @Synchronized
    fun enqueue(items: List<Source>) {
        if (items.isEmpty()) return
        items.filterIsInstance<Source.Local>().forEach { item ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        pending.addAll(items)
        if (isDraining) {
            _state.update { it.copy(total = it.total + items.size) }
            return
        }
        isDraining = true
        _state.value = State(isRunning = true, total = items.size)
        scope.launch { drain() }
    }

    @Synchronized
    private fun next(): Source? {
        val item = pending.removeFirstOrNull()
        if (item == null) {
            isDraining = false
            _state.update { s -> s.copy(isRunning = false, progress = null) }
        }
        return item
    }

    private suspend fun drain() {
        val wakeLock =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSM:mangaImport")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            cleanupTempFiles()
            while (true) {
                val item = next() ?: break
                _state.update { it.copy(current = it.current + 1) }
                importOne(item)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun cleanupTempFiles() {
        val cacheFiles = context.cacheDir.listFiles() ?: return
        cacheFiles
            .filter { it.isFile && (it.name.startsWith("manga-dl-") || it.name.startsWith("manga-mokuro-")) }
            .forEach { it.delete() }
    }

    private suspend fun importOne(item: Source) {
        var displayName = item.name

        fun report(text: String) {
            _state.update { it.copy(progress = (if (it.total > 1) "(${it.current}/${it.total}) " else "") + text) }
        }
        try {
            val entity =
                when (item) {
                    is Source.Local -> {
                        importLocalItem(item, displayName, ::report)
                    }

                    is Source.Drive -> {
                        importDriveItem(
                            item = item,
                            initialName = displayName,
                            onNameResolved = { displayName = it },
                            report = ::report,
                        )
                    }
                }
            _state.update { it.copy(imported = it.imported + entity) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            recordFailure(displayName, e)
        } catch (e: IllegalArgumentException) {
            recordFailure(displayName, e)
        } catch (e: IllegalStateException) {
            recordFailure(displayName, e)
        }
    }

    private fun recordFailure(displayName: String, error: Throwable) {
        Log.w(TAG, "Failed importing $displayName", error)
        _state.update { it.copy(failures = it.failures + "$displayName: ${error.message ?: "import failed"}") }
    }

    @Suppress("InjectDispatcher") // SAF input stream copy runs on the I/O dispatcher.
    private suspend fun importLocalItem(
        item: Source.Local,
        displayName: String,
        report: (String) -> Unit,
    ): MangaVolumeEntity {
        val tempFile = File(context.cacheDir, "manga-dl-${System.currentTimeMillis()}.cbz")
        return try {
            report("Copying $displayName…")
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open $displayName")
            }
            val mokuroText =
                item.mokuroUri?.let { mUri ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(mUri)?.use { stream ->
                            stream.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }
                }
            report("Importing $displayName…")
            repository.importCbzFile(tempFile, displayName, mokuroText)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun importDriveItem(
        item: Source.Drive,
        initialName: String,
        onNameResolved: (String) -> Unit,
        report: (String) -> Unit,
    ): MangaVolumeEntity {
        var displayName = initialName
        val cbzTemp = File(context.cacheDir, "manga-dl-${item.fileId}.cbz")
        val mokuroTemp = File(context.cacheDir, "manga-mokuro-${item.fileId}.mokuro")
        return try {
            report("Downloading $displayName…")
            val resolvedName =
                downloader.downloadDrive(item.fileId, cbzTemp) { downloaded, total ->
                    report(downloadText(displayName, downloaded, total))
                }
            if (resolvedName != null) {
                displayName = resolvedName
                onNameResolved(resolvedName)
            }
            val mokuroText =
                item.mokuroFileId?.let { mId ->
                    report("Downloading .mokuro for $displayName…")
                    try {
                        downloader.downloadDrive(mId, mokuroTemp)
                        mokuroTemp.readText(Charsets.UTF_8)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.w(TAG, "Optional Drive .mokuro fetch failed for $displayName", e)
                        null
                    }
                }
            report("Importing $displayName…")
            repository.importCbzFile(cbzTemp, displayName, mokuroText)
        } finally {
            cbzTemp.delete()
            mokuroTemp.delete()
        }
    }

    /** Companion helpers and formatting utilities. */
    companion object {
        private const val TAG = "DSM-MangaImport"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        /** Lowercased filename without its extension, used to match `.cbz` and `.mokuro` pairs. */
        fun stemOf(filename: String): String = filename.substringBeforeLast('.').trim().lowercase()
    }
}

private const val PERCENT_MAX = 100
private const val BYTES_PER_MB = 1024L * 1024L

internal fun downloadText(name: String, downloaded: Long, total: Long?): String {
    val progress =
        if (total != null && total > 0) {
            "${downloaded * PERCENT_MAX / total}%"
        } else {
            "${downloaded / BYTES_PER_MB} MB"
        }
    return "Downloading $name… $progress"
}
