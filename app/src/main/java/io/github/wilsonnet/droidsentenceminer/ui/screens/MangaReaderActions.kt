package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.geometry.Offset
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.manga.MangaBlock
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageOcr
import io.github.wilsonnet.droidsentenceminer.manga.MangaReadingMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaSpreadLayout
import io.github.wilsonnet.droidsentenceminer.manga.MangaSpreadSlot
import io.github.wilsonnet.droidsentenceminer.manga.MangaVolumeEntity
import io.github.wilsonnet.droidsentenceminer.manga.glyphAt
import io.github.wilsonnet.droidsentenceminer.manga.nearestGlyph
import io.github.wilsonnet.droidsentenceminer.manga.withText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val LOOKAHEAD_DOUBLE = 3
private const val LOOKAHEAD_SINGLE = 2

internal data class MangaPageCacheState(
    val container: AppContainer,
    val volume: MangaVolumeEntity,
    val entries: List<String>,
    val bitmaps: MutableMap<Int, Bitmap>,
    val ocrs: MutableMap<Int, MangaPageOcr>,
    val ocrRunning: MutableMap<Int, Boolean>,
)

internal data class MangaReaderNavState(
    val scope: CoroutineScope,
    val pageCount: Int,
    val readingMode: MangaReadingMode,
    val slots: List<MangaSpreadSlot>,
    val listState: LazyListState,
    val pagerState: PagerState,
    val onSetPage: (Int) -> Unit,
)

internal data class MangaBoxEditContext(
    val scope: CoroutineScope,
    val container: AppContainer,
    val volumeId: Long,
    val target: MangaEditingBox,
    val pageOcrs: MutableMap<Int, MangaPageOcr>,
)

internal suspend fun syncPageAndPreload(
    cache: MangaPageCacheState,
    currentPage: Int,
    prefsState: ReaderPrefsState,
    scope: CoroutineScope,
    loadPage: suspend (Int, Boolean) -> Unit,
) {
    if (cache.entries.isEmpty()) return
    cache.container.mangaRepo.updateProgress(cache.volume.id, currentPage)
    loadPage(currentPage, false)
    if (prefsState.readingMode == MangaReadingMode.DOUBLE_PAGE && currentPage + 1 < cache.entries.size) {
        loadPage(currentPage + 1, false)
    }
    if (prefsState.isPreloadOcr) {
        val depth = if (prefsState.readingMode == MangaReadingMode.DOUBLE_PAGE) LOOKAHEAD_DOUBLE else LOOKAHEAD_SINGLE
        for (delta in 1..depth) {
            val target = currentPage + delta
            if (target in cache.entries.indices) scope.launch { loadPage(target, false) }
        }
    }
}

internal suspend fun loadMangaPage(
    cache: MangaPageCacheState,
    idx: Int,
    forceReOcr: Boolean,
    ocrMode: MangaOcrMode = MangaOcrMode.AUTO,
) {
    if (idx !in cache.entries.indices) return
    val decoded = cache.bitmaps[idx] ?: cache.container.mangaRepo.decodePage(cache.volume, cache.entries, idx)
    val bmp = decoded?.also { cache.bitmaps[idx] = it } ?: return
    val isSkipOcr = !forceReOcr && cache.ocrs.containsKey(idx)
    if (!isSkipOcr && cache.ocrRunning[idx] != true) {
        cache.ocrRunning[idx] = true
        try {
            cache.ocrs[idx] =
                if (forceReOcr) {
                    cache.container.mangaOcr.recognizeAndStore(
                        volumeId = cache.volume.id,
                        pageIndex = idx,
                        bitmap = bmp,
                        mode = ocrMode,
                    )
                } else {
                    cache.container.mangaOcr.getOrRecognize(
                        volumeId = cache.volume.id,
                        pageIndex = idx,
                        bitmap = bmp,
                        mode = ocrMode,
                    )
                }
        } finally {
            cache.ocrRunning[idx] = false
        }
    }
}

internal fun navigateToMangaPage(nav: MangaReaderNavState, pageIdx: Int) {
    val clamped = pageIdx.coerceIn(minimumValue = 0, maximumValue = (nav.pageCount - 1).coerceAtLeast(0))
    nav.onSetPage(clamped)
    nav.scope.launch {
        if (nav.readingMode == MangaReadingMode.VERTICAL_SCROLL) {
            nav.listState.scrollToItem(clamped)
        } else if (nav.slots.isNotEmpty()) {
            nav.pagerState.animateScrollToPage(MangaSpreadLayout.slotIndexForPage(nav.slots, clamped))
        }
    }
}

internal fun stepMangaSlot(nav: MangaReaderNavState, delta: Int, currentPage: Int) {
    if (nav.readingMode == MangaReadingMode.VERTICAL_SCROLL) {
        navigateToMangaPage(nav = nav, pageIdx = currentPage + delta)
    } else {
        val maxSlot = (nav.slots.size - 1).coerceAtLeast(0)
        val targetSlot = (nav.pagerState.currentPage + delta).coerceIn(minimumValue = 0, maximumValue = maxSlot)
        if (targetSlot != nav.pagerState.currentPage) {
            nav.scope.launch { nav.pagerState.animateScrollToPage(targetSlot) }
        }
    }
}

internal fun saveEditedMangaBox(ctx: MangaBoxEditContext, ocr: MangaPageOcr, cleaned: String) {
    val updatedBlocks = ocr.blocks.toMutableList()
    if (cleaned.isEmpty()) {
        updatedBlocks.removeAt(ctx.target.blockIndex)
    } else {
        updatedBlocks[ctx.target.blockIndex] = ctx.target.block.withText(cleaned)
    }
    val updated = ocr.copy(blocks = updatedBlocks, source = MangaPageOcr.SOURCE_EDITED)
    ctx.pageOcrs[ctx.target.pageIndex] = updated
    ctx.scope.launch { ctx.container.mangaOcr.save(ctx.volumeId, ctx.target.pageIndex, updated) }
}

internal fun hitGlyph(
    blocks: List<MangaBlock>,
    coords: PageCoords,
    localPos: Offset,
    slack: Float,
    editMode: Boolean,
): Pair<Int, Int>? {
    val p = coords.screenToOcr(localPos)
    return findGlyphHit(blocks = blocks, x = p.x, y = p.y, slack = 0f)
        ?: findGlyphHit(blocks = blocks, x = p.x, y = p.y, slack = slack)
        ?: findBubbleFallback(blocks = blocks, x = p.x, y = p.y, slack = slack, isEditMode = editMode)
}

private fun findGlyphHit(blocks: List<MangaBlock>, x: Float, y: Float, slack: Float): Pair<Int, Int>? {
    for (b in blocks.indices) {
        val glyph = blocks[b].glyphAt(x = x, y = y, slack = slack)
        if (glyph != null) return b to glyph
    }
    return null
}

private fun findBubbleFallback(
    blocks: List<MangaBlock>,
    x: Float,
    y: Float,
    slack: Float,
    isEditMode: Boolean,
): Pair<Int, Int>? {
    val boxIdx =
        blocks.indexOfFirst { block ->
            val b = block.box
            x in b.left - slack..b.right + slack && y in b.top - slack..b.bottom + slack
        }
    if (boxIdx < 0) return null
    val fallbackIdx = if (isEditMode) 0 else blocks[boxIdx].nearestGlyph(x = x, y = y) ?: 0
    return boxIdx to fallbackIdx
}
