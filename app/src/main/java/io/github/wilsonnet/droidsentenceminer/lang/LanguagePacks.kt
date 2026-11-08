package io.github.wilsonnet.droidsentenceminer.lang

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * A language's downloadable data: OCR models and anything else too big to ship in the APK.
 * Published as a zip on this repo's GitHub Releases (tag `lang-<code>-<version>`, built by
 * `tools/lang-packs/`), downloaded only when the user studies that language and asks for it.
 *
 * Zip layout: `pack.json` (`{"language","version","name"}`) plus the files its components name —
 * today `paddle.json` + models for [io.github.wilsonnet.droidsentenceminer.ocr.PaddleOcr].
 */
data class LanguagePack(
    val language: String,
    val version: Int,
    val name: String,
    val description: String,
    val url: String,
    val sha256: String,
    val sizeMb: Double,
)

object LanguagePacks {
    private const val MANIFEST = "pack.json"

    fun dir(context: Context, language: String): File = File(context.filesDir, "lang/$language")

    /** Installed version of [language]'s pack, or 0. */
    fun installedVersion(context: Context, language: String): Int = runCatching {
        JSONObject(File(dir(context, language), MANIFEST).readText()).optInt("version", 0)
    }.getOrDefault(0)

    fun isInstalled(context: Context, pack: LanguagePack): Boolean = installedVersion(context, pack.language) >= pack.version

    /** Downloads, verifies (SHA-256) and unpacks [pack], replacing any older version. */
    suspend fun install(context: Context, pack: LanguagePack, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "lang-${pack.language}-${pack.version}.zip")
        try {
            download(pack.url, zip, onProgress)
            val actual = sha256(zip)
            check(actual.equals(pack.sha256, ignoreCase = true)) { "Download corrupted (SHA-256 $actual)" }
            val staging = File(context.filesDir, "lang/.${pack.language}-staging").apply { deleteRecursively(); mkdirs() }
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val out = File(staging, entry.name).canonicalFile
                    check(out.path.startsWith(staging.canonicalPath + File.separator)) { "Bad entry ${entry.name}" }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            check(File(staging, MANIFEST).isFile) { "Not a language pack" }
            val target = dir(context, pack.language)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not install the pack" }
        } finally {
            zip.delete()
        }
    }

    fun remove(context: Context, language: String) {
        dir(context, language).deleteRecursively()
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    }

    private fun download(url: String, file: File, onProgress: (Int) -> Unit) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "DroidSentenceMiner").build()).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Empty download")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
