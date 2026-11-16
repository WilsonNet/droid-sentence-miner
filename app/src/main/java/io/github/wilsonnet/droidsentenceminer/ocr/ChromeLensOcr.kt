package io.github.wilsonnet.droidsentenceminer.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Manga/comic OCR via Google Lens's `crupload` protobuf endpoint (the same backend Manatan's
 * `chrome_lens_ocr` uses). Requires internet access; callers fall back to [MlKitJapaneseOcr] offline.
 */
class ChromeLensOcr(
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Sends [bitmap] (downscaled to at most 1500 px) to Google Lens and returns paragraph-grouped
     * [OcrLine] blocks mapped back into [bitmap] pixel coordinates.
     */
    suspend fun recognizeBlocks(bitmap: Bitmap, languageCode: String = "ja"): List<List<OcrLine>> {
        val lensLang = if (languageCode == "ja") "jp" else languageCode
        val encoded = encodeImageForUpload(bitmap)
        val body =
            LensProtoWire.encodeRequest(
                imageBytes = encoded.bytes,
                width = encoded.uploadWidth,
                height = encoded.uploadHeight,
                languageCode = lensLang,
                requestUuid = RANDOM.nextLong(),
            )
        val respBytes = postProto(body)
        return LensProtoWire.decodeParagraphs(
            responseBytes = respBytes,
            imgWidth = bitmap.width,
            imgHeight = bitmap.height,
        )
    }

    private suspend fun postProto(body: ByteArray): ByteArray {
        val bytes =
            withContext(io) {
                val request =
                    Request
                        .Builder()
                        .url(ENDPOINT)
                        .header(name = "User-Agent", value = USER_AGENT)
                        .header(name = "X-Goog-Api-Key", value = API_KEY)
                        .post(body.toRequestBody(PROTO_MEDIA_TYPE))
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Google Lens HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: throw IOException("Empty Google Lens response")
                }
            }
        return bytes
    }

    private fun encodeImageForUpload(bitmap: Bitmap): EncodedUpload {
        val maxSide = max(a = bitmap.width, b = bitmap.height)
        val prepared =
            if (maxSide > MAX_UPLOAD_DIM) {
                val ratio = MAX_UPLOAD_DIM.toFloat() / maxSide
                val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
                val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
                bitmap.scale(width = targetW, height = targetH, filter = true)
            } else {
                bitmap
            }
        return try {
            val out = ByteArrayOutputStream()
            prepared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            EncodedUpload(
                bytes = out.toByteArray(),
                uploadWidth = prepared.width,
                uploadHeight = prepared.height,
            )
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    private data class EncodedUpload(val bytes: ByteArray, val uploadWidth: Int, val uploadHeight: Int)

    private companion object {
        const val ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val MAX_UPLOAD_DIM = 1500
        const val JPEG_QUALITY = 88
        const val TIMEOUT_SECONDS = 25L
        val PROTO_MEDIA_TYPE = "application/x-protobuf".toMediaType()
        val RANDOM = SecureRandom()

        fun defaultClient(): OkHttpClient {
            val built =
                OkHttpClient
                    .Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            return built
        }
    }
}
