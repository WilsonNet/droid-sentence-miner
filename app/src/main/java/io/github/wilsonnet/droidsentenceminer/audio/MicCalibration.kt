package io.github.wilsonnet.droidsentenceminer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.NoiseSuppressor
import android.os.Build

/**
 * Measures this device's speaker → microphone path for the microphone fallback's EQ: plays
 * [PINK_SECONDS] of pink noise through the speaker as game audio while recording the mic exactly as
 * the fallback does (same source, same noise suppressor), then fits [MicEq] to the response.
 * Pink noise, not a sine sweep: its result matched recorded speech within ~1 dB in every band,
 * at three playback levels, while a sweep overstated the 4–5 kHz peak by 4 dB.
 * Blocking; call off the main thread. Needs RECORD_AUDIO and the app in the foreground.
 */
object MicCalibration {
    private const val PINK_SECONDS = 6.0
    private const val PINK_RMS_DBFS = -18.0
    private const val LEAD_MS = 500
    private const val NOISE_FROM_MS = 50
    private const val NOISE_TO_MS = 450

    /** Output latency can reach ~250 ms; the analysis starts after it. */
    private const val SKIP_MS = 1_000

    class Result(val profile: MicEqProfile?, val error: String?)

    @SuppressLint("MissingPermission")
    fun run(context: Context, sampleRate: Int, noiseSuppression: Boolean): Result {
        if (!AudioCaptureManager.hasPermission(context)) return Result(null, "Microphone permission missing")
        val pink = MicEq.pinkNoise((PINK_SECONDS * sampleRate).toInt(), PINK_RMS_DBFS)
        val lead = LEAD_MS * sampleRate / 1000
        val out = ShortArray(pink.size + 2 * lead)
        for (i in pink.indices) out[lead + i] = (pink[i] * 32767).toInt().toShort()

        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(AudioCaptureManager.micSource(context))
                .setAudioFormat(AudioCaptureManager.monoFormat(sampleRate))
                .setBufferSizeInBytes(AudioCaptureManager.bufferBytes(sampleRate))
                .build()
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return Result(null, "Microphone unavailable")
        }
        val denoiser = if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(out.size * 2)
            .build()
        val recorded = ShortArray(out.size)
        var got = 0
        try {
            track.write(out, 0, out.size)
            recorder.startRecording()
            track.play()
            while (got < recorded.size) {
                val read = recorder.read(recorded, got, minOf(2048, recorded.size - got))
                if (read <= 0) break
                got += read
            }
        } catch (e: Exception) {
            return Result(null, e.message ?: "Calibration failed")
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { recorder.stop() }
            recorder.release()
            runCatching { denoiser?.release() }
        }
        if (got < recorded.size * 9 / 10 || recorded.all { it == 0.toShort() }) {
            return Result(null, "The microphone recorded nothing (in use by another app?)")
        }
        val ms = { t: Int -> t * sampleRate / 1000 }
        val noise = DoubleArray(ms(NOISE_TO_MS) - ms(NOISE_FROM_MS)) { recorded[ms(NOISE_FROM_MS) + it] / 32768.0 }
        val signal = DoubleArray(lead + pink.size - ms(SKIP_MS)) { recorded[ms(SKIP_MS) + it] / 32768.0 }
        val response = MicEq.response(pink, signal, noise, sampleRate)
            ?: return Result(null, "Too quiet or too noisy: turn the media volume up, pause other audio and try again")
        val gains = MicEq.fit(response, sampleRate)
        return Result(
            MicEqProfile(
                gainsDb = gains.map { (it * 100).toInt() / 100.0 },
                measuredDb = MicEq.plotPoints(response),
                model = deviceModel(),
                createdAtMs = System.currentTimeMillis(),
            ),
            null,
        )
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
