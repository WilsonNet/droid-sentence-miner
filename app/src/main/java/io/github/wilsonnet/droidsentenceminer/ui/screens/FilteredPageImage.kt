package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageEnhancer
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageFilter
import io.github.wilsonnet.droidsentenceminer.ocr.PageUpscaler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * A manga page drawn fit-centred like `ContentScale.Fit`, through [MangaPageEnhancer] when [filter]
 * is on: the filtered copy is rendered at the exact pixel size of the slot and drawn on whole
 * pixels, so nothing re-blurs it. Until it is ready (or with the filter off) the decoded page is
 * drawn as before. With [isAiUpscale] the quick filtered page shows first and the AI one replaces
 * it when done (about a second), unless prefetching already prepared it.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun FilteredPageImage(
    bitmap: Bitmap,
    filter: MangaPageFilter,
    isAiUpscale: Boolean,
    isZoomed: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val size =
        remember(bitmap, box, isZoomed) {
            if (box.width > 0 && box.height > 0) {
                MangaPageEnhancer.targetSize(src = bitmap, boxWidth = box.width, boxHeight = box.height, isZoomed = isZoomed)
            } else {
                null
            }
        }
    val context = LocalContext.current
    var shown by remember(bitmap) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bitmap, filter, size, isAiUpscale) {
        if (size != null) {
            val request = PageRequest(src = bitmap, filter = filter, size = size)
            loadPage(context = context, request = request, isAiUpscale = isAiUpscale) { page -> shown = page }
        }
    }
    val paints = remember { PagePaints() }
    val drawn = shown ?: bitmap
    val sized =
        modifier.onSizeChanged { slot ->
            box = slot
            MangaPageEnhancer.rememberBox(width = slot.width, height = slot.height)
        }
    val boxModifier =
        sized.semantics {
            contentDescription = description
            role = Role.Image
        }
    Box(modifier = boxModifier.drawBehind { drawPage(page = drawn, source = bitmap, paints = paints) })
}

// One page to filter at one size.
private data class PageRequest(val src: Bitmap, val filter: MangaPageFilter, val size: Pair<Int, Int>)

// Shows the cached AI page, or the quick filter first and the AI page when it is done.
private suspend fun loadPage(
    context: Context,
    request: PageRequest,
    isAiUpscale: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    show: (Bitmap) -> Unit,
) {
    val src = request.src
    val filter = request.filter
    val size = request.size
    val upscaler = if (isAiUpscale) withContext(dispatcher) { PageUpscaler.get(context) } else null
    val ready = MangaPageEnhancer.cached(src = src, filter = filter, size = size, upscaler = upscaler)
    if (ready != null) {
        show(ready)
    } else {
        if (upscaler != null) {
            show(
                MangaPageEnhancer.cached(src = src, filter = filter, size = size)
                    ?: MangaPageEnhancer.enhance(src = src, filter = filter, size = size),
            )
        }
        show(MangaPageEnhancer.enhance(src = src, filter = filter, size = size, upscaler = upscaler))
    }
}

// Paper-white fill under ink bitmaps, black ink paint for ALPHA_8 pages, filtered paint for the rest.
private class PagePaints {
    val ink = Paint(Paint.FILTER_BITMAP_FLAG)
    val paper = Paint()
    val plain = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        ink.color = Color.BLACK
        paper.color = Color.WHITE
    }
}

private fun DrawScope.drawPage(page: Bitmap, source: Bitmap, paints: PagePaints) {
    val fit = min(a = size.width / source.width, b = size.height / source.height)
    val isExact = page !== source && abs(page.width - source.width * fit) < 1f
    val width = if (isExact) page.width.toFloat() else source.width * fit
    val height = if (isExact) page.height.toFloat() else source.height * fit
    val left = floor((size.width - width) / 2f)
    val top = floor((size.height - height) / 2f)
    val dst = RectF(left, top, left + width, top + height)
    drawIntoCanvas { canvas ->
        if (MangaPageEnhancer.isInk(page)) {
            canvas.nativeCanvas.drawRect(dst, paints.paper)
            canvas.nativeCanvas.drawBitmap(page, null, dst, paints.ink)
        } else {
            canvas.nativeCanvas.drawBitmap(page, null, dst, paints.plain)
        }
    }
}
