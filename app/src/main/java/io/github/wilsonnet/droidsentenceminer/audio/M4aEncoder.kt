package io.github.wilsonnet.droidsentenceminer.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes PCM16 mono audio to AAC in an .m4a container using platform MediaCodec.
 * Falls back to WAV if the device refuses AAC encoding.
 */
object M4aEncoder {

    data class EncodedAudio(val bytes: ByteArray, val extension: String, val mimeType: String)

    fun encode(segment: AudioSegment, outputFile: File, bitRate: Int = 96_000): EncodedAudio? {
        val mono = AudioPcm.downmixToMono(segment)
        val aac = runCatching { encodeAac(mono, outputFile, bitRate) }.getOrNull()
        if (aac != null) {
            return EncodedAudio(aac.readBytes(), "m4a", "audio/mp4")
        }
        // Fallback to WAV, which Anki always accepts.
        val wavFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".wav")
        runCatching { wavFile.writeBytes(WavEncoder.encode(mono)) }.getOrNull() ?: return null
        return EncodedAudio(wavFile.readBytes(), "wav", "audio/wav")
    }

    private fun encodeAac(segment: AudioSegment, outputFile: File, bitRate: Int): File {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, segment.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val bytes = ByteBuffer.allocate(segment.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in segment.pcm) bytes.putShort(sample)
        bytes.flip()

        val chunkSize = 4096 * 2
        var inputDone = false
        var outputDone = false
        val deadline = System.currentTimeMillis() + 20_000

        while (!outputDone) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("AAC encoding timed out")
            }
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val totalBytes = segment.pcm.size * 2
                    val consumedBefore = totalBytes - bytes.remaining()
                    val size = minOf(bytes.remaining(), chunkSize)
                    if (size > 0) {
                        val chunk = ByteArray(size)
                        bytes.get(chunk)
                        inputBuffer.put(chunk)
                    }
                    val presentationTimeUs = 1_000_000L * (consumedBefore / 2) / segment.sampleRate
                    if (bytes.remaining() == 0) {
                        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        return outputFile
    }
}
