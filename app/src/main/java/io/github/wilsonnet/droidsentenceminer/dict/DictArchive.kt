package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Reads auxiliary files (glossary images, styles, index metadata) from an imported dictionary zip. */
object DictArchive {
    private val stylesCache = ConcurrentHashMap<Long, String>()
    private val unsafeFileChars = Regex("[^A-Za-z0-9._-]")

    /**
     * An imported zip kept open: opening one reads its whole central directory, which for an
     * image-heavy dictionary (tens of thousands of entries) costs more than the lookup itself.
     * [lowerNames] (lowercased name -> name) is built on the first case-insensitive miss only.
     */
    private class OpenZip(val dictId: Long, val zip: ZipFile) {
        @Volatile var lowerNames: Map<String, String>? = null
    }

    private val openZips = ConcurrentHashMap<String, OpenZip>()

    /** "zipPath\u0000path" of images not in their zip, so a broken reference isn't searched on every popup. */
    private val missingImages = ConcurrentHashMap.newKeySet<String>()

    fun imageFile(context: Context, dict: DictionaryEntity, path: String): File? {
        val zipPath = dict.zipPath ?: return null

        val safeName = path.replace(unsafeFileChars, "_").takeLast(80)
        val outDir = File(context.cacheDir, "dict-images/${dict.id}")
        val outFile = File(outDir, safeName)
        if (outFile.length() > 0) return outFile
        val missKey = zipPath + '\u0000' + path
        if (missKey in missingImages) return null

        val candidates = buildList {
            add(path)
            runCatching { add(URLDecoder.decode(path, "UTF-8")) }
            add(path.removePrefix("/"))
            add(path.removePrefix("./"))
            add("images/$path")
        }

        return runCatching {
            val open = openZip(dict.id, zipPath) ?: return null
            val zip = open.zip
            val entry = candidates.firstNotNullOfOrNull { zip.getEntry(it) }
                ?: run {
                    val lower = open.lowerNames ?: zip.entries().asSequence().associateBy({ it.name.lowercase() }, { it.name })
                        .also { open.lowerNames = it }
                    candidates.firstNotNullOfOrNull { lower[it.lowercase()]?.let(zip::getEntry) }
                }
            if (entry == null) {
                missingImages += missKey
                return null
            }
            outDir.mkdirs()
            // Write then rename, so a concurrent popup never reads a half-written image.
            val tmp = File(outDir, "$safeName.${Thread.currentThread().id}.tmp")
            zip.getInputStream(entry).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(outFile)) tmp.delete()
            outFile.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun openZip(dictId: Long, zipPath: String): OpenZip? {
        openZips[zipPath]?.let { return it }
        val file = File(zipPath)
        if (!file.exists()) return null
        val opened = OpenZip(dictId, ZipFile(file))
        val raced = openZips.putIfAbsent(zipPath, opened)
        if (raced != null) {
            runCatching { opened.zip.close() }
            return raced
        }
        return opened
    }

    fun styles(context: Context, dict: DictionaryEntity): String? {
        dict.styles?.takeIf { it.isNotBlank() }?.let { return it }
        stylesCache[dict.id]?.let { return it.ifEmpty { null } }
        val zipPath = dict.zipPath ?: return null
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return null
        val loaded = runCatching {
            ZipFile(zipFile).use { zip ->
                zip.readEntryText("styles.css")
            }
        }.getOrNull()
        stylesCache[dict.id] = loaded ?: ""
        return loaded
    }

    /** Forgets [dictId]'s cached styles and closes its open zip (call before deleting/replacing it). */
    fun invalidate(dictId: Long) {
        stylesCache.remove(dictId)
        openZips.entries.filter { it.value.dictId == dictId }.forEach { (path, open) ->
            if (openZips.remove(path, open)) runCatching { open.zip.close() }
            missingImages.removeAll { it.startsWith(path + '\u0000') }
        }
    }

    /** Reads `index.json` from an imported dictionary zip (used to check `indexUrl` / `downloadUrl` for updates). */
    fun index(dict: DictionaryEntity): DictionaryIndex? {
        val zipPath = dict.zipPath ?: return null
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return null
        return runCatching {
            ZipFile(zipFile).use { zip ->
                zip.readEntryText("index.json")?.let { YomitanIndexParser.parse(it) }
            }
        }.getOrNull()
    }
}
