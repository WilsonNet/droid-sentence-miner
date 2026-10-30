package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Reads auxiliary files (glossary images, styles, index metadata) from an imported dictionary zip. */
object DictArchive {
    private val stylesCache = ConcurrentHashMap<Long, String>()
    /** Extracted (and, if huge, downscaled) glossary image; see [DictMedia]. */
    fun imageFile(context: Context, dict: DictionaryEntity, path: String): File? = DictMedia.file(context, dict, path)?.file

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

    /** Forgets [dictId]'s cached styles and closes its open image zip (call before deleting/replacing it). */
    fun invalidate(dictId: Long) {
        stylesCache.remove(dictId)
        DictMedia.invalidate(null, dictId)
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
