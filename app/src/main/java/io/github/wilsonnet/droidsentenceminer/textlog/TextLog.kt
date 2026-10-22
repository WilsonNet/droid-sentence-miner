package io.github.wilsonnet.droidsentenceminer.textlog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.wilsonnet.droidsentenceminer.audio.AudioRingBuffer
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.VoiceTrimmer
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The texthooker log: every recognized line with its screenshot, plus the replay
 * buffer its voice clip is cut from.
 */
class TextLog(
    private val context: Context,
    private val db: TextLogDatabase,
    private val prefs: DsmPrefs,
) {
    val dao: TextLineDao get() = db.lines()

    /** Process-wide replay buffer; replaced when the configured length changes. */
    @Volatile
    var audioBuffer: AudioRingBuffer = AudioRingBuffer(SAMPLE_RATE, DsmPrefs.DEFAULT_AUDIO_BUFFER_SECONDS)
        private set

    fun ensureAudioBuffer(seconds: Int): AudioRingBuffer {
        val current = audioBuffer
        if (current.capacitySeconds == seconds) return current
        return AudioRingBuffer(SAMPLE_RATE, seconds).also { audioBuffer = it }
    }

    /**
     * When auto OCR paused with a line still open (a lookup popup or the panel is up, and the video
     * was usually paused with it). An open line's clip ends here instead of "now", or mining while
     * paused would cut up to 30 s of silence after the sentence.
     */
    @Volatile
    var openLinePausedAtMs: Long? = null

    private val screenshotDir: File get() = File(context.filesDir, "lines").apply { mkdirs() }

    suspend fun startLine(text: String, startMs: Long, source: String, frame: Bitmap?): Long = withContext(Dispatchers.IO) {
        val path = frame?.let { saveScreenshot(it, startMs) }
        val id = dao.insert(TextLineEntity(text = text, startMs = startMs, source = source, screenshotPath = path))
        pruneScreenshots()
        id
    }

    suspend fun endLine(id: Long, endMs: Long) = withContext(Dispatchers.IO) { dao.setEnd(id, endMs) }

    suspend fun updateText(id: Long, text: String) = withContext(Dispatchers.IO) { dao.setText(id, text) }

    suspend fun get(id: Long): TextLineEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    suspend fun latest(): TextLineEntity? = withContext(Dispatchers.IO) { dao.recent(1).firstOrNull() }

    /**
     * Text selected in another app (e.g. Samsung's text extraction or Circle to Search) and sent
     * to DSM: a word is matched to the newest logged line containing it, so mining keeps that
     * line's sentence, screenshot and audio; anything else is logged as a new manual line.
     */
    suspend fun lineForSharedText(text: String): TextLineEntity = withContext(Dispatchers.IO) {
        val cleaned = TextSimilarity.clean(text)
        val compact = cleaned.filterNot { it.isWhitespace() }
        dao.recent(SHARED_MATCH_LINES).firstOrNull { line ->
            val lineText = line.text.filterNot { it.isWhitespace() }
            // An earlier share of the same bare word is not a sentence.
            if (lineText == compact && line.source == TextLineEntity.SOURCE_MANUAL) return@firstOrNull false
            lineText.contains(compact) || (compact.length > 12 && TextSimilarity.ratio(lineText, compact) > 0.8)
        } ?: run {
            val now = System.currentTimeMillis()
            val id = dao.insert(TextLineEntity(text = cleaned, startMs = now, endMs = now, source = TextLineEntity.SOURCE_MANUAL))
            dao.get(id)!!
        }
    }

    suspend fun delete(line: TextLineEntity) = withContext(Dispatchers.IO) {
        line.screenshotPath?.let { File(it).delete() }
        dao.delete(line.id)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clear()
        screenshotDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun screenshot(line: TextLineEntity): Bitmap? = withContext(Dispatchers.IO) {
        line.screenshotPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }

    data class Clip(val segment: AudioSegment, val bounds: ClipWindow.Bounds, val trimmed: Boolean)

    suspend fun clipBounds(line: TextLineEntity, nowMs: Long = System.currentTimeMillis()): ClipWindow.Bounds = withContext(Dispatchers.IO) {
        val next = dao.next(line.startMs, line.id)
        val manual = line.source == TextLineEntity.SOURCE_MANUAL
        ClipWindow.compute(
            lineStartMs = line.startMs,
            lineEndMs = line.endMs ?: openLinePausedAtMs?.takeIf { it >= line.startMs },
            nextStartMs = next?.startMs,
            nowMs = nowMs,
            // A manual scan happens after the line was heard; look back further.
            beforeMs = if (manual) MANUAL_LOOKBACK_MS else prefs.clipBeforeMs.first().toLong(),
            afterMs = prefs.clipAfterMs.first().toLong(),
        )
    }

    /** True when part of the line's clip is still in the replay buffer. */
    suspend fun hasAudio(line: TextLineEntity): Boolean {
        val bounds = clipBounds(line)
        return audioBuffer.covers(bounds.startMs, bounds.endMs)
    }

    /** Cuts the line's voice clip from the replay buffer and trims silence (if enabled). */
    suspend fun clip(line: TextLineEntity, trim: Boolean? = null): Clip? {
        val bounds = clipBounds(line)
        return withContext(Dispatchers.Default) {
            val raw = audioBuffer.slice(bounds.startMs, bounds.endMs) ?: return@withContext null
            if (raw.durationMs < 200) return@withContext null
            if (trim ?: prefs.audioTrim.first()) {
                val result = VoiceTrimmer.trim(raw)
                Clip(result.segment, ClipWindow.Bounds(bounds.startMs + result.startMs, bounds.startMs + result.endMs), result.trimmed)
            } else {
                Clip(raw, bounds, false)
            }
        }
    }

    private fun saveScreenshot(frame: Bitmap, startMs: Long): String? = runCatching {
        val scale = (MAX_SCREENSHOT_WIDTH.toFloat() / frame.width).coerceAtMost(1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
        } else {
            frame
        }
        val file = File(screenshotDir, "line_$startMs.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (scaled !== frame) scaled.recycle()
        file.absolutePath
    }.getOrNull()

    private suspend fun pruneScreenshots() {
        val stale = dao.screenshotsBeyond(MAX_SCREENSHOTS)
        if (stale.isEmpty()) return
        stale.forEach { File(it).delete() }
        dao.clearScreenshots(stale)
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val MANUAL_LOOKBACK_MS = 6_000L
        private const val SHARED_MATCH_LINES = 300
        private const val MAX_SCREENSHOT_WIDTH = 1280
        private const val MAX_SCREENSHOTS = 400
    }
}
