package io.github.wilsonnet.droidsentenceminer.audio

import android.Manifest
import android.annotation.SuppressLint
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
 * Mono PCM goes into [buffer], the rolling replay buffer that text lines cut
 * their voice clips from.
 */
class AudioCaptureManager(
    private val context: Context,
    private val buffer: () -> AudioRingBuffer,
) {

    companion object {

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false

    @Volatile
    private var lastError: String? = null

    val isRunning: Boolean get() = running
    val error: String? get() = lastError

    // RECORD_AUDIO is checked via hasPermission() below; lint can't see through the helper.
    @SuppressLint("MissingPermission")
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
        val sampleRate = buffer().sampleRate

        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
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
        val chunk = ShortArray(2048)
        while (running) {
            val read = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                buffer().write(chunk, read, System.currentTimeMillis())
            } else if (read < 0) {
                break
            }
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread?.join(600)
        thread = null
    }
}
