package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Reads auxiliary files (glossary images, styles) from an imported dictionary zip. */
object DictArchive {

    fun imageFile(context: Context, dict: DictionaryEntity, path: String): File? {
        val zipPath = dict.zipPath ?: return null
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return null

        val safeName = path.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val outDir = File(context.cacheDir, "dict-images/${dict.id}").apply { mkdirs() }
        val outFile = File(outDir, safeName)
        if (outFile.exists() && outFile.length() > 0) return outFile

        val candidates = buildList {
            add(path)
            runCatching { add(URLDecoder.decode(path, "UTF-8")) }
            add(path.removePrefix("/"))
            add(path.removePrefix("./"))
            add("images/$path")
        }

        return runCatching {
            ZipFile(zipFile).use { zip ->
                val entry = candidates.firstNotNullOfOrNull { candidate ->
                    zip.getEntry(candidate) ?: zip.entries().toList()
                        .firstOrNull { it.name.equals(candidate, ignoreCase = true) }
                } ?: return null
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile
            }
        }.getOrNull()
    }

    fun styles(context: Context, dict: DictionaryEntity): String? {
        val zipPath = dict.zipPath ?: return dict.styles
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return dict.styles
        return runCatching {
            ZipFile(zipFile).use { zip ->
                zip.readEntryText("styles.css")
            }
        }.getOrNull() ?: dict.styles
    }
}
