package io.github.wilsonnet.droidsentenceminer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
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
 *
 * Apps can refuse playback capture (targetSdk < 29 without `allowAudioPlaybackCapture`, e.g.
 * DroidDeck; Netflix): Android then hands us pure digital silence. With [start]'s `micFallback`,
 * the microphone records alongside and fills the buffer whenever device audio has been exact
 * zeros for [SILENT_SWITCH_MS] while something is playing; device audio takes over again as soon
 * as it carries sound.
 */
class AudioCaptureManager(
    private val context: Context,
    private val buffer: () -> AudioRingBuffer,
) {

    companion object {

        /** Device audio silent this long while music plays = the app blocks capture. */
        const val SILENT_SWITCH_MS = 4_000L

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var record: AudioRecord? = null
    private var mic: AudioRecord? = null
    private var denoiser: NoiseSuppressor? = null
    private var thread: Thread? = null
    private var micThread: Thread? = null
    @Volatile
    private var running = false

    @Volatile
    private var lastError: String? = null

    /** Last time device audio carried a non-zero sample (or capture started). */
    @Volatile
    private var deviceSoundAtMs = 0L

    /** The buffer is being filled from the microphone. */
    @Volatile
    var usingMic = false
        private set

    val isRunning: Boolean get() = running
    val error: String? get() = lastError

    // RECORD_AUDIO is checked via hasPermission() below; lint can't see through the helper.
    @SuppressLint("MissingPermission")
    fun start(
        projection: MediaProjection,
        micFallback: Boolean = false,
        noiseSuppression: Boolean = false,
        compressor: Boolean = false,
    ): Boolean {
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

            val recorder = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(monoFormat(sampleRate))
                .setBufferSizeInBytes(bufferBytes(sampleRate))
                .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                lastError = "AudioRecord could not be initialized"
                return false
            }

            record = recorder
            running = true
            usingMic = false
            deviceSoundAtMs = System.currentTimeMillis()
            recorder.startRecording()
            thread = Thread({ readLoop(recorder) }, "dsm-audio").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            if (micFallback) startMic(sampleRate, noiseSuppression, compressor)
            true
        } catch (e: Exception) {
            lastError = e.message ?: "Audio capture failed"
            stop()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMic(sampleRate: Int, noiseSuppression: Boolean, compressor: Boolean) {
        // UNPROCESSED skips noise suppression and AGC, which pump game music; not every device has it.
        val unprocessed = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(monoFormat(sampleRate))
                .setBufferSizeInBytes(bufferBytes(sampleRate))
                .build()
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            lastError = "Microphone fallback unavailable"
            return
        }
        mic = recorder
        // The platform's voice noise suppressor (what calls use): fan noise and room hum out,
        // voices kept. Quality depends on the device; not every device has one.
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            denoiser = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        recorder.startRecording()
        // After the suppressor (it sits in the recorder's input path), before the buffer.
        val dynamics = if (compressor) AudioCompressor(sampleRate) else null
        micThread = Thread({ micLoop(recorder, dynamics) }, "dsm-audio-mic").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun monoFormat(sampleRate: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferBytes(sampleRate: Int) = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(8192) * 2

    private fun readLoop(recorder: AudioRecord) {
        val chunk = ShortArray(2048)
        while (running) {
            val read = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                val now = System.currentTimeMillis()
                if ((0 until read).any { chunk[it] != 0.toShort() }) {
                    deviceSoundAtMs = now
                    usingMic = false
                }
                if (!usingMic) buffer().write(chunk, read, now)
            } else if (read < 0) {
                break
            }
        }
    }

    private fun micLoop(recorder: AudioRecord, compressor: AudioCompressor?) {
        val chunk = ShortArray(2048)
        val audio = context.getSystemService(AudioManager::class.java)
        while (running) {
            val read = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (e: Exception) {
                -1
            }
            if (read > 0) {
                val now = System.currentTimeMillis()
                if (!usingMic && now - deviceSoundAtMs >= SILENT_SWITCH_MS && audio?.isMusicActive == true) {
                    usingMic = true
                }
                compressor?.process(chunk, read) // every chunk, so its envelope is settled on switching
                if (usingMic) buffer().write(chunk, read, now)
            } else if (read < 0) {
                break
            }
        }
    }

    fun stop() {
        running = false
        usingMic = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { denoiser?.release() }
        denoiser = null
        runCatching { mic?.stop() }
        runCatching { mic?.release() }
        record = null
        mic = null
        thread?.join(600)
        micThread?.join(600)
        thread = null
        micThread = null
    }
}
