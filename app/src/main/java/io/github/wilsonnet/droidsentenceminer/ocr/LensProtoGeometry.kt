package io.github.wilsonnet.droidsentenceminer.ocr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Normalized center-rotated geometry and word slicing helpers for Google Lens OCR results. */
internal object LensProtoGeometry {
    private const val F1 = 1
    private const val F2 = 2
    private const val F3 = 3
    private const val F4 = 4
    private const val F5 = 5
    private const val HALF = 2f
    private const val VERT_ROT_EPS = 0.5f
    private const val ROT_ACTIVE_MIN = 0.1f
    private const val QUARTER_TURN = (Math.PI / 2.0).toFloat()

    /** Normalized center-rotated box decoded from a Lens geometry node. */
    data class RawGeometry(val cx: Float, val cy: Float, val width: Float, val height: Float, val rotationZ: Float)

    /** One word entry decoded from a Lens line. */
    data class RawWord(val text: String, val separator: String, val geometry: RawGeometry?)

    /** Converts center-rotated normalized geometry to an axis-aligned [BoundingBox] in pixels. */
    fun toPixelBox(geom: RawGeometry, imgWidth: Int, imgHeight: Int): BoundingBox {
        val cx = geom.cx * imgWidth
        val cy = geom.cy * imgHeight
        val hw = geom.width * imgWidth / HALF
        val hh = geom.height * imgHeight / HALF
        val cosA = cos(geom.rotationZ.toDouble()).toFloat()
        val sinA = sin(geom.rotationZ.toDouble()).toFloat()
        val xs = floatArrayOf(-hw, hw, hw, -hw)
        val ys = floatArrayOf(-hh, -hh, hh, hh)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in xs.indices) {
            val rx = xs[i] * cosA - ys[i] * sinA + cx
            val ry = xs[i] * sinA + ys[i] * cosA + cy
            minX = min(a = minX, b = rx)
            maxX = max(a = maxX, b = rx)
            minY = min(a = minY, b = ry)
            maxY = max(a = maxY, b = ry)
        }
        val left = minX.roundToInt().coerceIn(minimumValue = 0, maximumValue = (imgWidth - 1).coerceAtLeast(0))
        val top = minY.roundToInt().coerceIn(minimumValue = 0, maximumValue = (imgHeight - 1).coerceAtLeast(0))
        val right = maxX.roundToInt().coerceIn(minimumValue = left + 1, maximumValue = max(a = left + 1, b = imgWidth))
        val bottom = maxY.roundToInt().coerceIn(minimumValue = top + 1, maximumValue = max(a = top + 1, b = imgHeight))
        return BoundingBox(left = left, top = top, right = right, bottom = bottom)
    }

    /** Decodes a word message into text + geometry. */
    fun parseWord(bytes: ByteArray): RawWord {
        val fields = LensProtoWire.decodeFields(bytes)
        val text =
            fields.bytesFields[F2]
                ?.firstOrNull()
                ?.decodeToString()
                .orEmpty()
        val sep =
            fields.bytesFields[F3]
                ?.firstOrNull()
                ?.decodeToString()
                .orEmpty()
        val geom =
            fields.bytesFields[F4]
                ?.firstOrNull()
                ?.let(::parseGeometry)
        return RawWord(text = text, separator = sep, geometry = geom)
    }

    /** Decodes a `Geometry` wrapper protobuf message. */
    fun parseGeometry(bytes: ByteArray): RawGeometry? {
        val outer = LensProtoWire.decodeFields(bytes)
        val boxBytes = outer.bytesFields[F1]?.firstOrNull()
        val fields = boxBytes?.let(LensProtoWire::decodeFields)
        val cx = fields?.floatFields?.get(F1)?.firstOrNull()
        val cy = fields?.floatFields?.get(F2)?.firstOrNull()
        val w = fields?.floatFields?.get(F3)?.firstOrNull()
        val h = fields?.floatFields?.get(F4)?.firstOrNull()
        val rot = fields?.floatFields?.get(F5)?.firstOrNull() ?: 0f
        return if (cx != null && cy != null) buildRawGeometry(cx = cx, cy = cy, w = w, h = h, rot = rot) else null
    }

    private fun buildRawGeometry(cx: Float, cy: Float, w: Float?, h: Float?, rot: Float): RawGeometry? {
        val result =
            if (w != null && h != null) {
                RawGeometry(cx = cx, cy = cy, width = w, height = h, rotationZ = rot)
            } else {
                null
            }
        return result
    }

    /** Subdivides Lens word geometries into per-character boxes matching line direction. */
    fun buildCharBoxes(
        rawWords: List<RawWord>,
        lineBox: BoundingBox,
        rotationZ: Float,
        imgW: Int,
        imgH: Int,
    ): List<BoundingBox> {
        val absRot = abs(rotationZ)
        val isVertical =
            if (absRot > ROT_ACTIVE_MIN) {
                abs(absRot - QUARTER_TURN) < VERT_ROT_EPS || lineBox.height > lineBox.width
            } else {
                lineBox.height > lineBox.width
            }
        val out = ArrayList<BoundingBox>()
        for (word in rawWords) {
            val g = word.geometry
            val count = (word.text + word.separator).trim().length
            if (g != null && count > 0) {
                val wordBox = toPixelBox(geom = g, imgWidth = imgW, imgHeight = imgH)
                for (i in 0 until count) {
                    out += sliceBox(box = wordBox, index = i, count = count, isVertical = isVertical)
                }
            }
        }
        return out
    }

    private fun sliceBox(box: BoundingBox, index: Int, count: Int, isVertical: Boolean): BoundingBox {
        if (count <= 1) return box
        return if (isVertical) {
            val top = box.top + box.height * index / count
            val bottom = box.top + box.height * (index + 1) / count
            BoundingBox(left = box.left, top = top, right = box.right, bottom = max(a = top + 1, b = bottom))
        } else {
            val left = box.left + box.width * index / count
            val right = box.left + box.width * (index + 1) / count
            BoundingBox(left = left, top = box.top, right = max(a = left + 1, b = right), bottom = box.bottom)
        }
    }
}
