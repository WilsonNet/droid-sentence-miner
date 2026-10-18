package io.github.wilsonnet.droidsentenceminer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAreaTest {

    @Test
    fun roundTripsThroughThePrefString() {
        val areas = listOf(TextArea(0.1f, 0.7f, 0.9f, 0.95f), TextArea(0.05f, 0.62f, 0.3f, 0.68f))
        val decoded = TextArea.decode(TextArea.encode(areas))
        assertEquals(2, decoded.size)
        assertEquals(0.7f, decoded[0].top, 0.0001f)
        assertEquals(0.3f, decoded[1].right, 0.0001f)
    }

    @Test
    fun ignoresJunkAndDegenerateBoxes() {
        assertTrue(TextArea.decode(null).isEmpty())
        assertTrue(TextArea.decode("garbage;1,2,3").isEmpty())
        assertTrue(TextArea.decode("0.5,0.5,0.5,0.9").isEmpty())
        assertEquals(1, TextArea.decode("0.1,0.1,0.5,0.5;;oops").size)
    }

    @Test
    fun clampsOutOfRangeValues() {
        val area = TextArea.decode("-0.2,0.5,1.4,0.9").single()
        assertEquals(0f, area.left)
        assertEquals(1f, area.right)
    }

    @Test
    fun ordersNameBoxBeforeDialogue() {
        val dialogue = TextArea(0.1f, 0.75f, 0.9f, 0.95f)
        val name = TextArea(0.08f, 0.66f, 0.3f, 0.72f)
        assertEquals(listOf(name, dialogue), TextArea.ordered(listOf(dialogue, name)))
    }
}
