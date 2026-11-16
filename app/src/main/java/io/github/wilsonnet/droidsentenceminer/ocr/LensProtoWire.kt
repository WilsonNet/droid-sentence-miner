package io.github.wilsonnet.droidsentenceminer.ocr

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal protobuf wire encoder/decoder for Google Lens's `crupload` endpoint
 * (matching `chrome_lens_ocr` / Manatan without extra runtime protobuf dependencies).
 */
internal object LensProtoWire {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5
    private const val TAG_SHIFT = 3
    private const val WIRE_MASK = 7
    private const val BYTE_MASK = 0xFF
    private const val VARINT_DATA_MASK = 0x7F
    private const val VARINT_CONT_BIT = 0x80
    private const val VARINT_STEP = 7
    private const val FIXED64_BYTES = 8
    private const val FIXED32_BYTES = 4

    private const val PLATFORM_WEB = 3L
    private const val SURFACE_CHROMIUM = 4L
    private const val CLIENT_REGION = "US"
    private const val CLIENT_TIME_ZONE = "America/New_York"

    private const val F1 = 1
    private const val F2 = 2
    private const val F3 = 3
    private const val F4 = 4

    /** Decoded raw protobuf fields split into byte chunks and 32-bit floats. */
    data class DecodedFields(val bytesFields: Map<Int, List<ByteArray>>, val floatFields: Map<Int, List<Float>>)

    /** Builds a `LensOverlayServerRequest` protobuf payload for [imageBytes]. */
    fun encodeRequest(
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        languageCode: String,
        requestUuid: Long,
    ): ByteArray {
        val reqId = concatBytes(varintField(F1, requestUuid), varintField(F2, 1L), varintField(F3, 1L))
        val locale =
            concatBytes(
                bytesField(F1, languageCode.toByteArray()),
                bytesField(F2, CLIENT_REGION.toByteArray()),
                bytesField(F3, CLIENT_TIME_ZONE.toByteArray()),
            )
        val clientCtx =
            concatBytes(
                varintField(F1, PLATFORM_WEB),
                varintField(F2, SURFACE_CHROMIUM),
                bytesField(F4, locale),
            )
        val reqCtx = concatBytes(bytesField(F3, reqId), bytesField(F4, clientCtx))
        val imgData =
            concatBytes(
                bytesField(F1, bytesField(F1, imageBytes)),
                bytesField(F3, concatBytes(varintField(F1, width.toLong()), varintField(F2, height.toLong()))),
            )
        return bytesField(F1, concatBytes(bytesField(F1, reqCtx), bytesField(F3, imgData)))
    }

    /** Decodes a `LensOverlayServerResponse` into paragraph-grouped [OcrLine] blocks in bitmap coords. */
    fun decodeParagraphs(responseBytes: ByteArray, imgWidth: Int, imgHeight: Int): List<List<OcrLine>> {
        val layouts =
            decodeFields(responseBytes)
                .bytesFields[F2]
                .orEmpty()
                .asSequence()
                .flatMap { obj -> decodeFields(obj).bytesFields[F3].orEmpty() }
                .flatMap { txt -> decodeFields(txt).bytesFields[F1].orEmpty() }
        val result = ArrayList<List<OcrLine>>()
        for (lay in layouts) {
            for (para in decodeFields(lay).bytesFields[F1].orEmpty()) {
                val lines =
                    decodeFields(para)
                        .bytesFields[F2]
                        .orEmpty()
                        .mapNotNull { lineBytes ->
                            decodeSingleLine(
                                lineBytes = lineBytes,
                                imgWidth = imgWidth,
                                imgHeight = imgHeight,
                            )
                        }
                if (lines.isNotEmpty()) result += lines
            }
        }
        return result
    }

    /** Parses a protobuf wire buffer into field numbers for bytes and fixed32 floats. */
    fun decodeFields(buf: ByteArray): DecodedFields {
        val bytesMap = HashMap<Int, MutableList<ByteArray>>()
        val floatMap = HashMap<Int, MutableList<Float>>()
        var i = 0
        var isValid = true
        while (i < buf.size && isValid) {
            val (tag, nextIdx) = readVarint(buf = buf, start = i)
            i = nextIdx
            val fieldNum = (tag ushr TAG_SHIFT).toInt()
            val wireType = (tag and WIRE_MASK.toLong()).toInt()
            i =
                when (wireType) {
                    WIRE_VARINT -> {
                        readVarint(buf = buf, start = i).second
                    }

                    WIRE_FIXED64 -> {
                        i + FIXED64_BYTES
                    }

                    WIRE_BYTES -> {
                        val list =
                            bytesMap.getOrPut(fieldNum) {
                                ArrayList()
                            }
                        readBytesField(buf = buf, start = i, target = list)
                    }

                    WIRE_FIXED32 -> {
                        val list =
                            floatMap.getOrPut(fieldNum) {
                                ArrayList()
                            }
                        readFloatField(buf = buf, start = i, target = list)
                    }

                    else -> {
                        isValid = false
                        buf.size
                    }
                }
        }
        return DecodedFields(bytesFields = bytesMap, floatFields = floatMap)
    }

    private fun readBytesField(buf: ByteArray, start: Int, target: MutableList<ByteArray>): Int {
        val (lenLong, afterLen) = readVarint(buf = buf, start = start)
        val len = lenLong.toInt().coerceAtLeast(0)
        val end = (afterLen + len).coerceAtMost(buf.size)
        target += buf.copyOfRange(fromIndex = afterLen, toIndex = end)
        return end
    }

    private fun readFloatField(buf: ByteArray, start: Int, target: MutableList<Float>): Int {
        if (start + FIXED32_BYTES <= buf.size) {
            val value =
                ByteBuffer
                    .wrap(buf, start, FIXED32_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .float
            target += value
        }
        return start + FIXED32_BYTES
    }

    private fun decodeSingleLine(lineBytes: ByteArray, imgWidth: Int, imgHeight: Int): OcrLine? {
        val lineFields = decodeFields(lineBytes)
        val lineGeom =
            lineFields.bytesFields[F2]
                ?.firstOrNull()
                ?.let(LensProtoGeometry::parseGeometry)
        val rawWords = lineFields.bytesFields[F1].orEmpty().map(LensProtoGeometry::parseWord)
        val wordsText = rawWords.joinToString(separator = "") { word -> word.text + word.separator }.trim()
        val lineBox =
            if (wordsText.isNotEmpty() && lineGeom != null) {
                LensProtoGeometry.toPixelBox(geom = lineGeom, imgWidth = imgWidth, imgHeight = imgHeight)
            } else {
                null
            }
        val result =
            if (lineBox != null && lineGeom != null) {
                val charBoxes =
                    LensProtoGeometry.buildCharBoxes(
                        rawWords = rawWords,
                        lineBox = lineBox,
                        rotationZ = lineGeom.rotationZ,
                        imgW = imgWidth,
                        imgH = imgHeight,
                    )
                OcrLine(
                    text = wordsText,
                    box = lineBox,
                    angle = lineGeom.rotationZ,
                    chars = charBoxes.takeIf { it.isNotEmpty() },
                )
            } else {
                null
            }
        return result
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var idx = start
        var shift = 0
        var value = 0L
        var isDone = false
        while (idx < buf.size && !isDone) {
            val b = buf[idx].toInt() and BYTE_MASK
            idx++
            value = value or (b and VARINT_DATA_MASK).toLong().shl(shift)
            if (b and VARINT_CONT_BIT == 0) {
                isDone = true
            } else {
                shift += VARINT_STEP
            }
        }
        return value to idx
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var remaining = value
        var isDone = false
        while (!isDone) {
            val low = (remaining and VARINT_DATA_MASK.toLong()).toInt()
            remaining = remaining ushr VARINT_STEP
            if (remaining == 0L) {
                out.write(low)
                isDone = true
            } else {
                out.write(low or VARINT_CONT_BIT)
            }
        }
    }

    private fun varintField(fieldNum: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out = out, value = (fieldNum shl TAG_SHIFT or WIRE_VARINT).toLong())
        writeVarint(out = out, value = value)
        return out.toByteArray()
    }

    private fun bytesField(fieldNum: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out = out, value = (fieldNum shl TAG_SHIFT or WIRE_BYTES).toLong())
        writeVarint(out = out, value = data.size.toLong())
        out.write(data)
        return out.toByteArray()
    }

    private fun concatBytes(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
}
