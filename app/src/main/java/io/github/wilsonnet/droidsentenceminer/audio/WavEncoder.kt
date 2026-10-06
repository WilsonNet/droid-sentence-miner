package io.github.wilsonnet.droidsentenceminer.audio

import java.io.ByteArrayOutputStream

/** Minimal WAV (PCM 16-bit) writer. Used as a universal fallback for Anki media. */
object WavEncoder {

    fun encode(segment: AudioSegment): ByteArray {
        val pcm = segment.pcm
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val byteRate = segment.sampleRate * segment.channels * 2
        val blockAlign = segment.channels * 2

        out.write("RIFF".toByteArray())
        out.write(intLe(36 + dataSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1)) // PCM
        out.write(shortLe(segment.channels))
        out.write(intLe(segment.sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(blockAlign))
        out.write(shortLe(16))
        out.write("data".toByteArray())
        out.write(intLe(dataSize))
        for (sample in pcm) {
            out.write(sample.toInt() and 0xFF)
            out.write((sample.toInt() shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}
