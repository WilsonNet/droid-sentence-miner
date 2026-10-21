package io.github.wilsonnet.droidsentenceminer.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis

/**
 * On-device Japanese OCR using ML Kit.
 *
 * Mirrors the two-pass strategy of GameSentenceMiner's OwOCR pipeline:
 *  1. Detect and recognize the whole capture region.
 *  2. Re-recognize each detected text block at higher resolution and keep
 *     whichever result reads better as Japanese.
 */
class MlKitJapaneseOcr(
    private val twoPass: Boolean = true,
    private val dropNoise: Boolean = true,
) : OcrEngine {

    override val name: String = "ML Kit Japanese"

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis {
            val prepared = ImagePreprocessor.upscaleForOcr(bitmap)
            lines = recognizeOnce(prepared)

            val merged = OcrPostProcessor.merge(lines, dropNoise = dropNoise)
            if (twoPass && merged.isNotEmpty()) {
                lines = refine(prepared, merged)
            }
        }
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines, dropNoise = dropNoise))
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private suspend fun recognizeOnce(bitmap: Bitmap): List<OcrLine> = recognizeBlocks(bitmap).flatten()

    /** Raw lines grouped by ML Kit's text blocks (paragraphs), no post-processing. */
    suspend fun recognizeBlocks(bitmap: Bitmap): List<List<OcrLine>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text: Text = recognizer.process(image).awaitResult()
        return text.textBlocks.map { block -> linesOf(block) }.filter { it.isNotEmpty() }
    }

    private fun linesOf(block: Text.TextBlock): List<OcrLine> = block.lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        if (line.text.isBlank()) return@mapNotNull null
        OcrLine(
            text = line.text,
            box = BoundingBox.from(box),
            confidence = line.confidence,
            angle = line.angle,
            chars = line.elements.flatMap { it.symbols }.mapNotNull { it.boundingBox?.let(BoundingBox::from) },
        )
    }

    /**
     * Second pass: for each consolidated line, crop + upscale and re-run recognition,
     * keeping the better reading. Capped to avoid long scans.
     */
    private suspend fun refine(bitmap: Bitmap, merged: List<OcrPostProcessor.MergedLine>): List<OcrLine> {
        val lines = merged.map { OcrLine(text = it.text, box = it.box) }
        val refined = ArrayList<OcrLine>(lines.size)
        val candidates = lines
            .sortedByDescending { it.box.width.toLong() * it.box.height }
            .take(MAX_REFINE_LINES)
        val refinedByKey = HashMap<String, String>()
        for (line in candidates) {
            val crop = ImagePreprocessor.crop(bitmap, line.box, padding = 16)
            val scaled = ImagePreprocessor.upscaleForOcr(crop, minWidth = crop.width * 2)
            val result = runCatching { recognizeOnce(scaled) }.getOrNull()
            if (result.isNullOrEmpty()) continue
            val candidateText = OcrPostProcessor.merge(result, dropNoise = dropNoise).joinToString("\n") { it.text }
            if (JapaneseTextScore.score(candidateText) > JapaneseTextScore.score(line.text) + SCORE_MARGIN) {
                refinedByKey[keyOf(line)] = candidateText
            }
        }
        for (line in lines) {
            val better = refinedByKey[keyOf(line)]
            refined += if (better != null) line.copy(text = better) else line
        }
        return refined
    }

    private fun keyOf(line: OcrLine): String = "${line.box.left}:${line.box.top}:${line.box.width}:${line.box.height}"

    companion object {
        private const val MAX_REFINE_LINES = 6
        private const val SCORE_MARGIN = 1.5
    }
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}
