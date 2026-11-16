package io.github.wilsonnet.droidsentenceminer.manga

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Downloads the on-device manga OCR models (~34 MB, Apache-2.0) from Hugging Face on demand, pinned
 * to a commit and checked by SHA-256:
 * - PP-OCRv6_manga (Kellenok): a PaddleOCR line detector + recognizer fine-tuned on Manga109 that
 *   reads vertical and horizontal Japanese,
 * - comic-text-and-bubble-detector v4-s int8 (ogkalu): RT-DETR text/bubble regions used to group
 *   lines into speech bubbles.
 *
 * Measured against hand-checked bubbles (`tools/manga-eval`): 88/99 bubbles exact, like mokuro.
 */
object MangaOcrModels {
    /**
     * PP-OCRv6_manga text-line detector (FP32 ONNX: the FP16 export's native ARM fp16 kernels
     * turned whole pages into one text blob on the Xiaomi Pad 7 and ran the recognizer 5× slower).
     */
    const val DETECTOR = "manga_det.onnx"

    /** PP-OCRv6_manga CTC recognizer (FP32 ONNX). */
    const val RECOGNIZER = "manga_rec.onnx"

    /** The recognizer's 19,053-character dictionary. */
    const val DICTIONARY = "ppocrv6_dict.txt"

    /** Text/bubble region detector (int8 RT-DETR). */
    const val BUBBLES = "comic_text_bubble_v4s_int8.onnx"

    private const val PP_BASE = "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/d65838996a00ef6b1c73ca3a700d7a8c5fbbef8a"
    private const val BUBBLE_BASE =
        "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546"
    private const val BUFFER_SIZE = 1 shl 16
    private const val PERCENT = 100

    /** One model file: local [name], download [url], expected [sha256] and approximate [bytes]. */
    private data class Source(val name: String, val url: String, val sha256: String, val bytes: Long)

    private val sources =
        listOf(
            Source(
                name = DETECTOR,
                url = "$PP_BASE/det/manga_det.onnx",
                sha256 = "97c434ae8deaad83513fb132b605d5085a3af8ffac2cb15e2aa096815c206172",
                bytes = 1_816_958L,
            ),
            Source(
                name = RECOGNIZER,
                url = "$PP_BASE/rec/manga_rec.onnx",
                sha256 = "5288bd221a9a047c6b7d1ff56bb98de8c875fb613df98ebe8754946448bda540",
                bytes = 21_334_702L,
            ),
            Source(
                name = DICTIONARY,
                url = "$PP_BASE/ppocrv6_dict.txt",
                sha256 = "4f0f8971ecb95e3a4d278985d6eaac154413e81fda0b4c941db17389580e9e9b",
                bytes = 76_327L,
            ),
            Source(
                name = BUBBLES,
                url = "$BUBBLE_BASE/detector-v4-s_int8.onnx",
                sha256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
                bytes = 11_120_765L,
            ),
        )

    /** Total download size in bytes. */
    val totalBytes: Long = sources.sumOf { it.bytes }

    /** Folder holding the manga models. */
    fun dir(context: Context): File = File(context.filesDir, "models/manga").apply { mkdirs() }

    /** True when every model file is present with its expected size. */
    fun isInstalled(context: Context): Boolean {
        val dir = dir(context)
        return sources.all { File(dir, it.name).length() == it.bytes }
    }

    /** Deletes the downloaded models. */
    fun remove(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Downloads every missing model, verifying SHA-256; [onProgress] gets 0..100.
     * Returns null on success, else an error message.
     */
    suspend fun download(
        context: Context,
        client: OkHttpClient = OkHttpClient(),
        io: CoroutineDispatcher = Dispatchers.IO,
        onProgress: (Int) -> Unit = {},
    ): String? {
        val error =
            withContext(io) {
                val target = dir(context)
                var done = 0L
                try {
                    for (source in sources) {
                        val file = File(target, source.name)
                        if (file.length() != source.bytes) {
                            fetch(client = client, source = source, file = file) { read ->
                                onProgress(((done + read) * PERCENT / totalBytes).toInt())
                            }
                        }
                        done += source.bytes
                    }
                    onProgress(PERCENT)
                    null
                } catch (e: IOException) {
                    e.message ?: "Download failed"
                }
            }
        return error
    }

    private fun fetch(client: OkHttpClient, source: Source, file: File, onRead: (Long) -> Unit) {
        val tmp = File(file.parentFile, "${source.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(source.url).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code} for ${source.name}")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0L
                    var n = input.read(buffer)
                    while (n >= 0) {
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onRead(read)
                        n = input.read(buffer)
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        val problem =
            when {
                hash != source.sha256 -> "Checksum mismatch for ${source.name}"
                !tmp.renameTo(file) -> "Could not save ${source.name}"
                else -> null
            }
        if (problem != null) {
            tmp.delete()
            throw IOException(problem)
        }
    }
}
