package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Dictionary images (glossary `img` nodes) served from the imported zip.
 *
 * The popup page points images at `https://dict-media.invalid/<dictId>/<path>` ([url]) and the
 * WebView answers those requests from [intercept] on its own IO thread, so building the popup
 * never touches the zip and the page opens before any image is read. Files are extracted once
 * into `cacheDir/dict-images/<dictId>`; images bigger than [MAX_DIMENSION] px are downscaled
 * there (WebP) so a huge scan doesn't cost a full-size decode in the popup or bloat an Anki card.
 */
object DictMedia {
    const val HOST = "dict-media.invalid"

    /**
     * Base URL for the popup documents: same origin as the images, so the WebView neither blocks them
     * as network loads from a `file://` page nor needs CORS for the monochrome CSS masks.
     */
    const val PAGE_BASE_URL = "https://$HOST/"
    private const val TAG = "DSM-DictMedia"

    /** Longest side kept for raster images; dictionary pictures are shown at a few hundred CSS px. */
    const val MAX_DIMENSION = 1600

    /** Zip path per dictionary id, filled by whoever renders a page with that dictionary's images. */
    private val zipPaths = ConcurrentHashMap<Long, String>()

    private class OpenZip(val path: String, val modified: Long, val zip: ZipFile) {
        /** Lower-cased entry name → entry, built on the first case-insensitive miss. */
        @Volatile var lowerNames: Map<String, ZipEntry>? = null
    }

    /** A few open zips (central directory parsed once, not per image); most recently used last. */
    private val openZips = LinkedHashMap<Long, OpenZip>(4, 0.75f, true)
    private const val MAX_OPEN_ZIPS = 3

    data class MediaFile(val file: File, val mimeType: String)

    fun register(dict: DictionaryEntity) {
        dict.zipPath?.let { zipPaths[dict.id] = it }
    }

    /** Page URL for [path] of [dict] (registers the dictionary for [intercept]). */
    fun url(dict: DictionaryEntity, path: String): String? {
        if (dict.zipPath == null || path.isEmpty()) return null
        register(dict)
        return urlFor(dict.id, path)
    }

    fun urlFor(dictId: Long, path: String): String = "https://$HOST/$dictId/${encodeSegment(path)}"

    /** (dictId, path) of a [url], or null for any other URL. */
    fun parseUrl(url: String): Pair<Long, String>? {
        val prefix = "https://$HOST/"
        if (!url.startsWith(prefix)) return null
        val rest = url.substring(prefix.length).substringBefore('?').substringBefore('#')
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val id = rest.substring(0, slash).toLongOrNull() ?: return null
        val path = runCatching { URLDecoder.decode(rest.substring(slash + 1).replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return id to path
    }

    /** Percent-encodes everything but RFC 3986 unreserved characters (so `/` stays inside one segment). */
    internal fun encodeSegment(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                append(c.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
    }

    /** For `WebViewClient.shouldInterceptRequest`: serves [url] images, null for anything else. */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != HOST) return null
        val (dictId, path) = parseUrl(request.url.toString()) ?: return notFound()
        val zipPath = zipPaths[dictId] ?: return notFound()
        val media = runCatching { file(context, dictId, zipPath, path) }
            .onFailure { Log.w(TAG, "dict $dictId: $path", it) }
            .getOrNull() ?: return notFound()
        return WebResourceResponse(media.mimeType, null, 200, "OK", RESPONSE_HEADERS, media.file.inputStream())
    }

    // CSS masks (monochrome images) are CORS requests, and the page's origin is file://.
    private val RESPONSE_HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", RESPONSE_HEADERS, "".byteInputStream())

    /** The image for Anki media (same downscaled copy the popup shows). */
    fun file(context: Context, dict: DictionaryEntity, path: String): MediaFile? {
        val zipPath = dict.zipPath ?: return null
        return runCatching { file(context, dict.id, zipPath, path) }.getOrNull()
    }

    /** Stable Anki media name for [path] of [dict]: same image → same file, across cards. */
    fun ankiName(dict: DictionaryEntity, path: String, media: MediaFile): String {
        val hash = sha1Hex("${YomitanImporter.baseDictionaryTitle(dict.title)}\n$path").take(16)
        return "dsm_dict_$hash.${extensionOf(media.file.name)}"
    }

    private fun file(context: Context, dictId: Long, zipPath: String, path: String): MediaFile? {
        val dir = File(context.cacheDir, "dict-images/$dictId")
        val hash = sha1Hex(path).take(20)
        val ext = extensionOf(path)
        val scaled = File(dir, "$hash-s.webp")
        if (scaled.length() > 0) return MediaFile(scaled, "image/webp")
        val plain = File(dir, "$hash.$ext")
        if (plain.length() > 0) return MediaFile(plain, mimeOf(ext))

        val bytes = readEntry(dictId, zipPath, path) ?: return null
        dir.mkdirs()
        val downscaled = if (ext in DOWNSCALABLE) downscale(bytes) else null
        val (target, mime) = if (downscaled != null) scaled to "image/webp" else plain to mimeOf(ext)
        // Two requests for one image may race: write aside, then rename into place.
        val tmp = File(dir, "${target.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(downscaled ?: bytes)
        if (!tmp.renameTo(target)) tmp.delete()
        return MediaFile(target, mime)
    }

    private fun readEntry(dictId: Long, zipPath: String, path: String): ByteArray? {
        val open = openZip(dictId, zipPath) ?: return null
        val candidates = buildList {
            add(path)
            runCatching { add(URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")) }
            add(path.removePrefix("/"))
            add(path.removePrefix("./"))
            add("images/$path")
        }.distinct()
        val zip = open.zip
        val entry = candidates.firstNotNullOfOrNull { zip.getEntry(it) }
            ?: run {
                val lower = open.lowerNames ?: zip.entries().asSequence()
                    .associateBy { it.name.lowercase() }
                    .also { open.lowerNames = it }
                candidates.firstNotNullOfOrNull { lower[it.lowercase()] }
            }
            ?: return null
        if (entry.isDirectory) return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun openZip(dictId: Long, zipPath: String): OpenZip? = synchronized(openZips) {
        val file = File(zipPath)
        if (!file.exists()) return null
        openZips[dictId]?.let { open ->
            if (open.path == zipPath && open.modified == file.lastModified()) return open
            openZips.remove(dictId)
            runCatching { open.zip.close() }
        }
        val open = OpenZip(zipPath, file.lastModified(), ZipFile(file))
        openZips[dictId] = open
        while (openZips.size > MAX_OPEN_ZIPS) {
            val eldest = openZips.entries.first()
            openZips.remove(eldest.key)
            runCatching { eldest.value.zip.close() }
        }
        open
    }

    /** Drops the open zip and the extracted images of [dictId] (dictionary updated or deleted). */
    fun invalidate(context: Context?, dictId: Long) {
        synchronized(openZips) { openZips.remove(dictId)?.let { runCatching { it.zip.close() } } }
        zipPaths.remove(dictId)
        context?.let { runCatching { File(it.cacheDir, "dict-images/$dictId").deleteRecursively() } }
    }

    /** WebP of [bytes] with the longest side at most [MAX_DIMENSION], or null if it is small enough (or not decodable). */
    private fun downscale(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0 || maxOf(w, h) <= MAX_DIMENSION) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        return try {
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(format, 85, out)
                out.toByteArray()
            }.takeIf { it.isNotEmpty() && it.size < bytes.size }
        } finally {
            bitmap.recycle()
        }
    }

    /** GIF/SVG/AVIF are passed through: animation and vectors would be lost, and AVIF is already small. */
    private val DOWNSCALABLE = setOf("png", "jpg", "jpeg", "webp", "bmp")

    internal fun extensionOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }

    internal fun mimeOf(ext: String): String = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "tif", "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
