package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background pre-OCR runner for an entire `.cbz` volume (mirrors Manatan's `jobs.rs`):
 * processes pages concurrently (bounded to 2 on Android) and stores every page into `manga.db`
 * so offline reading later feels instant, like having a `.mokuro` file.
 */
class MangaPreOcrQueue(
    private val repository: MangaRepository,
    private val scope: CoroutineScope,
    private val hasLocalModels: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Progress snapshot for one active or finished volume pre-OCR job. */
    data class VolumeProgress(
        /** Number of pages prepared so far. */
        val done: Int,
        /** Total pages in the volume. */
        val total: Int,
        /** True while the background job is running. */
        val isRunning: Boolean,
        /** Optional failure reason when the batch stopped with an error. */
        val error: String? = null,
    )

    private val _progress = MutableStateFlow<Map<Long, VolumeProgress>>(emptyMap())

    /** Map of volume ID to its current pre-OCR progress. */
    val progress: StateFlow<Map<Long, VolumeProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Starts pre-OCR for all unprepared pages of [volume] using [mode]. */
    fun start(volume: MangaVolumeEntity, mode: MangaOcrMode = MangaOcrMode.AUTO) {
        if (jobs[volume.id]?.isActive == true) return
        val entries = repository.entriesOf(volume)
        if (entries.isEmpty()) return
        updateProgress(volume.id, VolumeProgress(done = 0, total = entries.size, isRunning = true))
        val job =
            scope.launch {
                runVolumeBatch(volume = volume, entries = entries, mode = mode)
            }
        jobs[volume.id] = job
    }

    /** Cancels an active pre-OCR job for [volumeId], keeping all already-saved pages. */
    fun cancel(volumeId: Long) {
        jobs.remove(volumeId)?.cancel()
        val prev = _progress.value[volumeId]
        if (prev != null && prev.isRunning) {
            updateProgress(volumeId, prev.copy(isRunning = false))
        }
    }

    private suspend fun runVolumeBatch(volume: MangaVolumeEntity, entries: List<String>, mode: MangaOcrMode) {
        val semaphore = Semaphore(PERMITS_ON_ANDROID)
        val doneCount = AtomicInteger(0)
        var lastError: String? = null
        try {
            coroutineScope {
                for (pageIndex in entries.indices) {
                    launch {
                        semaphore.withPermit {
                            val pageError =
                                processSinglePage(volume = volume, entries = entries, pageIndex = pageIndex, mode = mode)
                            if (pageError != null) lastError = pageError
                            val done = doneCount.incrementAndGet()
                            updateProgress(
                                volume.id,
                                VolumeProgress(done = done, total = entries.size, isRunning = true),
                            )
                        }
                    }
                }
            }
            updateProgress(
                volume.id,
                VolumeProgress(
                    done = doneCount.get(),
                    total = entries.size,
                    isRunning = false,
                    error = lastError,
                ),
            )
        } finally {
            jobs.remove(volume.id)
        }
    }

    private suspend fun processSinglePage(
        volume: MangaVolumeEntity,
        entries: List<String>,
        pageIndex: Int,
        mode: MangaOcrMode,
    ): String? {
        val existing = repository.ocrEngine.getCached(volume.id, pageIndex)
        var failure: String? = null
        if (!isAlreadyPrepared(existing?.source, mode)) {
            val bitmap = withContext(io) { CbzArchive.decodePage(File(volume.cbzPath), entries[pageIndex]) }
            if (bitmap != null) {
                failure = runPageOcrSafely(volume = volume, pageIndex = pageIndex, bitmap = bitmap, mode = mode)
            }
        }
        return failure
    }

    private suspend fun runPageOcrSafely(
        volume: MangaVolumeEntity,
        pageIndex: Int,
        bitmap: Bitmap,
        mode: MangaOcrMode,
    ): String? {
        val result =
            try {
                repository.ocrEngine.recognizeAndStore(
                    volumeId = volume.id,
                    pageIndex = pageIndex,
                    bitmap = bitmap,
                    mode = mode,
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Pre-OCR failed at page $pageIndex", e)
                e.message ?: "OCR network error"
            } finally {
                bitmap.recycle()
            }
        return result
    }

    // Pages read by another engine than the one asked for are redone (picking an engine and
    // preparing the volume is how a reader switches it); mokuro and hand edits are always kept.
    private fun isAlreadyPrepared(source: String?, mode: MangaOcrMode): Boolean {
        val isAutoOnLens = mode == MangaOcrMode.AUTO && !hasLocalModels()
        val isReady =
            when (source) {
                MangaPageOcr.SOURCE_MOKURO,
                MangaPageOcr.SOURCE_EDITED,
                -> true

                MangaPageOcr.SOURCE_LOCAL -> mode == MangaOcrMode.LOCAL || mode == MangaOcrMode.AUTO

                MangaPageOcr.SOURCE_LENS -> mode == MangaOcrMode.LENS || isAutoOnLens

                MangaPageOcr.SOURCE_OCR -> mode == MangaOcrMode.MLKIT

                else -> false
            }
        return isReady
    }

    private fun updateProgress(volumeId: Long, value: VolumeProgress) {
        _progress.update { current -> current + (volumeId to value) }
    }

    private companion object {
        const val TAG = "DSM-MangaPreOcr"
        const val PERMITS_ON_ANDROID = 2
    }
}
