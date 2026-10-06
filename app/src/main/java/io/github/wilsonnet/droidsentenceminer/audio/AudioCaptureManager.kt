package io.github.wilsonnet.droidsentenceminer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Captures the audio playing on the device (games, visual novels, video) through
 * the existing MediaProjection session — the Android equivalent of the system
 * loopback capture used by GameSentenceMiner.
 *
 * The stream is segmented by [VadSegmenter] so cards can receive the voice line
 * that was playing when the text was mined.
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var segmenter = VadSegmenter(SAMPLE_RATE, CHANNELS)

    @Volatile
    private var running = false

    @Volatile
    private var lastError: String? = null

    val isRunning: Boolean get() = running
    val error: String? get() = lastError

    fun start(projection: MediaProjection): Boolean {
        if (!isSupported()) {
            lastError = "Audio capture needs Android 10 or newer"
            return false
        }
        if (!hasPermission(context)) {
            lastError = "RECORD_AUDIO permission missing"
            return false
        }
        if (running) return true

        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(8192)

            val recorder = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                lastError = "AudioRecord could not be initialized"
                return false
            }

            record = recorder
            running = true
            recorder.startRecording()
            thread = Thread({ readLoop(recorder) }, "dsm-audio").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            true
        } catch (e: Exception) {
            lastError = e.message ?: "Audio capture failed"
            stop()
            false
        }
    }

    private fun readLoop(recorder: AudioRecord) {
        val buffer = ShortArray(2048 * CHANNELS)
        while (running) {
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                synchronized(lock) { segmenter.process(chunk) }
            } else if (read < 0) {
                break
            }
        }
    }

    /** Latest completed voice line, or the currently playing one when long enough. */
    fun latestSegment(): AudioSegment? = synchronized(lock) { segmenter.latestSegment() }

    fun currentSegment(): AudioSegment? = synchronized(lock) { segmenter.currentSegment() }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread?.join(600)
        thread = null
        synchronized(lock) { segmenter = VadSegmenter(SAMPLE_RATE, CHANNELS) }
    }
}
