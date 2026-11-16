package io.github.wilsonnet.droidsentenceminer.manga

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages the local `.cbz` manga library, `.mokuro` OCR sidecars, page decoding, and progress.
 */
class MangaRepository(
    private val context: Context,
    /** Room database storing volumes and cached OCR blocks. */
    val db: MangaDatabase,
    /** On-device OCR engine used when a page has no `.mokuro` data. */
    val ocrEngine: MangaOcrEngine,
    /** Shared in-memory decoded page cache. */
    val bitmaps: MangaBitmapCache = MangaBitmapCache(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mangaDir: File get() = File(context.filesDir, "manga").apply { mkdirs() }
    private val coversDir: File get() = File(mangaDir, "covers").apply { mkdirs() }

    /** Decodes the sorted page entry names stored for [volume]. */
    fun entriesOf(volume: MangaVolumeEntity): List<String> =
        runCatching { json.decodeFromString<List<String>>(volume.entriesJson) }.getOrDefault(emptyList())

    /** Copies [sourceFile] into app storage, extracts cover and `.mokuro` text, and inserts into `manga.db`. */
    @Suppress("InjectDispatcher") // Copies archive to app files and writes Room rows on I/O pool.
    suspend fun importCbzFile(
        sourceFile: File,
        displayName: String,
        sidecarMokuroText: String? = null,
    ): MangaVolumeEntity {
        val inserted =
            withContext(Dispatchers.IO) {
                val inspection = CbzArchive.inspect(sourceFile)
                require(inspection.imageEntries.isNotEmpty()) { "No manga pages (images) found inside $displayName" }

                val idSlug = UUID.randomUUID().toString()
                val destFile = File(mangaDir, "$idSlug.cbz")
                sourceFile.copyTo(destFile, overwrite = true)

                val coverFile = File(coversDir, "$idSlug.jpg")
                val hasCover = CbzArchive.extractCover(destFile, inspection.imageEntries.first(), coverFile)

                val mokuroText =
                    sidecarMokuroText
                        ?: inspection.embeddedMokuroEntry?.let { CbzArchive.readTextEntry(destFile, it) }
                val parsedMokuro = mokuroText?.let { runCatching { MokuroParser.parse(it) }.getOrNull() }

                val title =
                    parsedMokuro?.volume?.takeIf { it.isNotBlank() }
                        ?: displayName.removeSuffix(".cbz").removeSuffix(".zip").ifBlank { "Untitled Manga" }
                val seriesTitle = parsedMokuro?.title.orEmpty()
                val now = System.currentTimeMillis()

                val matchedPages =
                    if (parsedMokuro != null) {
                        MokuroParser.matchPages(parsedMokuro.pages, inspection.imageEntries)
                    } else {
                        emptyMap()
                    }
                val (totalChars, pageEntities) = buildMokuroEntities(volumeId = 0L, matchedPages = matchedPages, now = now)

                val volume =
                    MangaVolumeEntity(
                        title = title,
                        seriesTitle = seriesTitle,
                        cbzPath = destFile.absolutePath,
                        coverPath = if (hasCover) coverFile.absolutePath else null,
                        pageCount = inspection.imageEntries.size,
                        currentPage = 0,
                        hasMokuro = pageEntities.isNotEmpty(),
                        totalChars = totalChars,
                        addedAt = now,
                        lastReadAt = now,
                        entriesJson = json.encodeToString(inspection.imageEntries),
                    )
                val volumeId = db.volumeDao().insertVolume(volume)
                if (pageEntities.isNotEmpty()) {
                    db.pageOcrDao().upsertPagesOcr(pageEntities.map { it.copy(volumeId = volumeId) })
                }
                volume.copy(id = volumeId)
            }
        return inserted
    }

    /** Attaches a `.mokuro` JSON file to an already-imported volume, replacing page OCR caches. */
    @Suppress("InjectDispatcher") // Reads SAF stream via ContentResolver on the I/O dispatcher.
    suspend fun attachMokuro(volume: MangaVolumeEntity, uri: Uri): Int {
        val text =
            withContext(Dispatchers.IO) {
                context.contentResolver
                    .openInputStream(uri)
                    ?.use { stream -> stream.bufferedReader(Charsets.UTF_8).readText() }
                    ?: error("Could not read .mokuro file")
            }
        return attachMokuroText(volume, text)
    }

    /** Parses [mokuroText], matches its pages to [volume], and replaces cached OCR rows. */
    @Suppress("InjectDispatcher") // Parses and persists Room OCR rows on the I/O dispatcher.
    suspend fun attachMokuroText(volume: MangaVolumeEntity, mokuroText: String): Int {
        val count =
            withContext(Dispatchers.IO) {
                val parsed = MokuroParser.parse(mokuroText)
                val entries = entriesOf(volume)
                val matched = MokuroParser.matchPages(parsed.pages, entries)
                require(matched.isNotEmpty()) { "None of the pages in this .mokuro file matched the volume's images" }
                val (totalChars, entities) = buildMokuroEntities(volume.id, matched, System.currentTimeMillis())
                db.pageOcrDao().upsertPagesOcr(entities)
                db.volumeDao().updateMokuroMeta(volume.id, hasMokuro = true, totalChars = totalChars)
                matched.size
            }
        return count
    }

    private fun buildMokuroEntities(
        volumeId: Long,
        matchedPages: Map<Int, MokuroParser.RawMokuroPage>,
        now: Long,
    ): Pair<Int, List<MangaPageOcrEntity>> {
        var totalChars = 0
        val entities =
            matchedPages.entries.map { (pageIdx, rawPage) ->
                val pageOcr = MokuroParser.toPageOcr(rawPage)
                totalChars += pageOcr.blocks.sumOf { it.text.length }
                MangaPageOcrEntity(
                    volumeId = volumeId,
                    pageIndex = pageIdx,
                    imgWidth = pageOcr.imgWidth,
                    imgHeight = pageOcr.imgHeight,
                    blocksJson = MangaOcrEngine.encodeBlocks(pageOcr.blocks),
                    source = MangaPageOcr.SOURCE_MOKURO,
                    updatedAt = now,
                )
            }
        return totalChars to entities
    }

    /** Returns page [pageIndex] of [volume], reading from [bitmaps] cache or decoding from disk. */
    suspend fun decodePage(volume: MangaVolumeEntity, entries: List<String>, pageIndex: Int): Bitmap? {
        val cached = if (pageIndex in entries.indices) bitmaps.get(volume.id, pageIndex) else null
        return cached ?: if (pageIndex in entries.indices) {
            CbzArchive.decodePage(File(volume.cbzPath), entries[pageIndex])?.also {
                bitmaps.put(volume.id, pageIndex, it)
            }
        } else {
            null
        }
    }

    /** Updates [pageIndex] and last-read timestamp for [volumeId]. */
    @Suppress("InjectDispatcher") // Room progress update runs on the I/O dispatcher.
    suspend fun updateProgress(volumeId: Long, pageIndex: Int) {
        withContext(Dispatchers.IO) {
            db.volumeDao().updateProgress(volumeId, pageIndex, System.currentTimeMillis())
        }
    }

    /** Deletes [volume], its cached OCR rows, and its on-disk `.cbz` + cover thumbnail. */
    @Suppress("InjectDispatcher") // Deletes Room rows and local archive files on the I/O dispatcher.
    suspend fun deleteVolume(volume: MangaVolumeEntity) {
        withContext(Dispatchers.IO) {
            bitmaps.evictVolume(volume.id)
            db.pageOcrDao().deleteVolumePages(volume.id)
            db.volumeDao().deleteVolume(volume.id)
            File(volume.cbzPath).delete()
            volume.coverPath?.let { File(it).delete() }
        }
    }
}
