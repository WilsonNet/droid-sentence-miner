package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Random-access `.cbz`/`.zip` archive reader for manga volumes.
 * Sorts page entries with natural numeric ordering and decodes pages downsampled when needed.
 */
object CbzArchive {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "bmp", "gif")
    private const val MAX_PAGE_SIDE = 2600
    private const val COVER_WIDTH = 360
    private const val COVER_JPEG_QUALITY = 85

    /** Sorted image entry paths and optional embedded `.mokuro` path found inside a `.cbz` archive. */
    data class Inspection(
        /** Naturally sorted image file entry names inside the archive. */
        val imageEntries: List<String>,
        /** Entry name of the first embedded `.mokuro` file, or null. */
        val embeddedMokuroEntry: String?,
    )

    /** Lists sorted image entries and any embedded `.mokuro` file inside [cbzFile]. */
    fun inspect(cbzFile: File): Inspection {
        ZipFile(cbzFile).use { zip ->
            val images = ArrayList<String>()
            var mokuroEntry: String? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || isHiddenOrMetadata(entry.name)) continue
                val name = entry.name
                val ext = name.substringAfterLast('.', "").lowercase()
                when {
                    ext in IMAGE_EXTENSIONS -> images += name
                    ext == "mokuro" && mokuroEntry == null -> mokuroEntry = name
                }
            }
            images.sortWith(::naturalCompare)
            return Inspection(imageEntries = images, embeddedMokuroEntry = mokuroEntry)
        }
    }

    /** Reads the text of an embedded `.mokuro` entry inside [cbzFile]. */
    fun readTextEntry(cbzFile: File, entryName: String): String? {
        val result =
            runCatching {
                ZipFile(cbzFile).use { zip ->
                    zip.getEntry(entryName)?.let { entry ->
                        zip
                            .getInputStream(entry)
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }
                }
            }
        return result.getOrNull()
    }

    /** Decodes page [entryName] from [cbzFile], downsampling if its longest side exceeds [maxSide]. */
    @Suppress("InjectDispatcher") // Decodes compressed CBZ bitmap entries off the main thread.
    suspend fun decodePage(cbzFile: File, entryName: String, maxSide: Int = MAX_PAGE_SIDE): Bitmap? {
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    ZipFile(cbzFile).use { zip -> decodeZipEntry(zip, entryName, maxSide) }
                }
            }
        return result.getOrNull()
    }

    /** Decodes the cover image and writes a compact JPEG thumbnail to [destFile]. */
    @Suppress("InjectDispatcher") // Extracts cover thumbnail from local CBZ storage on I/O pool.
    suspend fun extractCover(cbzFile: File, entryName: String, destFile: File): Boolean {
        val hasCover =
            withContext(Dispatchers.IO) {
                val bitmap = decodePage(cbzFile, entryName, maxSide = COVER_WIDTH * 2)
                if (bitmap == null) {
                    false
                } else {
                    try {
                        writeCoverThumbnail(bitmap, destFile)
                        true
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        return hasCover
    }

    private fun decodeZipEntry(zip: ZipFile, entryName: String, maxSide: Int): Bitmap? {
        val entry = zip.getEntry(entryName)
        var bitmap: Bitmap? = null
        if (entry != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (max(a = bounds.outWidth, b = bounds.outHeight) / (sample * 2) >= maxSide) {
                    sample *= 2
                }
                val opts =
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }
        return bitmap
    }

    private fun writeCoverThumbnail(bitmap: Bitmap, destFile: File) {
        val targetW = COVER_WIDTH.coerceAtMost(bitmap.width)
        val targetH = (bitmap.height * (targetW.toFloat() / bitmap.width)).roundToInt().coerceAtLeast(1)
        val scaled =
            if (targetW < bitmap.width) {
                bitmap.scale(width = targetW, height = targetH)
            } else {
                bitmap
            }
        try {
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun isHiddenOrMetadata(name: String): Boolean {
        val parts = name.replace(oldChar = '\\', newChar = '/').split('/')
        return parts.any { it.startsWith(".") || it == "__MACOSX" }
    }

    /** Natural alphanumeric comparison so `page2.jpg` precedes `page10.jpg`. */
    fun naturalCompare(a: String, b: String): Int {
        val la = a.lowercase()
        val lb = b.lowercase()
        var ia = 0
        var ib = 0
        var diff = 0
        while (diff == 0 && ia < la.length && ib < lb.length) {
            val ca = la[ia]
            val cb = lb[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val na = digitEnd(la, ia)
                val nb = digitEnd(lb, ib)
                diff =
                    compareNumericChunks(
                        chunkA = la.substring(startIndex = ia, endIndex = na),
                        chunkB = lb.substring(startIndex = ib, endIndex = nb),
                    )
                ia = na
                ib = nb
            } else {
                diff = ca.compareTo(cb)
                ia++
                ib++
            }
        }
        return if (diff != 0) diff else la.length.compareTo(lb.length)
    }

    private fun digitEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isDigit()) i++
        return i
    }

    private fun compareNumericChunks(chunkA: String, chunkB: String): Int {
        val numA = chunkA.trimStart('0')
        val numB = chunkB.trimStart('0')
        val byLength = numA.length.compareTo(numB.length)
        if (byLength != 0) return byLength
        val byValue = numA.compareTo(numB)
        return if (byValue != 0) byValue else chunkA.length.compareTo(chunkB.length)
    }
}

/** In-memory LRU bitmap cache for decoded CBZ pages (48 MB budget). */
class MangaBitmapCache {
    private val cache =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    /** Returns the cached page bitmap if present in memory. */
    @Suppress("ExplicitCollectionElementAccessMethod") // android.util.LruCache has no Kotlin [] operator.
    fun get(volumeId: Long, pageIndex: Int): Bitmap? = cache.get(key(volumeId, pageIndex))

    /** Stores [bitmap] in the in-memory page cache. */
    fun put(volumeId: Long, pageIndex: Int, bitmap: Bitmap) {
        cache.put(key(volumeId, pageIndex), bitmap)
    }

    /** Drops all cached pages belonging to [volumeId]. */
    fun evictVolume(volumeId: Long) {
        val prefix = "$volumeId:"
        cache
            .snapshot()
            .keys
            .filter { it.startsWith(prefix) }
            .forEach { cache.remove(it) }
    }

    private fun key(volumeId: Long, pageIndex: Int): String = "$volumeId:$pageIndex"

    private companion object {
        const val CACHE_MB = 48
        const val BYTES_PER_MB = 1024 * 1024
        const val CACHE_BYTES = CACHE_MB * BYTES_PER_MB
    }
}
