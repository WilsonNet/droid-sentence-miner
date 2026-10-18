package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchAccentTest {

    // Copied from the user's existing Yomitan-made Anime Cards notes.
    private val byoketsu =
        """<svg xmlns="http://www.w3.org/2000/svg" focusable="false" viewBox="0 0 250 100" style="display:inline-block;vertical-align:middle;height:1.5em;"><path d="M25 75 L75 25 L125 25 L175 25" style="fill:none;stroke-width:5;stroke:currentColor;"></path><path d="M175 25 L225 25" style="fill:none;stroke-width:5;stroke:currentColor;stroke-dasharray:5 5;"></path><circle cx="25" cy="75" r="15" style="stroke-width:5;fill:currentColor;stroke:currentColor;"></circle><circle cx="75" cy="25" r="15" style="stroke-width:5;fill:currentColor;stroke:currentColor;"></circle><circle cx="125" cy="25" r="15" style="stroke-width:5;fill:currentColor;stroke:currentColor;"></circle><circle cx="175" cy="25" r="15" style="stroke-width:5;fill:currentColor;stroke:currentColor;"></circle><path d="M0 13 L15 -13 L-15 -13 Z" transform="translate(225,25)" style="fill:none;stroke-width:5;stroke:currentColor;"></path></svg>"""
    private val naka =
        """<svg xmlns="http://www.w3.org/2000/svg" focusable="false" viewBox="0 0 150 100" style="display:inline-block;vertical-align:middle;height:1.5em;"><path d="M25 25 L75 75" style="fill:none;stroke-width:5;stroke:currentColor;"></path><path d="M75 75 L125 75" style="fill:none;stroke-width:5;stroke:currentColor;stroke-dasharray:5 5;"></path><circle cx="25" cy="25" r="15" style="fill:none;stroke-width:5;stroke:currentColor;"></circle><circle cx="25" cy="25" r="5" style="fill:currentColor;"></circle><circle cx="75" cy="75" r="15" style="stroke-width:5;fill:currentColor;stroke:currentColor;"></circle><path d="M0 13 L15 -13 L-15 -13 Z" transform="translate(125,75)" style="fill:none;stroke-width:5;stroke:currentColor;"></path></svg>"""

    @Test
    fun heibanGraphMatchesYomitanByteForByte() {
        assertEquals(byoketsu, PitchAccent.graphSvg("びょうけつ", PitchAccent.Pitch(position = 0)))
    }

    @Test
    fun atamadakaGraphMatchesYomitanByteForByte() {
        assertEquals(naka, PitchAccent.graphSvg("なか", PitchAccent.Pitch(position = 1)))
    }

    @Test
    fun moraeKeepSmallKanaWithTheirMora() {
        assertEquals(listOf("びょ", "う", "け", "つ"), PitchAccent.morae("びょうけつ"))
        assertEquals(listOf("シャ", "ン", "デ", "リ", "ア"), PitchAccent.morae("シャンデリア"))
    }

    @Test
    fun convertsKanjiumLines() {
        val (term, data) = PitchAccent.fromKanjiumLine("１\tひと\t0,2")!!
        assertEquals("１", term)
        val entry = PitchAccent.parse(data)!!
        assertEquals("ひと", entry.reading)
        assertEquals(listOf(0, 2), entry.pitches.map { it.position })
        // Kana-only words have an empty reading column.
        assertEquals("シャンデリア", PitchAccent.parse(PitchAccent.fromKanjiumLine("シャンデリア\t\t3")!!.second)!!.reading)
    }

    @Test
    fun parsesYomitanPatternPitches() {
        val entry = PitchAccent.parse("""{"reading":"はし","pitches":[{"position":"LHL"},{"position":2,"tags":["n"]}]}""")!!
        assertEquals("LHL", entry.pitches[0].pattern)
        assertEquals(2, entry.pitches[1].position)
    }

    @Test
    fun ankiFieldUsesMatchingReadingAndListsSeveralPitches() {
        val entries = listOf(
            PitchAccent.Entry("なか", listOf(PitchAccent.Pitch(position = 1))),
            PitchAccent.Entry("ちゅう", listOf(PitchAccent.Pitch(position = 0))),
        )
        assertEquals(naka, PitchAccent.ankiGraphs("なか", entries))
        val two = PitchAccent.ankiGraphs("ひと", listOf(PitchAccent.Entry("ひと", listOf(PitchAccent.Pitch(0), PitchAccent.Pitch(2)))))
        assertTrue(two.startsWith("<ol><li><svg") && two.endsWith("</svg></li></ol>"))
        assertEquals("", PitchAccent.ankiGraphs("よう", entries))
    }
}
