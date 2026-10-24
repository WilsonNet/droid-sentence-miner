package io.github.wilsonnet.droidsentenceminer.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads the meikiocr models (LGPL-3.0, ~31 MB) from Hugging Face on demand instead of
 * bundling them in the APK, like the dictionaries.
 */
object MeikiModels {
    const val DETECTOR = "meiki.text.detect.v0.1.960x544.onnx"
    const val RECOGNIZER = "meiki.text.rec.v0.960x32.onnx"

    /**
     * Shorter detector inputs shipped in assets/meiki (built by tools/meiki-variants): graphs only,
     * their weights are read from [DETECTOR] itself, so they only work next to that exact file.
     */
    val VARIANT_HEIGHTS = listOf(160, 224, 288, 352, 448)

    /** [RECOGNIZER] accepting any input width (multiple of 32), same weights. */
    const val RECOGNIZER_ANY_WIDTH = "meiki.text.rec.v0.32xW.onnx"

    /** SHA-256 of the originals the variants were built from. */
    private const val RECOGNIZER_SHA256 = "3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000"
    private const val DETECTOR_SHA256 = "40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b"

    fun variant(height: Int): String = "meiki.text.detect.v0.1.960x$height.onnx"

    @Volatile
    private var variantsChecked = false

    private val sources = listOf(
        DETECTOR to "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/main/$DETECTOR",
        RECOGNIZER to "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/main/$RECOGNIZER",
    )

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(context: Context): Boolean = sources.all { (name, _) -> File(dir(context), name).let { it.isFile && it.length() > 1_000_000 } }

    /**
     * Copies the model variants next to the downloaded originals (once per process), or removes
     * them if an original isn't the file they were built from (their weights would be garbage).
     */
    fun installVariants(context: Context) {
        if (variantsChecked) return
        synchronized(this) {
            if (variantsChecked) return
            val dir = dir(context)
            if (!File(dir, DETECTOR).isFile || !File(dir, RECOGNIZER).isFile) return
            install(context, dir, DETECTOR, DETECTOR_SHA256, VARIANT_HEIGHTS.map { variant(it) })
            install(context, dir, RECOGNIZER, RECOGNIZER_SHA256, listOf(RECOGNIZER_ANY_WIDTH))
            variantsChecked = true
        }
    }

    private fun install(context: Context, dir: File, original: String, sha256: String, variants: List<String>) {
        val matches = runCatching { sha256(File(dir, original)) == sha256 }.getOrDefault(false)
        variants.forEach { name ->
            val file = File(dir, name)
            if (!matches) {
                file.delete()
                return@forEach
            }
            runCatching {
                context.assets.open("meiki/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }.onFailure { file.delete() }
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    /** Downloads both models; [onProgress] gets 0..100. Returns null on success, else an error. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val target = dir(context)
        try {
            sources.forEachIndexed { index, (name, url) ->
                val file = File(target, name)
                if (file.isFile && file.length() > 1_000_000) return@forEachIndexed
                val tmp = File(target, "$name.part")
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext "HTTP ${response.code} for $name"
                    val body = response.body ?: return@withContext "Empty response for $name"
                    val total = body.contentLength().takeIf { it > 0 } ?: 1L
                    var read = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                onProgress(((index + read.toDouble() / total) * 100 / sources.size).toInt())
                            }
                        }
                    }
                }
                if (!tmp.renameTo(file)) return@withContext "Could not save $name"
            }
            onProgress(100)
            null
        } catch (e: Exception) {
            e.message ?: "Download failed"
        }
    }
}
