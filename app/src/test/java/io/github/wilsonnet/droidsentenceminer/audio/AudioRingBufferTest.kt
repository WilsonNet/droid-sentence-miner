package io.github.wilsonnet.droidsentenceminer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    private val rate = 1000 // 1 sample per ms keeps the arithmetic readable

    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun sliceReturnsSamplesByWallClock() {
        val buffer = AudioRingBuffer(rate, 10)
        // 1000 samples ending at t=11_000 -> they span [10_000, 11_000)
        buffer.write(ramp(0, 1000), nowMs = 11_000)
        val slice = buffer.slice(10_200, 10_300)!!
        assertEquals(100, slice.frameCount)
        assertEquals(200, slice.pcm.first().toInt())
        assertEquals(299, slice.pcm.last().toInt())
    }

    @Test
    fun oldAudioFallsOutOfTheBuffer() {
        val buffer = AudioRingBuffer(rate, 2) // 2000 samples
        buffer.write(ramp(0, 1000), nowMs = 1_000)
        buffer.write(ramp(1000, 1000), nowMs = 2_000)
        buffer.write(ramp(2000, 1000), nowMs = 3_000)
        assertEquals(1_000L, buffer.oldestMs)
        assertEquals(2_000L, buffer.bufferedMs)
        assertNull(buffer.slice(0, 900))
        val slice = buffer.slice(500, 1_500)!!
        // Clamped to what is left: [1000, 1500)
        assertEquals(500, slice.frameCount)
        assertEquals(1000, slice.pcm.first().toInt())
    }

    @Test
    fun gapsArePaddedWithSilence() {
        val buffer = AudioRingBuffer(rate, 10)
        buffer.write(ramp(1, 1000), nowMs = 1_000)
        // Next chunk arrives 2 s later instead of 1 s: one second of silence is inserted.
        buffer.write(ramp(1, 1000), nowMs = 3_000)
        val gap = buffer.slice(1_100, 1_900)!!
        assertTrue(gap.pcm.all { it.toInt() == 0 })
        val after = buffer.slice(2_000, 2_010)!!
        assertEquals(1, after.pcm.first().toInt())
    }

    @Test
    fun coversReportsOverlap() {
        val buffer = AudioRingBuffer(rate, 2)
        assertFalse(buffer.covers(0, 10))
        buffer.write(ramp(0, 2000), nowMs = 5_000)
        assertTrue(buffer.covers(3_500, 4_000))
        assertTrue(buffer.covers(2_000, 3_100))
        assertFalse(buffer.covers(1_000, 2_900))
        assertFalse(buffer.covers(5_100, 6_000))
    }
}
