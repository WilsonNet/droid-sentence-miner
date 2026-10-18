package io.github.wilsonnet.droidsentenceminer.textlog

import io.github.wilsonnet.droidsentenceminer.textlog.TextLineTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLineTrackerTest {

    private fun TextLineTracker.feed(vararg reads: Pair<String, Long>): List<Event> =
        reads.flatMap { (text, at) -> onRead(text, at) }

    @Test
    fun lineStartsWhenFirstSeenOnceStable() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed("今日はいい天気ですね" to 1000, "今日はいい天気ですね" to 1400)
        assertEquals(listOf(Event.Started("今日はいい天気ですね", 1000)), events)
    }

    @Test
    fun singleNoisyReadIsIgnored() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed("今日は" to 1000, "" to 1400, "" to 1800)
        assertTrue(events.isEmpty())
    }

    @Test
    fun ocrJitterOnTheSameLineDoesNotSplitIt() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed(
            "俺はもう行かなきゃならない" to 0,
            "俺はもう行かなきゃならない" to 400,
            "俺はもう行かなさゃならない" to 800, // one misread kana
            "俺はもう行かなきゃならない" to 1200,
        )
        assertEquals(listOf(Event.Started("俺はもう行かなきゃならない", 0)), events)
    }

    @Test
    fun longerNoisyReadDoesNotReplaceTheLine() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed(
            "私の運転の仕方が悪かったんだろうか" to 0,
            "私の運転の仕方が悪悪かったんだろうか" to 400,
            "私の運転の仕方が悪かったんだろうか" to 800,
        )
        assertEquals(listOf(Event.Started("私の運転の仕方が悪かったんだろうか", 0)), events)
    }

    @Test
    fun lineSettlesOnTheConsensusOfItsReads() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed(
            "特に予約じなくても乗れるみたいで" to 0,
            "特に予約しなくても乗れるみたいで" to 400,
            "特に予約しなくても乗れるみたいで" to 800,
            "特に予約しなくても乗れるみたいて" to 1200,
            "特に予約しなくても乗れるみたいで" to 1600,
            "" to 2000,
            "" to 2400,
        )
        assertEquals(Event.Started("特に予約じなくても乗れるみたいで", 0), events.first())
        assertTrue(events.contains(Event.Settled("特に予約しなくても乗れるみたいで")))
        assertEquals(Event.Ended(2000), events.last())
    }

    @Test
    fun noisyReReadOfTheSameLineDoesNotStartANewLine() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed(
            "ちょっと昔ながらの温泉街って感じしない" to 0,
            "ちょっと昔ながらの温泉街って感じしない" to 400,
            "もはっと普ながらの温泉街って感じしない" to 800,
            "ちよっと貴ながらの温街うて感じしない" to 1200,
            "ちよっと貴ながらの温街うて感じしない" to 1600,
        )
        assertTrue(events.none { it is Event.Ended })
        assertEquals(1, events.count { it is Event.Started })
    }

    @Test
    fun consensusPicksTheMedoid() {
        assertEquals("送迎バスが30分ごとに出ているので", TextSimilarity.consensus(listOf(
            "送迎バスが30分ごとに出いるので",
            "送迎バスが30分ごとに出ているので",
            "送迎バスが30分ごとにているので",
            "送迎バスが30分ごとに出ているのて",
        )))
    }

    @Test
    fun replacementEndsPreviousLineAtTheSameInstant() {
        val tracker = TextLineTracker(stableReads = 2)
        tracker.feed("最初のセリフです" to 0, "最初のセリフです" to 400)
        val events = tracker.feed("次の全然違う言葉" to 2000, "次の全然違う言葉" to 2400)
        assertEquals(listOf(Event.Ended(2000), Event.Started("次の全然違う言葉", 2000)), events)
    }

    @Test
    fun blankScreenEndsTheLine() {
        val tracker = TextLineTracker(stableReads = 2)
        tracker.feed("セリフ" to 0, "セリフ" to 400)
        val events = tracker.feed("" to 3000, "" to 3400)
        assertEquals(listOf(Event.Ended(3000)), events)
    }

    @Test
    fun typewriterTextGrowsTheSameLine() {
        val tracker = TextLineTracker(stableReads = 2)
        val events = tracker.feed(
            "これは" to 0,
            "これは" to 100,
            "これはテスト" to 200,
            "これはテストです" to 300,
            "これはテストです" to 400,
        )
        assertEquals(Event.Started("これは", 0), events.first())
        assertEquals(Event.Updated("これはテストです"), events.last())
        assertTrue(events.none { it is Event.Ended })
    }

    @Test
    fun noisyFirstReadStillDatesTheLineFromTheScreenChange() {
        val tracker = TextLineTracker(stableReads = 2)
        tracker.feed("最初のセリフです" to 0, "最初のセリフです" to 400)
        // A fade frame reads as garbage before the next line settles.
        val events = tracker.feed("次のミ" to 2000, "次の全然違う言葉" to 2400, "次の全然違う言葉" to 2800)
        assertEquals(listOf(Event.Ended(2000), Event.Started("次の全然違う言葉", 2000)), events)
    }

    @Test
    fun captionBoxEdgesAreStripped() {
        assertEquals("寝る時のことを考えると", TextSimilarity.clean("|寝る時のことを考えると|"))
        assertEquals("道の駅、都留", TextSimilarity.clean("｜道の駅、都留"))
    }

    @Test
    fun multiRowTextIsStoredAsOneSentence() {
        assertEquals("やっぱり温泉って秘策のイメージがあるじゃないですか", TextSimilarity.clean("やっぱり温泉って\n秘策のイメージがあるじゃないですか"))
        assertEquals("Final Fantasy XIVの世界", TextSimilarity.clean("Final Fantasy\nXIVの世界"))
    }

    @Test
    fun similarityIgnoresPunctuationAndSpaces() {
        assertEquals(1.0, TextSimilarity.ratio("そうだね、行こう！", "そうだね 行こう"), 0.0001)
        assertTrue(TextSimilarity.ratio("全然違う", "まったく別") < 0.3)
    }

    @Test
    fun clipWindowUsesEarliestOfEndAndNextLine() {
        val bounds = ClipWindow.compute(
            lineStartMs = 10_000, lineEndMs = 13_000, nextStartMs = 12_500, nowMs = 60_000,
            beforeMs = 500, afterMs = 300,
        )
        assertEquals(ClipWindow.Bounds(9_500, 12_800), bounds)
    }

    @Test
    fun openLineClipsUpToNowAndIsCapped() {
        val open = ClipWindow.compute(10_000, null, null, nowMs = 14_000, beforeMs = 0, afterMs = 500)
        assertEquals(ClipWindow.Bounds(10_000, 14_000), open)
        val capped = ClipWindow.compute(0, null, null, nowMs = 100_000, beforeMs = 0, afterMs = 0, maxLengthMs = 30_000)
        assertEquals(30_000L, capped.endMs)
    }
}
