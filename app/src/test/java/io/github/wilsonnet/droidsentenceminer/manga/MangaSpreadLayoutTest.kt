package io.github.wilsonnet.droidsentenceminer.manga

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaSpreadLayoutTest {
    @Test
    fun buildsSinglePageSlots() {
        val slots = MangaSpreadLayout.buildSlots(pageCount = 3, doublePage = false, coverOffset = false, rtl = true)
        assertEquals(
            listOf(
                MangaSpreadSlot(null, 0),
                MangaSpreadSlot(null, 1),
                MangaSpreadSlot(null, 2),
            ),
            slots,
        )
        assertEquals(1, MangaSpreadLayout.slotIndexForPage(slots, 1))
    }

    @Test
    fun buildsDoublePageRtlWithCoverOffset() {
        val slots = MangaSpreadLayout.buildSlots(pageCount = 5, doublePage = true, coverOffset = true, rtl = true)
        assertEquals(
            listOf(
                MangaSpreadSlot(leftPage = null, rightPage = 0),
                MangaSpreadSlot(leftPage = 2, rightPage = 1),
                MangaSpreadSlot(leftPage = 4, rightPage = 3),
            ),
            slots,
        )
        assertEquals(1, MangaSpreadLayout.slotIndexForPage(slots, 2))
        assertEquals(2, MangaSpreadLayout.slotIndexForPage(slots, 3))
    }

    @Test
    fun buildsDoublePageLtrWithoutCoverOffset() {
        val slots = MangaSpreadLayout.buildSlots(pageCount = 3, doublePage = true, coverOffset = false, rtl = false)
        assertEquals(
            listOf(
                MangaSpreadSlot(leftPage = 0, rightPage = 1),
                MangaSpreadSlot(leftPage = 2, rightPage = null),
            ),
            slots,
        )
        assertEquals(listOf(0, 1), slots[0].pagesInReadingOrder(rtl = false))
    }
}
