package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

class ChromeLensProtoTest {
    @Test
    fun encodesRequestHeaderWithLanguageAndUuid() {
        val payload =
            LensProtoWire.encodeRequest(
                imageBytes = byteArrayOf(1, 2, 3, 4),
                width = 800,
                height = 1200,
                languageCode = "jp",
                requestUuid = 42L,
            )
        assertTrue(payload.isNotEmpty())
        val text = payload.decodeToString()
        assertTrue(text.contains("jp"))
        assertTrue(text.contains("America/New_York"))
    }

    @Test
    fun decodesParagraphLinesWordsAndCharBoxes() {
        val word1 =
            encodeWord(
                text = "かかっ",
                sep = "",
                geom = LensProtoGeometry.RawGeometry(cx = 0.5f, cy = 0.3f, width = 0.04f, height = 0.12f, rotationZ = 0f),
            )
        val word2 =
            encodeWord(
                text = "た",
                sep = "",
                geom = LensProtoGeometry.RawGeometry(cx = 0.5f, cy = 0.4f, width = 0.04f, height = 0.04f, rotationZ = 0f),
            )
        val lineGeom =
            encodeGeometryField(
                LensProtoGeometry.RawGeometry(cx = 0.5f, cy = 0.35f, width = 0.04f, height = 0.18f, rotationZ = 0f),
            )
        val line = concat(fieldBytes(1, word1), fieldBytes(1, word2), fieldBytes(2, lineGeom))
        val para = fieldBytes(2, line)
        val layout = fieldBytes(1, para)
        val textNode = fieldBytes(1, layout)
        val objResp = fieldBytes(3, textNode)
        val serverResp = fieldBytes(2, objResp)

        val blocks = LensProtoWire.decodeParagraphs(serverResp, imgWidth = 1000, imgHeight = 1000)
        assertEquals(1, blocks.size)
        assertEquals(1, blocks[0].size)
        val decoded = blocks[0][0]
        assertEquals("かかった", decoded.text)
        assertEquals(BoundingBox(left = 480, top = 260, right = 520, bottom = 440), decoded.box)
        assertEquals(4, decoded.chars?.size)
    }

    @Test
    fun convertsRotatedGeometryIntoAxisAlignedBox() {
        val box =
            LensProtoGeometry.toPixelBox(
                geom =
                LensProtoGeometry.RawGeometry(
                    cx = 0.5f,
                    cy = 0.5f,
                    width = 0.2f,
                    height = 0.1f,
                    rotationZ = (PI / 2.0).toFloat(),
                ),
                imgWidth = 1000,
                imgHeight = 1000,
            )
        assertEquals(450, box.left)
        assertEquals(400, box.top)
        assertEquals(550, box.right)
        assertEquals(600, box.bottom)
    }

    private fun encodeWord(text: String, sep: String, geom: LensProtoGeometry.RawGeometry): ByteArray {
        val geomBytes = encodeGeometryField(geom)
        return concat(
            fieldBytes(2, text.toByteArray()),
            fieldBytes(3, sep.toByteArray()),
            fieldBytes(4, geomBytes),
        )
    }

    private fun encodeGeometryField(geom: LensProtoGeometry.RawGeometry): ByteArray {
        val centerRotated =
            concat(
                fieldFixed32(1, geom.cx),
                fieldFixed32(2, geom.cy),
                fieldFixed32(3, geom.width),
                fieldFixed32(4, geom.height),
                fieldFixed32(5, geom.rotationZ),
            )
        return fieldBytes(1, centerRotated)
    }

    private fun fieldFixed32(tag: Int, value: Float): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, tag shl TAG_SHIFT or WIRE_FIXED32)
        val raw =
            ByteBuffer
                .allocate(FLOAT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(value)
                .array()
        out.write(raw)
        return out.toByteArray()
    }

    private fun fieldBytes(tag: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, tag shl TAG_SHIFT or WIRE_BYTES)
        writeVarint(out, data.size)
        out.write(data)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            val low = remaining and DATA_MASK
            remaining = remaining ushr STEP_BITS
            if (remaining == 0) {
                out.write(low)
                break
            } else {
                out.write(low or CONT_BIT)
            }
        }
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    private companion object {
        const val TAG_SHIFT = 3
        const val WIRE_BYTES = 2
        const val WIRE_FIXED32 = 5
        const val FLOAT_BYTES = 4
        const val DATA_MASK = 0x7F
        const val CONT_BIT = 0x80
        const val STEP_BITS = 7
    }
}
