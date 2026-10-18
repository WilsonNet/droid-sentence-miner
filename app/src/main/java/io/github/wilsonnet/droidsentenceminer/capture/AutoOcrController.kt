package io.github.wilsonnet.droidsentenceminer.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.ocr.JapaneseTextScore
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrPostProcessor
import io.github.wilsonnet.droidsentenceminer.ocr.SubtitleLineFilter
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineTracker
import io.github.wilsonnet.droidsentenceminer.textlog.TextSimilarity
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Auto OCR mode: keeps reading the selected text areas while you play and logs every new
 * line (GSM's OCR "auto" scan). Fast single-pass reads detect changes; once a line is stable
 * it is re-read with the configured (two-pass) engine and stored with its screenshot.
 * Multiple areas (e.g. name box + dialogue box) are read in order and joined.
 */
class AutoOcrController(
    private val scope: CoroutineScope,
    private val capture: ScreenCaptureManager,
    private val textLog: TextLog,
    private val prefs: DsmPrefs,
    /** Areas for the current orientation. */
    private val areas: () -> List<TextArea>,
    /** True while DSM's own UI covers the screen; reads are skipped so it isn't logged. */
    private val paused: () -> Boolean = { false },
) {
    data class Status(
        val running: Boolean = false,
        val reads: Long = 0,
        val lines: Long = 0,
        val lastRead: String = "",
        val lastReadMs: Long = 0,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var job: Job? = null
    private var openLineId: Long? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        _status.value = Status(running = true)
        job = scope.launch(Dispatchers.Default) {
            try {
                loop()
            } finally {
                withContext(NonCancellable) { closeOpenLine(System.currentTimeMillis()) }
                _status.value = _status.value.copy(running = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        val dropNoise = prefs.ocrDropNoise.first()
        val fast = MlKitJapaneseOcr(twoPass = false, dropNoise = dropNoise)
        val tracker = TextLineTracker()
        var lastSignature: IntArray? = null
        var lastRead = ""
        var lastCrops: List<Bitmap> = emptyList()

        while (currentCoroutineContext().isActive) {
            val loopStart = System.currentTimeMillis()
            val interval = prefs.autoOcrIntervalMs.first().toLong().coerceIn(150, 5_000)
            if (!capture.isReady) {
                _status.value = _status.value.copy(error = "Screen capture stopped")
                delay(interval)
                continue
            }

            val selected = TextArea.ordered(areas())
            if (selected.isEmpty()) {
                _status.value = _status.value.copy(error = NO_AREA)
                delay(interval)
                continue
            }
            if (paused()) {
                // Our own screen/panel is showing: don't log it, keep the current line open.
                capture.pollNewFrame()
                lastSignature = null
                _status.value = _status.value.copy(error = PAUSED)
                delay(interval)
                continue
            }

            val frame = capture.pollNewFrame()
            var frameBitmap: Bitmap? = null
            val atMs: Long
            val text: String
            if (frame == null) {
                // Nothing changed on screen; the previous read still holds.
                atMs = loopStart
                text = lastRead
            } else {
                val crops = selected.map { a ->
                    capture.cropToRegion(frame.bitmap, ScreenCaptureManager.RectFrac(a.left, a.top, a.right, a.bottom))
                }
                val signature = crops.map { FrameSignature.of(it) }.reduce { acc, sig -> acc + sig }
                atMs = frame.atMs
                frameBitmap = frame.bitmap
                text = if (lastSignature != null && FrameSignature.distance(signature, lastSignature) < STATIC_REGION_DISTANCE) {
                    lastRead
                } else {
                    val raw = crops.map { crop -> runCatching { dialogueText(fast, crop, dropNoise) }.getOrElse { "" } }.joinToString("\n").trim()
                    if (looksJapanese(raw)) raw else ""
                }
                lastSignature = signature
                lastCrops = crops
                _status.value = _status.value.copy(reads = _status.value.reads + 1, lastRead = text, lastReadMs = atMs, error = null)
            }
            lastRead = text

            for (event in tracker.onRead(text, atMs)) {
                when (event) {
                    is TextLineTracker.Event.Ended -> closeOpenLine(event.atMs)
                    is TextLineTracker.Event.Started -> {
                        val refined = refine(lastCrops, event.text)
                        val screenshot = frameBitmap ?: capture.pollNewFrame()?.bitmap
                        openLineId = textLog.startLine(refined, event.atMs, TextLineEntity.SOURCE_AUTO, screenshot)
                        // The slower pass counts as one more vote in the line's consensus.
                        if (refined != TextSimilarity.clean(event.text)) tracker.addReading(refined)
                        _status.value = _status.value.copy(lines = _status.value.lines + 1)
                        Log.i(TAG, "line @${event.atMs}: $refined")
                    }
                    is TextLineTracker.Event.Settled -> openLineId?.let { id -> textLog.updateText(id, event.text) }
                    is TextLineTracker.Event.Updated -> openLineId?.let { id ->
                        textLog.updateText(id, refine(lastCrops, event.text))
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - loopStart
            delay((interval - elapsed).coerceAtLeast(30))
        }
    }

    /** Re-reads a stable line with the user's OCR settings; keeps the fast read if worse. Stored joined. */
    private suspend fun refine(crops: List<Bitmap>, fastText: String): String {
        val fast = TextSimilarity.clean(fastText)
        if (!prefs.ocrTwoPass.first() || crops.isEmpty()) return fast
        val dropNoise = prefs.ocrDropNoise.first()
        val engine = MlKitJapaneseOcr(twoPass = true, dropNoise = dropNoise)
        val text = runCatching {
            TextSimilarity.clean(crops.map { dialogueText(engine, it, dropNoise) }.joinToString("\n"))
        }.getOrElse { return fast }
        if (text.isBlank()) return fast
        return if (JapaneseTextScore.score(text) >= JapaneseTextScore.score(fast)) text else fast
    }

    private suspend fun dialogueText(engine: MlKitJapaneseOcr, crop: Bitmap, dropNoise: Boolean): String {
        val result = engine.recognize(crop)
        val rows = SubtitleLineFilter.filter(OcrPostProcessor.merge(result.lines, dropNoise = dropNoise))
        return OcrPostProcessor.toSentenceText(rows).trim()
    }

    private suspend fun closeOpenLine(atMs: Long) {
        val id = openLineId ?: return
        openLineId = null
        textLog.endLine(id, atMs)
    }

    private fun looksJapanese(text: String): Boolean {
        val japanese = text.count { JapaneseTextScore.isJapanese(it) }
        return japanese >= 2 && JapaneseTextScore.score(text) >= 3.0
    }

    companion object {
        private const val TAG = "DSM-AutoOcr"
        private const val STATIC_REGION_DISTANCE = 0.012
        const val NO_AREA = "No text area for this orientation — long-press the bubble → Select text areas"
        const val PAUSED = "Paused while Droid Sentence Miner is on screen"
    }
}

/** Tiny grayscale fingerprint of a region, used to skip OCR when nothing visibly changed. */
object FrameSignature {
    private const val W = 48
    private const val H = 12

    fun of(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, W, H, true)
        val pixels = IntArray(W * H)
        small.getPixels(pixels, 0, W, 0, 0, W, H)
        if (small !== bitmap) small.recycle()
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
    }

    /** Mean absolute luminance difference, 0..1. */
    fun distance(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 1.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / (a.size * 255.0)
    }
}
