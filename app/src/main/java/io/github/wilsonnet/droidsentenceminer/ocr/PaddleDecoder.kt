package io.github.wilsonnet.droidsentenceminer.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure parts of the PaddleOCR (PP-OCRv5/v6) pipeline, JVM unit-tested: detector input size,
 * DB post-processing of the probability map into text boxes, and CTC decoding with per-character
 * x ranges. Mirrors PaddleOCR's `DBPostProcess` / `CTCLabelDecode` for axis-aligned text (screen
 * text in games and subtitles is horizontal, so rotated boxes are not needed).
 */
object PaddleDecoder {

    /** A text box in detector-map pixels (left/top inclusive, right/bottom exclusive) with its mean probability. */
    data class MapBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float)

    /** One decoded character: its label, score and x range in recognizer timesteps [start, end). */
    data class Glyph(val char: String, val score: Float, val start: Int, val end: Int)

    /** Detector input size: long side capped at [limit], both sides multiples of 32 (≥ 32). */
    fun detectorSize(width: Int, height: Int, limit: Int): Pair<Int, Int> {
        val scale = min(1f, limit.toFloat() / max(width, height))
        fun round32(v: Float) = max(32, (v / 32f).roundToInt() * 32)
        return round32(width * scale) to round32(height * scale)
    }

    /**
     * Connected regions of `prob > thresh` (4-connected), kept when their mean probability reaches
     * [boxThresh], grown by DB's unclip distance (area × [unclipRatio] / perimeter).
     */
    fun boxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        maxCandidates: Int = 1000,
    ): List<MapBox> {
        val label = IntArray(width * height)
        val queue = IntArray(width * height)
        val out = ArrayList<MapBox>()
        var next = 0
        for (start in prob.indices) {
            if (label[start] != 0 || prob[start] <= thresh) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var sum = 0.0
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                sum += prob[p]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) tail = visit(p - 1, prob, label, queue, tail, thresh, next)
                if (x < width - 1) tail = visit(p + 1, prob, label, queue, tail, thresh, next)
                if (y > 0) tail = visit(p - width, prob, label, queue, tail, thresh, next)
                if (y < height - 1) tail = visit(p + width, prob, label, queue, tail, thresh, next)
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (min(w, h) < 3) continue
            val score = (sum / tail).toFloat()
            if (score < boxThresh) continue
            val distance = w.toFloat() * h * unclipRatio / (2f * (w + h))
            val d = ceil(distance).toInt()
            out += MapBox(
                left = max(0, minX - d),
                top = max(0, minY - d),
                right = min(width, maxX + 1 + d),
                bottom = min(height, maxY + 1 + d),
                score = score,
            )
            if (out.size >= maxCandidates) break
        }
        return out
    }

    private fun visit(p: Int, prob: FloatArray, label: IntArray, queue: IntArray, tail: Int, thresh: Float, id: Int): Int {
        if (label[p] != 0 || prob[p] <= thresh) return tail
        label[p] = id
        queue[tail] = p
        return tail + 1
    }

    /**
     * Greedy CTC decode of one line's `[timesteps × classes]` scores ([classes] per row). Class 0 is
     * the blank, class i the dictionary's (i − 1)th entry, the last class a space. Repeats collapse.
     */
    fun ctcDecode(scores: FloatArray, timesteps: Int, classes: Int, dictionary: List<String>): List<Glyph> {
        val glyphs = ArrayList<Glyph>()
        var prev = 0
        var t = 0
        while (t < timesteps) {
            val row = t * classes
            var best = 0
            var bestScore = scores[row]
            for (c in 1 until classes) {
                val s = scores[row + c]
                if (s > bestScore) {
                    best = c
                    bestScore = s
                }
            }
            if (best != 0 && best != prev) {
                glyphs += Glyph(charOf(best, dictionary), bestScore, t, t + 1)
            } else if (best != 0 && glyphs.isNotEmpty()) {
                val last = glyphs.last()
                glyphs[glyphs.lastIndex] = last.copy(end = t + 1)
            }
            prev = best
            t++
        }
        return glyphs
    }

    private fun charOf(index: Int, dictionary: List<String>): String =
        if (index - 1 < dictionary.size) dictionary[index - 1] else " "

    /**
     * Reads a PaddleOCR recognizer dictionary: one character per line (a `ppocr_keys`-style
     * `dict.txt`), or the `character_dict:` list of an exported `inference.yml`.
     */
    fun parseDictionary(text: String): List<String> {
        if (!text.contains("character_dict:")) return text.split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("character_dict:") }
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val out = ArrayList<String>()
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trimStart()
            val lineIndent = line.length - trimmed.length
            if (!trimmed.startsWith("- ") && trimmed != "-") {
                if (lineIndent <= indent) break else continue
            }
            out += yamlScalar(trimmed.removePrefix("-").removePrefix(" "))
        }
        return out
    }

    /** The YAML scalars PaddleOCR's exporter writes: plain, 'single' ('' escapes ') or "double" (\ escapes). */
    private fun yamlScalar(raw: String): String = when {
        raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1).replace("''", "'")
        raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") -> unescape(raw.substring(1, raw.length - 1))
        else -> raw
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> if (i + 5 < s.length) {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    'x' -> if (i + 3 < s.length) {
                        sb.append(s.substring(i + 2, i + 4).toInt(16).toChar())
                        i += 2
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
