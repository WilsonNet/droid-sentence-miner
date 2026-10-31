package io.github.wilsonnet.droidsentenceminer.ui.components

import io.github.wilsonnet.droidsentenceminer.core.GlossaryMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CardEditorTest {

    @Test
    fun splitsLineIntoSentencesKeepingEndsAndQuotes() {
        assertEquals(
            listOf("「どこへ行くの？」", "彼女は笑った。", "そして……"),
            sentencesOf("「どこへ行くの？」彼女は笑った。そして……"),
        )
    }

    @Test
    fun quoteSplitAcrossSentencesLosesItsUnmatchedHalf() {
        // Nightshade, two sentences inside one 「」.
        assertEquals(
            listOf("だから、もう強く反対はしません。", "俺は、その想いを応援します"),
            sentencesOf("「だから、もう強く反対はしません。\n俺は、その想いを応援します」"),
        )
    }

    @Test
    fun oneSentenceIsOnePart() {
        assertEquals(listOf("猫が好きだ"), sentencesOf("猫が好きだ"))
    }

    @Test
    fun lineBreaksAreWrapsNotSentenceEnds() {
        // Nightshade wraps one sentence over two rows of the dialogue box.
        assertEquals(
            listOf("ええ、本当です。", "もちろん、貴方が任務に出られる時はこの俺もご一緒します"),
            sentencesOf("「ええ、本当です。\nもちろん、貴方が任務に出られる時は\nこの俺もご一緒します」"),
        )
    }

    @Test
    fun glossaryModeIdsAreYomitanMarkers() {
        assertEquals(GlossaryMode.FIRST, GlossaryMode.DEFAULT)
        assertEquals(GlossaryMode.FIRST, GlossaryMode.fromId("glossary-first"))
        assertEquals(null, GlossaryMode.fromId("nope"))
    }
}
