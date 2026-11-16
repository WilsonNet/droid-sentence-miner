package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.manga.MangaBlock
import io.github.wilsonnet.droidsentenceminer.manga.MangaDirection
import io.github.wilsonnet.droidsentenceminer.manga.MangaOverlayStyle
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageOcr
import io.github.wilsonnet.droidsentenceminer.manga.MangaReadingMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaSpreadLayout
import io.github.wilsonnet.droidsentenceminer.manga.MangaSpreadSlot
import io.github.wilsonnet.droidsentenceminer.manga.MangaTapZone
import io.github.wilsonnet.droidsentenceminer.manga.MangaVolumeEntity
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ui.components.WordLookupPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private const val READER_BG_HEX = 0xFF0B0B0DL
private const val ZOOM_THRESHOLD = 1.02f

internal data class MangaEditingBox(val pageIndex: Int, val blockIndex: Int, val block: MangaBlock)

internal data class ReaderPrefsState(
    val readingMode: MangaReadingMode,
    val direction: MangaDirection,
    val isCoverOffset: Boolean,
    val overlayStyle: MangaOverlayStyle,
    val hasBorders: Boolean,
    val tapZone: MangaTapZone,
    val isCropPanelOnMine: Boolean,
    val isPreloadOcr: Boolean,
)

private data class ReaderCharTapInput(
    val container: AppContainer,
    val scope: CoroutineScope,
    val isEditMode: Boolean,
    val block: MangaBlock,
    val pageIdx: Int,
    val blockIdx: Int,
    val charIdx: Int,
    val anchor: Rect,
)

private data class ReaderEmptyTapInput(
    val hasPicked: Boolean,
    val prefsState: ReaderPrefsState,
    val nx: Float,
    val ny: Float,
)

@Immutable
private data class SpreadSlotList(val items: List<MangaSpreadSlot>)

/**
 * Manatan- and Mokuro-style CBZ manga reader.
 * Supports Single, Double (with cover offset), and Vertical Scroll modes, RTL/LTR navigation,
 * Mokuro JSON or automatic on-device OCR overlays, text-box editing, and Anki panel cropping.
 */
@Suppress("FunctionName")
@Composable
fun MangaReaderScreen(volume: MangaVolumeEntity, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val entries = remember(volume.id, volume.entriesJson) { container.mangaRepo.entriesOf(volume) }
    val pageCount = entries.size

    val prefsState = rememberReaderPrefsState(container)
    val pageBitmaps = remember(volume.id) { mutableStateMapOf<Int, Bitmap>() }
    val pageOcrs = remember(volume.id) { mutableStateMapOf<Int, MangaPageOcr>() }
    val ocrRunning = remember(volume.id) { mutableStateMapOf<Int, Boolean>() }
    val pageCache =
        remember(container, volume, entries) {
            MangaPageCacheState(
                container = container,
                volume = volume,
                entries = entries,
                bitmaps = pageBitmaps,
                ocrs = pageOcrs,
                ocrRunning = ocrRunning,
            )
        }

    var currentPage by remember(volume.id) {
        mutableIntStateOf(volume.currentPage.coerceIn(minimumValue = 0, maximumValue = (pageCount - 1).coerceAtLeast(0)))
    }
    var isHudVisible by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<MangaPick?>(null) }
    var editingBox by remember { mutableStateOf<MangaEditingBox?>(null) }

    val loadPage: suspend (Int, Boolean) -> Unit = { idx, force ->
        loadMangaPage(cache = pageCache, idx = idx, forceReOcr = force)
    }

    LaunchedEffect(key1 = currentPage, key2 = pageCount, key3 = prefsState.isPreloadOcr) {
        syncPageAndPreload(
            cache = pageCache,
            currentPage = currentPage,
            prefsState = prefsState,
            scope = scope,
            loadPage = loadPage,
        )
    }

    BackHandler {
        when {
            picked != null -> picked = null
            editingBox != null -> editingBox = null
            isSettingsOpen -> isSettingsOpen = false
            isEditMode -> isEditMode = false
            else -> onClose()
        }
    }

    val slots =
        remember(pageCount, prefsState.readingMode, prefsState.isCoverOffset, prefsState.direction) {
            MangaSpreadLayout.buildSlots(
                pageCount = pageCount,
                doublePage = prefsState.readingMode == MangaReadingMode.DOUBLE_PAGE,
                coverOffset = prefsState.isCoverOffset,
                rtl = prefsState.direction.isRtl,
            )
        }
    val maxSlotIdx = (slots.size - 1).coerceAtLeast(0)
    val initialSlot = MangaSpreadLayout.slotIndexForPage(slots, currentPage).coerceIn(minimumValue = 0, maximumValue = maxSlotIdx)
    val pagerState = rememberPagerState(initialPage = initialSlot, pageCount = { slots.size.coerceAtLeast(1) })
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
    val navState =
        MangaReaderNavState(
            scope = scope,
            pageCount = pageCount,
            readingMode = prefsState.readingMode,
            slots = slots,
            listState = listState,
            pagerState = pagerState,
            onSetPage = { target ->
                picked = null
                currentPage = target
            },
        )

    val goToPage: (Int) -> Unit = { idx -> navigateToMangaPage(nav = navState, pageIdx = idx) }
    val stepSlot: (Int) -> Unit = { delta ->
        picked = null
        stepMangaSlot(nav = navState, delta = delta, currentPage = currentPage)
    }

    Box(modifier = modifier.fillMaxSize().background(Color(READER_BG_HEX))) {
        ReaderBody(
            pageCount = pageCount,
            slots = SpreadSlotList(slots),
            pagerState = pagerState,
            listState = listState,
            currentPage = currentPage,
            pageState = { idx ->
                val p = picked
                MangaPageRenderState(
                    pageIndex = idx,
                    bitmap = pageBitmaps[idx],
                    ocr = pageOcrs[idx],
                    picked = if (p != null && p.pageIndex == idx) p else null,
                )
            },
            prefsState = prefsState,
            editMode = isEditMode,
            onEnsurePage = { idx -> loadPage(idx, false) },
            onCurrentPageChange = { idx ->
                currentPage = idx
                picked = null
            },
            onTapChar = { pageIdx, blockIdx, charIdx, anchor ->
                val ocr = pageOcrs[pageIdx]
                val block = ocr?.run { blocks.getOrNull(blockIdx) }
                if (block != null) {
                    val tapInput =
                        ReaderCharTapInput(
                            container = container,
                            scope = scope,
                            isEditMode = isEditMode,
                            block = block,
                            pageIdx = pageIdx,
                            blockIdx = blockIdx,
                            charIdx = charIdx,
                            anchor = anchor,
                        )
                    onReaderCharTap(
                        input = tapInput,
                        onEdit = { box ->
                            editingBox = box
                        },
                        onPick = { pick ->
                            picked = pick
                        },
                    )
                }
            },
            onSelectRange = { p, b, r, a -> picked = MangaPick(pageIndex = p, blockIndex = b, range = r, anchor = a) },
            onTapEmpty = { nx, ny ->
                onReaderEmptyTap(
                    input = ReaderEmptyTapInput(hasPicked = picked != null, prefsState = prefsState, nx = nx, ny = ny),
                    onClearPick = { picked = null },
                    onNext = { stepSlot(+1) },
                    onPrev = { stepSlot(-1) },
                    onToggleHud = { isHudVisible = !isHudVisible },
                )
            },
        )

        ReaderHudOverlay(
            hudVisible = isHudVisible,
            title = volume.title,
            currentPage = currentPage,
            pageCount = pageCount,
            ocrSource = pageOcrs[currentPage]?.source,
            ocrBusy = ocrRunning[currentPage] == true,
            prefsState = prefsState,
            editMode = isEditMode,
            onBack = onClose,
            onReOcr = { scope.launch { loadPage(currentPage, true) } },
            onCycleOverlay = {
                val order = MangaOverlayStyle.entries
                val next = order[(order.indexOf(prefsState.overlayStyle) + 1) % order.size]
                scope.launch { container.prefs.setMangaOverlayStyle(next.id) }
            },
            onToggleEdit = { isEditMode = !isEditMode },
            onOpenSettings = { isSettingsOpen = true },
            onSeek = goToPage,
            onStep = stepSlot,
        )
    }

    ReaderDialogs(
        picked = picked,
        pickedOcr = picked?.let { pageOcrs[it.pageIndex] },
        pickedBitmap = picked?.let { pageBitmaps[it.pageIndex] },
        editingBox = editingBox,
        editingOcr = editingBox?.let { pageOcrs[it.pageIndex] },
        settingsOpen = isSettingsOpen,
        prefsState = prefsState,
        onDismissPick = { picked = null },
        onDismissEdit = { editingBox = null },
        onSaveEditedBox = { target, ocr, cleaned ->
            val editContext =
                MangaBoxEditContext(
                    scope = scope,
                    container = container,
                    volumeId = volume.id,
                    target = target,
                    pageOcrs = pageOcrs,
                )
            saveEditedMangaBox(ctx = editContext, ocr = ocr, cleaned = cleaned)
            editingBox = null
        },
        onDismissSettings = { isSettingsOpen = false },
        onUpdateSetting = { updater -> scope.launch { updater(container) } },
    )
}

private fun onReaderCharTap(
    input: ReaderCharTapInput,
    onEdit: (MangaEditingBox) -> Unit,
    onPick: (MangaPick) -> Unit,
) {
    if (input.isEditMode) {
        onEdit(MangaEditingBox(pageIndex = input.pageIdx, blockIndex = input.blockIdx, block = input.block))
    } else {
        input.scope.launch {
            val range = input.container.lookup.wordAt(input.block.text, input.charIdx)
            onPick(MangaPick(pageIndex = input.pageIdx, blockIndex = input.blockIdx, range = range, anchor = input.anchor))
        }
    }
}

private fun onReaderEmptyTap(
    input: ReaderEmptyTapInput,
    onClearPick: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleHud: () -> Unit,
) {
    if (input.hasPicked) {
        onClearPick()
    } else {
        val zoneAction =
            classifyTapZone(
                zone = input.prefsState.tapZone,
                nx = input.nx,
                ny = input.ny,
                rtl = input.prefsState.direction.isRtl,
            )
        when (zoneAction) {
            TapZoneAction.NEXT -> onNext()
            TapZoneAction.PREV -> onPrev()
            TapZoneAction.MENU -> onToggleHud()
        }
    }
}

@Composable
private fun rememberReaderPrefsState(container: AppContainer): ReaderPrefsState {
    val readingModeId by container.prefs.mangaReadingMode.collectAsState(initial = null)
    val directionId by container.prefs.mangaDirection.collectAsState(initial = null)
    val isCoverOffset by container.prefs.mangaCoverOffset.collectAsState(initial = true)
    val overlayStyleId by container.prefs.mangaOverlayStyle.collectAsState(initial = null)
    val hasBorders by container.prefs.mangaShowBorders.collectAsState(initial = true)
    val tapZoneId by container.prefs.mangaTapZone.collectAsState(initial = null)
    val isCropPanelOnMine by container.prefs.mangaCropPanelOnMine.collectAsState(initial = true)
    val isPreloadOcr by container.prefs.mangaPreloadOcr.collectAsState(initial = true)
    return ReaderPrefsState(
        readingMode = MangaReadingMode.fromId(readingModeId),
        direction = MangaDirection.fromId(directionId),
        isCoverOffset = isCoverOffset,
        overlayStyle = MangaOverlayStyle.fromId(overlayStyleId),
        hasBorders = hasBorders,
        tapZone = MangaTapZone.fromId(tapZoneId),
        isCropPanelOnMine = isCropPanelOnMine,
        isPreloadOcr = isPreloadOcr,
    )
}

@Suppress("FunctionName")
@Composable
private fun ReaderBody(
    pageCount: Int,
    slots: SpreadSlotList,
    pagerState: PagerState,
    listState: LazyListState,
    currentPage: Int,
    pageState: (Int) -> MangaPageRenderState,
    prefsState: ReaderPrefsState,
    editMode: Boolean,
    onEnsurePage: suspend (Int) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    onTapChar: (Int, Int, Int, Rect) -> Unit,
    onSelectRange: (Int, Int, IntRange, Rect) -> Unit,
    onTapEmpty: (Float, Float) -> Unit,
) {
    when {
        pageCount == 0 -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pages found in this archive.", color = Color.White)
            }
        }

        prefsState.readingMode == MangaReadingMode.VERTICAL_SCROLL -> {
            ReaderVerticalBody(
                pageCount = pageCount,
                listState = listState,
                pageState = pageState,
                prefs = prefsState,
                editMode = editMode,
                onEnsurePage = onEnsurePage,
                onCurrentPageChange = onCurrentPageChange,
                onTapChar = onTapChar,
                onSelectRange = onSelectRange,
                onTapEmpty = onTapEmpty,
            )
        }

        else -> {
            ReaderPagedBody(
                slots = slots,
                pagerState = pagerState,
                currentPage = currentPage,
                pageState = pageState,
                prefs = prefsState,
                editMode = editMode,
                onEnsurePage = onEnsurePage,
                onCurrentPageChange = onCurrentPageChange,
                onTapChar = onTapChar,
                onSelectRange = onSelectRange,
                onTapEmpty = onTapEmpty,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderVerticalBody(
    pageCount: Int,
    listState: LazyListState,
    pageState: (Int) -> MangaPageRenderState,
    prefs: ReaderPrefsState,
    editMode: Boolean,
    onEnsurePage: suspend (Int) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    onTapChar: (Int, Int, Int, Rect) -> Unit,
    onSelectRange: (Int, Int, IntRange, Rect) -> Unit,
    onTapEmpty: (Float, Float) -> Unit,
) {
    val currentOnEnsure by rememberUpdatedState(onEnsurePage)
    val currentOnPageChange by rememberUpdatedState(onCurrentPageChange)

    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx -> if (idx in 0 until pageCount) currentOnPageChange(idx) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(pageCount, key = { it }) { pageIdx ->
            LaunchedEffect(pageIdx) { currentOnEnsure(pageIdx) }
            VerticalMangaPageItem(
                state = pageState(pageIdx),
                overlayStyle = prefs.overlayStyle,
                showBorders = prefs.hasBorders,
                editMode = editMode,
                onTapChar = { b, c, a -> onTapChar(pageIdx, b, c, a) },
                onSelectRange = { b, r, a -> onSelectRange(pageIdx, b, r, a) },
                onTapEmpty = onTapEmpty,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderPagedBody(
    slots: SpreadSlotList,
    pagerState: PagerState,
    currentPage: Int,
    pageState: (Int) -> MangaPageRenderState,
    prefs: ReaderPrefsState,
    editMode: Boolean,
    onEnsurePage: suspend (Int) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    onTapChar: (Int, Int, Int, Rect) -> Unit,
    onSelectRange: (Int, Int, IntRange, Rect) -> Unit,
    onTapEmpty: (Float, Float) -> Unit,
) {
    val currentOnEnsure by rememberUpdatedState(onEnsurePage)
    val currentOnPageChange by rememberUpdatedState(onCurrentPageChange)

    LaunchedEffect(pagerState, slots) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { slotIdx ->
                slots.items.getOrNull(slotIdx)?.let { slot -> currentOnPageChange(slot.primaryPage) }
            }
    }
    LaunchedEffect(slots) {
        val targetSlot = MangaSpreadLayout.slotIndexForPage(slots.items, currentPage)
        if (targetSlot != pagerState.currentPage && targetSlot in slots.items.indices) pagerState.scrollToPage(targetSlot)
    }

    var isZoomed by remember { mutableStateOf(false) }

    HorizontalPager(
        state = pagerState,
        reverseLayout = prefs.direction.isRtl,
        userScrollEnabled = !isZoomed,
        modifier = Modifier.fillMaxSize(),
    ) { slotIdx ->
        val slot = slots.items.getOrNull(slotIdx)
        if (slot != null) {
            LaunchedEffect(slot) {
                slot.leftPage?.let { p -> currentOnEnsure(p) }
                slot.rightPage?.let { p -> currentOnEnsure(p) }
            }
            PagedMangaSlotView(
                slot = slot,
                leftState = slot.leftPage?.let(pageState),
                rightState = slot.rightPage?.let(pageState),
                overlayStyle = prefs.overlayStyle,
                showBorders = prefs.hasBorders,
                editMode = editMode,
                onZoomChange = { z -> isZoomed = z > ZOOM_THRESHOLD },
                onTapChar = onTapChar,
                onSelectRange = onSelectRange,
                onTapEmpty = onTapEmpty,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderDialogs(
    picked: MangaPick?,
    pickedOcr: MangaPageOcr?,
    pickedBitmap: Bitmap?,
    editingBox: MangaEditingBox?,
    editingOcr: MangaPageOcr?,
    settingsOpen: Boolean,
    prefsState: ReaderPrefsState,
    onDismissPick: () -> Unit,
    onDismissEdit: () -> Unit,
    onSaveEditedBox: (MangaEditingBox, MangaPageOcr, String) -> Unit,
    onDismissSettings: () -> Unit,
    onUpdateSetting: (suspend (AppContainer) -> Unit) -> Unit,
) {
    ReaderPickedPopup(
        picked = picked,
        ocr = pickedOcr,
        bitmap = pickedBitmap,
        cropOnMine = prefsState.isCropPanelOnMine,
        onDismiss = onDismissPick,
    )
    if (editingBox != null && editingOcr != null) {
        EditBoxDialog(
            target = editingBox,
            onDismiss = onDismissEdit,
            onSave = { cleaned -> onSaveEditedBox(editingBox, editingOcr, cleaned) },
        )
    }
    if (settingsOpen) {
        ReaderSettingsSheet(
            prefsState = prefsState,
            onDismiss = onDismissSettings,
            onReadingMode = { m -> onUpdateSetting { it.prefs.setMangaReadingMode(m.id) } },
            onDirection = { d -> onUpdateSetting { it.prefs.setMangaDirection(d.id) } },
            onCoverOffset = { v -> onUpdateSetting { it.prefs.setMangaCoverOffset(v) } },
            onOverlayStyle = { s -> onUpdateSetting { it.prefs.setMangaOverlayStyle(s.id) } },
            onShowBorders = { v -> onUpdateSetting { it.prefs.setMangaShowBorders(v) } },
            onTapZone = { z -> onUpdateSetting { it.prefs.setMangaTapZone(z.id) } },
            onCropPanelOnMine = { v -> onUpdateSetting { it.prefs.setMangaCropPanelOnMine(v) } },
            onPreloadOcr = { v -> onUpdateSetting { it.prefs.setMangaPreloadOcr(v) } },
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderPickedPopup(
    picked: MangaPick?,
    ocr: MangaPageOcr?,
    bitmap: Bitmap?,
    cropOnMine: Boolean,
    onDismiss: () -> Unit,
) {
    if (picked == null || ocr == null || bitmap == null) return
    val block = ocr.blocks.getOrNull(picked.blockIndex) ?: return
    val last = min(a = picked.range.last, b = block.text.lastIndex)
    if (picked.range.first <= last) {
        val word = block.text.substring(startIndex = picked.range.first, endIndex = last + 1)
        val sentence = remember(picked) { PageLayout.sentenceAt(block.text, picked.range.first) }
        val scaledBox =
            remember(block, ocr, bitmap) {
                scaleOcrBoxToBitmap(
                    box = block.boundingBox,
                    ocrW = ocr.imgWidth,
                    ocrH = ocr.imgHeight,
                    bmpW = bitmap.width,
                    bmpH = bitmap.height,
                )
            }
        WordLookupPopup(
            word = word,
            sentence = sentence,
            image = bitmap,
            anchor = picked.anchor,
            onDismiss = onDismiss,
            cropOnMine = cropOnMine,
            cropFocusBox = scaledBox,
        )
    }
}

private fun scaleOcrBoxToBitmap(box: BoundingBox, ocrW: Int, ocrH: Int, bmpW: Int, bmpH: Int): BoundingBox {
    val isMissingOcrSize = ocrW <= 0 || ocrH <= 0
    val isMatchesBitmap = ocrW == bmpW && ocrH == bmpH
    if (isMissingOcrSize || isMatchesBitmap) return box
    val sx = bmpW.toFloat() / ocrW
    val sy = bmpH.toFloat() / ocrH
    return BoundingBox(
        left = (box.left * sx).roundToInt().coerceIn(minimumValue = 0, maximumValue = (bmpW - 1).coerceAtLeast(0)),
        top = (box.top * sy).roundToInt().coerceIn(minimumValue = 0, maximumValue = (bmpH - 1).coerceAtLeast(0)),
        right = (box.right * sx).roundToInt().coerceIn(minimumValue = 1, maximumValue = bmpW),
        bottom = (box.bottom * sy).roundToInt().coerceIn(minimumValue = 1, maximumValue = bmpH),
    )
}
