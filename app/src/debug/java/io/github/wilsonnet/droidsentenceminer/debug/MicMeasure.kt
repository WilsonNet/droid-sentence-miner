package io.github.wilsonnet.droidsentenceminer.debug

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.MicEq
import io.github.wilsonnet.droidsentenceminer.audio.MicFx
import io.github.wilsonnet.droidsentenceminer.audio.MicFxChain
import io.github.wilsonnet.droidsentenceminer.audio.WavEncoder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Speaker → microphone measurement for tuning the mic fallback chain: plays an exponential sine
 * sweep (or white noise) through the speaker as game audio while recording the mic with the same
 * source the fallback uses. Returns a stereo WAV, left = played, right = recorded (through the
 * configured [MicFxChain] with `fx`).
 */
object MicMeasure {

    @SuppressLint("MissingPermission")
    fun run(context: Context, signal: String, seconds: Double, amp: Double, ns: Boolean, file: String? = null, fx: MicFx? = null, rate: Int = 24_000): ByteArray {
        val n = (seconds * rate).toInt()
        val played: ShortArray = when (signal) {
            "noise" -> {
                val rnd = java.util.Random(1)
                ShortArray(n) { ((rnd.nextDouble() * 2 - 1) * amp * 32767).toInt().toShort() }
            }
            "pink" -> MicEq.pinkNoise(n, 20 * kotlin.math.log10(amp / 4)).let { p -> ShortArray(n) { (p[it] * 32767).toInt().toShort() } }
            "file" -> readWav(java.io.File(context.filesDir, file ?: "measure.wav"), amp)
            else -> sweep(n, rate, 40.0, 11_000.0, amp)
        }
        val lead = rate / 2 // half a second of silence before and after
        val out = ShortArray(played.size + 2 * lead).also { played.copyInto(it, lead) }

        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .build()
        val denoiser = if (ns && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } else null
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(out.size * 2)
            .build()
        track.write(out, 0, out.size)
        val recorded = ShortArray(out.size + rate)
        try {
            recorder.startRecording()
            track.play()
            var pos = 0
            while (pos < recorded.size) {
                val r = recorder.read(recorded, pos, minOf(2048, recorded.size - pos))
                if (r <= 0) break
                pos += r
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { recorder.stop() }
            recorder.release()
            denoiser?.release()
        }
        // Right channel through the fallback's chain, as micLoop does it chunk by chunk.
        fx?.let { cfg ->
            val chain = MicFxChain(rate, cfg)
            val chunk = ShortArray(2048)
            for (from in recorded.indices step chunk.size) {
                val len = minOf(chunk.size, recorded.size - from)
                recorded.copyInto(chunk, 0, from, from + len)
                chain.process(chunk, len)
                chunk.copyInto(recorded, from, 0, len)
            }
        }
        val stereo = ShortArray(recorded.size * 2)
        for (i in recorded.indices) {
            stereo[2 * i] = if (i < out.size) out[i] else 0
            stereo[2 * i + 1] = recorded[i]
        }
        return WavEncoder.encode(AudioSegment(stereo, rate, 2))
    }

    /** Mono 16-bit WAV at the measure rate (convert with ffmpeg first), scaled to [amp] peak. */
    private fun readWav(file: java.io.File, amp: Double): ShortArray {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(buf.remaining()).also { buf.get(it) }
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }.coerceAtLeast(1)
        val g = amp * 32767 / peak
        return ShortArray(pcm.size) { (pcm[it] * g).toInt().toShort() }
    }

    private fun sweep(n: Int, rate: Int, f1: Double, f2: Double, amp: Double): ShortArray {
        val t = n.toDouble() / rate
        val k = ln(f2 / f1)
        val fade = rate / 20
        return ShortArray(n) { i ->
            val time = i.toDouble() / rate
            val env = minOf(1.0, i.toDouble() / fade, (n - i).toDouble() / fade)
            (amp * env * 32767 * sin(2 * PI * f1 * t / k * (exp(time / t * k) - 1))).toInt().toShort()
        }
    }
}
