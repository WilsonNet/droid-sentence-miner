package io.github.wilsonnet.droidsentenceminer.book

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import io.github.wilsonnet.droidsentenceminer.ocr.ImagePreprocessor
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * [ActivityResultContracts.TakePicture] that prefers Samsung's own Camera app (its full processing:
 * scene optimizer, HDR, document focus) and falls back to whatever camera app handles
 * `IMAGE_CAPTURE`. Samsung has no public camera SDK any more, so its app is the way in.
 */
class TakePagePhoto : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent {
        val intent = super.createIntent(context, input)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val samsung = SAMSUNG_CAMERAS.firstOrNull { pkg ->
            Intent(intent).setPackage(pkg).resolveActivity(context.packageManager) != null
        }
        return if (samsung != null) intent.setPackage(samsung) else intent
    }

    companion object {
        private val SAMSUNG_CAMERAS = listOf("com.sec.android.app.camera")
    }
}

/** A photographed page and its paragraphs. */
data class BookPage(
    val bitmap: Bitmap,
    val blocks: List<PageBlock>,
    val elapsedMs: Long,
)

/**
 * OCR for photos of printed books. Always ML Kit: it reads vertical (tategaki) and horizontal
 * print, while meikiocr only knows horizontal game text.
 */
class BookOcr(private val engine: MlKitJapaneseOcr = MlKitJapaneseOcr(twoPass = false)) {

    suspend fun read(bitmap: Bitmap): BookPage = withContext(Dispatchers.Default) {
        var blocks: List<PageBlock> = emptyList()
        val elapsed = measureTimeMillis { blocks = PageLayout.build(engine.recognizeBlocks(bitmap)) }
        BookPage(bitmap, blocks, elapsed)
    }

    companion object {
        /** Camera photos are 12–50 MP; this keeps print legible and memory sane. */
        const val MAX_SIDE = 3000

        /** Decodes [uri] upright (EXIF orientation applied), long side capped at [maxSide]. */
        suspend fun loadPhoto(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val longSide = max(info.size.width, info.size.height)
                        if (longSide > maxSide) {
                            val scale = maxSide.toFloat() / longSide
                            decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                        }
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null
                    val degrees = context.contentResolver.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                    if (degrees == 0f) decoded
                    else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
                }
            }.onFailure { android.util.Log.w("DSM-Book", "Could not read $uri", it) }.getOrNull()
        }

        /** The paragraph cut out of the page for the card's picture, at most 1280 px. */
        fun cardImage(page: BookPage, block: PageBlock): Bitmap {
            val pad = max(block.box.width, block.box.height) / 20 + 16
            val crop = ImagePreprocessor.crop(page.bitmap, block.box, padding = pad)
            val longSide = max(crop.width, crop.height)
            if (longSide <= 1280) return crop
            val scale = 1280f / longSide
            return Bitmap.createScaledBitmap(crop, (crop.width * scale).roundToInt(), (crop.height * scale).roundToInt(), true)
        }
    }
}
