package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pitch accent: reading Yomitan `term_meta` "pitch" entries and drawing the graph exactly like
 * Yomitan's `{pitch-accent-graphs}` Anki marker (yomitan/ext/js/display/pronunciation-generator.js
 * `createPronunciationGraph`, with the inline styles Yomitan bakes into Anki fields).
 *
 * Pure Kotlin, unit tested.
 */
object PitchAccent {

    /** A downstep position (0 = heiban) or an explicit H/L pattern such as "LHHL". */
    data class Pitch(val position: Int? = null, val pattern: String? = null)

    data class Entry(val reading: String, val pitches: List<Pitch>)

    private val json = Json { ignoreUnknownKeys = true }
    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"

    /** Parses one Yomitan pitch `term_meta` payload: `{"reading": "...", "pitches": [{"position": 0}, ...]}`. */
    fun parse(dataJson: String): Entry? = runCatching {
        val obj = json.parseToJsonElement(dataJson) as? JsonObject ?: return null
        val reading = obj["reading"]?.jsonPrimitive?.content ?: return null
        val pitches = (obj["pitches"] as? JsonArray).orEmpty().mapNotNull { element ->
            val position = (element as? JsonObject)?.get("position") as? JsonPrimitive ?: return@mapNotNull null
            position.intOrNull?.let { Pitch(position = it) }
                ?: position.content.takeIf { p -> p.isNotEmpty() && p.all { it == 'H' || it == 'L' } }?.let { Pitch(pattern = it) }
        }
        Entry(reading, pitches)
    }.getOrNull()

    /** Builds the Yomitan payload for one Kanjium `accents.txt` line (`term\treading\t0,2`). */
    fun fromKanjiumLine(line: String): Pair<String, String>? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val term = parts[0].trim()
        val reading = parts[1].trim().ifEmpty { term }
        val positions = parts[2].split(',').mapNotNull { it.trim().toIntOrNull() }
        if (term.isEmpty() || positions.isEmpty()) return null
        val data = buildJsonObject {
            put("reading", reading)
            put("pitches", buildJsonArray { positions.forEach { add(buildJsonObject { put("position", it) }) } })
        }
        return term to data.toString()
    }

    fun morae(text: String): List<String> {
        val out = ArrayList<String>()
        for (c in text) {
            if (c in SMALL_KANA && out.isNotEmpty()) out[out.lastIndex] = out.last() + c else out += c.toString()
        }
        return out
    }

    fun isHigh(moraIndex: Int, pitch: Pitch): Boolean {
        pitch.pattern?.let { return it.getOrNull(moraIndex) == 'H' }
        return when (val p = pitch.position ?: 0) {
            0 -> moraIndex > 0
            1 -> moraIndex < 1
            else -> moraIndex in 1 until p
        }
    }

    /** One graph, byte-for-byte the SVG Yomitan writes into Anki fields. */
    fun graphSvg(reading: String, pitch: Pitch): String {
        val morae = morae(reading)
        val n = morae.size
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" focusable=\"false\" viewBox=\"0 0 ${50 * (n + 1)} 100\" ")
            .append("style=\"display:inline-block;vertical-align:middle;height:1.5em;\">")
        if (n == 0) return sb.append("</svg>").toString()
        val points = ArrayList<String>()
        val dots = StringBuilder()
        for (i in 0 until n) {
            val high = isHigh(i, pitch)
            val x = i * 50 + 25
            val y = if (high) 25 else 75
            if (high && !isHigh(i + 1, pitch)) {
                dots.append("<circle cx=\"$x\" cy=\"$y\" r=\"15\" style=\"fill:none;stroke-width:5;stroke:currentColor;\"></circle>")
                dots.append("<circle cx=\"$x\" cy=\"$y\" r=\"5\" style=\"fill:currentColor;\"></circle>")
            } else {
                dots.append("<circle cx=\"$x\" cy=\"$y\" r=\"15\" style=\"stroke-width:5;fill:currentColor;stroke:currentColor;\"></circle>")
            }
            points += "$x $y"
        }
        val tx = n * 50 + 25
        val ty = if (isHigh(n, pitch)) 25 else 75
        sb.append("<path d=\"M${points.joinToString(" L")}\" style=\"fill:none;stroke-width:5;stroke:currentColor;\"></path>")
        sb.append("<path d=\"M${points.last()} L$tx $ty\" style=\"fill:none;stroke-width:5;stroke:currentColor;stroke-dasharray:5 5;\"></path>")
        sb.append(dots)
        sb.append("<path d=\"M0 13 L15 -13 L-15 -13 Z\" transform=\"translate($tx,$ty)\" style=\"fill:none;stroke-width:5;stroke:currentColor;\"></path>")
        return sb.append("</svg>").toString()
    }

    /**
     * The Anki "Graph" field for a word: graphs for every pitch of the matching reading, deduplicated
     * across dictionaries; a single graph is bare, several are an `<ol>` like Yomitan's template.
     */
    fun ankiGraphs(reading: String, entries: List<Entry>): String {
        val target = toHiragana(reading)
        val pitches = entries.filter { toHiragana(it.reading) == target }
            .flatMap { e -> e.pitches.map { e.reading to it } }
            .distinctBy { (_, p) -> p.position?.toString() ?: p.pattern }
        if (pitches.isEmpty()) return ""
        val svgs = pitches.map { (r, p) -> graphSvg(r, p) }
        return if (svgs.size == 1) svgs.single() else svgs.joinToString("", "<ol>", "</ol>") { "<li>$it</li>" }
    }

    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c)
    }
}
