package io.github.wilsonnet.droidsentenceminer.ui.components

import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import kotlin.math.min

private const val BG_DARK_HEX = 0xFF0E0E10L
private const val BAR_DARK_HEX = 0xFF18181BL
private const val HANDLE_SLOP_DP = 26
private const val SCRIM_ALPHA = 0.62f
private const val GUIDE_ALPHA = 0.4f
private const val THIRDS = 3f
private const val DOUBLE_THIRD = 2f
private const val ARM_MAX_RATIO = 0.35f

/**
 * Full-screen interactive panel cropper: drag corners/edges to resize, drag inside to move,
 * or drag anywhere outside the box to draw a fresh panel rectangle.
 */
@Suppress("FunctionName")
@Composable
fun PanelCropperDialog(
    bitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
    initialFocus: BoundingBox? = null,
    confirmLabel: String = "Add to Anki",
) {
    var crop by remember(bitmap, initialFocus) {
        mutableStateOf(
            PanelCropMath.initialPanelRect(
                imgWidth = bitmap.width,
                imgHeight = bitmap.height,
                focusBox = initialFocus,
            ),
        )
    }
    val pixelBox =
        remember(crop, bitmap) {
            PanelCropMath.toPixelBox(rect = crop, imgWidth = bitmap.width, imgHeight = bitmap.height)
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(BG_DARK_HEX),
            contentColor = Color.White,
        ) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CropTopBar(onDismiss = onDismiss)
                CropPresetsBar(
                    bitmap = bitmap,
                    initialFocus = initialFocus,
                    crop = crop,
                    pixelBox = pixelBox,
                    onSelectCrop = { crop = it },
                )
                CropViewport(
                    bitmap = bitmap,
                    crop = crop,
                    onCropChange = { crop = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                )
                CropActionBar(
                    confirmLabel = confirmLabel,
                    onDismiss = onDismiss,
                    onConfirm = { onConfirm(PanelCropMath.cropBitmap(bitmap, crop)) },
                )
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun CropTopBar(onDismiss: () -> Unit) {
    val barModifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    Row(modifier = barModifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Cancel crop", tint = Color.White)
        }
        Icon(
            imageVector = Icons.Default.Crop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Crop panel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                "Drag handles to resize, inside to move, or drag outside to redraw",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun CropPresetsBar(
    bitmap: Bitmap,
    initialFocus: BoundingBox?,
    crop: NormalizedCropRect,
    pixelBox: BoundingBox,
    onSelectCrop: (NormalizedCropRect) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val panelPreset =
            remember(bitmap, initialFocus) {
                PanelCropMath.initialPanelRect(imgWidth = bitmap.width, imgHeight = bitmap.height, focusBox = initialFocus)
            }
        FilterChip(
            selected = crop == panelPreset,
            onClick = { onSelectCrop(panelPreset) },
            label = { Text("Panel") },
        )
        if (initialFocus != null) {
            val bubblePreset =
                remember(bitmap, initialFocus) {
                    PanelCropMath.tightBubbleRect(imgWidth = bitmap.width, imgHeight = bitmap.height, focusBox = initialFocus)
                }
            FilterChip(
                selected = crop == bubblePreset,
                onClick = { onSelectCrop(bubblePreset) },
                label = { Text("Bubble") },
            )
        }
        FilterChip(
            selected = crop.isFullPage,
            onClick = { onSelectCrop(NormalizedCropRect(left = 0f, top = 0f, right = 1f, bottom = 1f)) },
            label = { Text("Full page") },
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${pixelBox.width} × ${pixelBox.height} px",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun CropViewport(
    bitmap: Bitmap,
    crop: NormalizedCropRect,
    onCropChange: (NormalizedCropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var latestCrop by remember { mutableStateOf(crop) }
    latestCrop = crop

    Box(
        modifier = modifier.onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center,
    ) {
        if (viewport.width > 0 && viewport.height > 0) {
            val fit = min(a = viewport.width.toFloat() / bitmap.width, b = viewport.height.toFloat() / bitmap.height)
            val imgW = bitmap.width * fit
            val imgH = bitmap.height * fit
            val metrics =
                CropImageMetrics(
                    left = (viewport.width - imgW) / 2f,
                    top = (viewport.height - imgH) / 2f,
                    width = imgW,
                    height = imgH,
                )
            val handleSlop = with(density) { HANDLE_SLOP_DP.dp.toPx() }
            val primary = MaterialTheme.colorScheme.primary

            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "Page to crop",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            val canvasModifier =
                Modifier.fillMaxSize().pointerInput(bitmap, viewport) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val mode =
                            hitTestCropMode(
                                pos = down.position,
                                metrics = metrics,
                                crop = latestCrop,
                                slop = handleSlop,
                            )
                        isDragging = true
                        val gesture =
                            CropDragStart(
                                downId = down.id,
                                startPos = down.position,
                                startCrop = latestCrop,
                                mode = mode,
                            )
                        trackCropDrag(gesture = gesture, metrics = metrics, onCropChange = onCropChange)
                        isDragging = false
                    }
                }
            Canvas(modifier = canvasModifier) {
                drawCropOverlay(metrics = metrics, crop = crop, isDragging = isDragging, primary = primary)
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun CropActionBar(confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val barModifier = Modifier.fillMaxWidth().background(Color(BAR_DARK_HEX)).padding(horizontal = 16.dp, vertical = 12.dp)
    Row(
        modifier = barModifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text("Cancel", color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onConfirm) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(confirmLabel)
        }
    }
}

private fun DrawScope.drawCropOverlay(
    metrics: CropImageMetrics,
    crop: NormalizedCropRect,
    isDragging: Boolean,
    primary: Color,
) {
    val cl = metrics.left + crop.left * metrics.width
    val ct = metrics.top + crop.top * metrics.height
    val cr = metrics.left + crop.right * metrics.width
    val cb = metrics.top + crop.bottom * metrics.height
    val cw = cr - cl
    val ch = cb - ct

    val scrim =
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(
                Rect(
                    left = metrics.left,
                    top = metrics.top,
                    right = metrics.left + metrics.width,
                    bottom = metrics.top + metrics.height,
                ),
            )
            addRect(Rect(left = cl, top = ct, right = cr, bottom = cb))
        }
    drawPath(scrim, Color.Black.copy(alpha = SCRIM_ALPHA))
    drawRect(
        color = Color.White,
        topLeft = Offset(x = cl, y = ct),
        size = Size(width = cw, height = ch),
        style = Stroke(width = 2.dp.toPx()),
    )

    if (isDragging) drawCropGuides(Rect(left = cl, top = ct, right = cr, bottom = cb))
    drawCropCornerHandles(Rect(left = cl, top = ct, right = cr, bottom = cb), primary)
}

private fun DrawScope.drawCropGuides(cropBounds: Rect) {
    val guideColor = Color.White.copy(alpha = GUIDE_ALPHA)
    val sw = 1.dp.toPx()
    val cl = cropBounds.left
    val ct = cropBounds.top
    val cr = cropBounds.right
    val cb = cropBounds.bottom
    val cw = cr - cl
    val ch = cb - ct
    drawLine(color = guideColor, start = Offset(x = cl + cw / THIRDS, y = ct), end = Offset(x = cl + cw / THIRDS, y = cb), strokeWidth = sw)
    drawLine(
        color = guideColor,
        start = Offset(x = cl + DOUBLE_THIRD * cw / THIRDS, y = ct),
        end = Offset(x = cl + DOUBLE_THIRD * cw / THIRDS, y = cb),
        strokeWidth = sw,
    )
    drawLine(color = guideColor, start = Offset(x = cl, y = ct + ch / THIRDS), end = Offset(x = cr, y = ct + ch / THIRDS), strokeWidth = sw)
    drawLine(
        color = guideColor,
        start = Offset(x = cl, y = ct + DOUBLE_THIRD * ch / THIRDS),
        end = Offset(x = cr, y = ct + DOUBLE_THIRD * ch / THIRDS),
        strokeWidth = sw,
    )
}

private fun DrawScope.drawCropCornerHandles(cropBounds: Rect, primary: Color) {
    val cl = cropBounds.left
    val ct = cropBounds.top
    val cr = cropBounds.right
    val cb = cropBounds.bottom
    val cw = cr - cl
    val ch = cb - ct
    val arm = min(a = 22.dp.toPx(), b = min(a = cw, b = ch) * ARM_MAX_RATIO)
    val strokeW = 4.dp.toPx()
    drawLine(color = primary, start = Offset(x = cl, y = ct), end = Offset(x = cl + arm, y = ct), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cl, y = ct), end = Offset(x = cl, y = ct + arm), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cr - arm, y = ct), end = Offset(x = cr, y = ct), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cr, y = ct), end = Offset(x = cr, y = ct + arm), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cl, y = cb - arm), end = Offset(x = cl, y = cb), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cl, y = cb), end = Offset(x = cl + arm, y = cb), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cr - arm, y = cb), end = Offset(x = cr, y = cb), strokeWidth = strokeW)
    drawLine(color = primary, start = Offset(x = cr, y = cb - arm), end = Offset(x = cr, y = cb), strokeWidth = strokeW)
}
