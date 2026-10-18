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

    private val sources = listOf(
        DETECTOR to "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/main/$DETECTOR",
        RECOGNIZER to "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/main/$RECOGNIZER",
    )

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(context: Context): Boolean = sources.all { (name, _) -> File(dir(context), name).let { it.isFile && it.length() > 1_000_000 } }

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
