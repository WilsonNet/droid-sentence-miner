package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.manga.MangaDirection
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaOverlayStyle
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageOcr
import io.github.wilsonnet.droidsentenceminer.manga.MangaPreOcrQueue
import io.github.wilsonnet.droidsentenceminer.manga.MangaReadingMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaTapZone
import kotlin.math.roundToInt

private const val HUD_SCRIM_ALPHA = 0.78f
private const val BUSY_PILL_ALPHA = 0.7f
private const val MUTED_ICON_ALPHA = 0.45f
private const val SUBTITLE_ALPHA = 0.75f
private const val EDGE_PREV_FRAC = 0.16f
private const val EDGE_NEXT_FRAC = 0.84f
private const val THIRD_LOW = 0.22f
private const val THIRD_HIGH = 0.78f

internal enum class TapZoneAction {
    PREV,
    MENU,
    NEXT,
}

@Suppress("FunctionName")
@Composable
internal fun ReaderHudOverlay(
    hudVisible: Boolean,
    title: String,
    currentPage: Int,
    pageCount: Int,
    ocrSource: String?,
    ocrBusy: Boolean,
    prefsState: ReaderPrefsState,
    editMode: Boolean,
    onBack: () -> Unit,
    onReOcr: () -> Unit,
    onCycleOverlay: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpenSettings: () -> Unit,
    onSeek: (Int) -> Unit,
    onStep: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = title,
                pageLabel = "${currentPage + 1} / $pageCount",
                ocrSource = ocrSource,
                ocrBusy = ocrBusy,
                overlayStyle = prefsState.overlayStyle,
                editMode = editMode,
                onBack = onBack,
                onReOcr = onReOcr,
                onCycleOverlay = onCycleOverlay,
                onToggleEdit = onToggleEdit,
                onOpenSettings = onOpenSettings,
            )
        }

        AnimatedVisibility(
            visible = hudVisible && pageCount > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                currentPage = currentPage,
                pageCount = pageCount,
                rtl = prefsState.direction.isRtl,
                onSeek = onSeek,
                onStep = onStep,
            )
        }

        if (!hudVisible && ocrBusy) {
            val pillModifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = BUSY_PILL_ALPHA), modifier = pillModifier) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Reading page…", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
internal fun EditBoxDialog(target: MangaEditingBox, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(target) { mutableStateOf(target.block.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit text box") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.replace(oldValue = "\n", newValue = "").trim()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) {
                    val err = MaterialTheme.colorScheme.error
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = err,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = err)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Suppress("FunctionName")
@Composable
private fun ReaderTopBar(
    title: String,
    pageLabel: String,
    ocrSource: String?,
    ocrBusy: Boolean,
    overlayStyle: MangaOverlayStyle,
    editMode: Boolean,
    onBack: () -> Unit,
    onReOcr: () -> Unit,
    onCycleOverlay: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sourceLabel = ocrSourceLabel(ocrBusy, ocrSource)
    Surface(color = Color.Black.copy(alpha = HUD_SCRIM_ALPHA), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (sourceLabel.isNotEmpty()) "$pageLabel · $sourceLabel" else pageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = SUBTITLE_ALPHA),
                )
            }
            IconButton(onClick = onReOcr, enabled = !ocrBusy) {
                Icon(Icons.Default.Refresh, contentDescription = "Re-run OCR on page", tint = Color.White)
            }
            IconButton(onClick = onCycleOverlay) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = "Cycle text overlay (${overlayStyle.label})",
                    tint = if (overlayStyle == MangaOverlayStyle.HIDDEN) Color.White.copy(alpha = MUTED_ICON_ALPHA) else Color.White,
                )
            }
            IconButton(onClick = onToggleEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit text boxes",
                    tint = if (editMode) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Reader settings", tint = Color.White)
            }
        }
    }
}

private fun ocrSourceLabel(ocrBusy: Boolean, ocrSource: String?): String {
    val label =
        when {
            ocrBusy -> "Running OCR…"
            ocrSource == MangaPageOcr.SOURCE_MOKURO -> "Mokuro"
            ocrSource == MangaPageOcr.SOURCE_LENS -> "Lens"
            ocrSource == MangaPageOcr.SOURCE_EDITED -> "Edited"
            ocrSource == MangaPageOcr.SOURCE_OCR -> "Offline OCR"
            else -> ""
        }
    return label
}

@Suppress("FunctionName")
@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    pageCount: Int,
    rtl: Boolean,
    onSeek: (Int) -> Unit,
    onStep: (Int) -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = HUD_SCRIM_ALPHA), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onStep(if (rtl) +1 else -1) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Left page", tint = Color.White)
            }
            val sliderDir = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides sliderDir) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { value -> onSeek(value.roundToInt()) },
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = { onStep(if (rtl) -1 else +1) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Right page", tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
internal fun ReaderSettingsSheet(
    prefsState: ReaderPrefsState,
    hasMokuro: Boolean,
    batchProgress: MangaPreOcrQueue.VolumeProgress?,
    onDismiss: () -> Unit,
    onReadingMode: (MangaReadingMode) -> Unit,
    onDirection: (MangaDirection) -> Unit,
    onCoverOffset: (Boolean) -> Unit,
    onOverlayStyle: (MangaOverlayStyle) -> Unit,
    onShowBorders: (Boolean) -> Unit,
    onTapZone: (MangaTapZone) -> Unit,
    onOcrMode: (MangaOcrMode) -> Unit,
    onToggleBatchPreOcr: () -> Unit,
    onCropPanelOnMine: (Boolean) -> Unit,
    onPreloadOcr: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetModifier =
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = sheetModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reader settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ReaderLayoutSettings(
                prefsState = prefsState,
                onReadingMode = onReadingMode,
                onDirection = onDirection,
                onCoverOffset = onCoverOffset,
            )
            HorizontalDivider()
            ReaderOverlaySettings(
                prefsState = prefsState,
                onOverlayStyle = onOverlayStyle,
                onShowBorders = onShowBorders,
                onTapZone = onTapZone,
            )
            HorizontalDivider()
            ReaderOcrSettingsSection(
                ocrMode = prefsState.ocrMode,
                hasMokuro = hasMokuro,
                batchProgress = batchProgress,
                onOcrMode = onOcrMode,
                onToggleBatchPreOcr = onToggleBatchPreOcr,
            )
            HorizontalDivider()
            SettingToggleRow(
                title = "Crop panel on Add to Anki",
                subtitle = "Let you crop the exact manga panel before sending the card (recommended)",
                checked = prefsState.isCropPanelOnMine,
                onCheckedChange = onCropPanelOnMine,
                iconVector = Icons.Default.Crop,
            )
            SettingToggleRow(
                title = "Pre-OCR next pages",
                subtitle = "Run OCR ahead of your current page when no .mokuro is attached",
                checked = prefsState.isPreloadOcr,
                onCheckedChange = onPreloadOcr,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderLayoutSettings(
    prefsState: ReaderPrefsState,
    onReadingMode: (MangaReadingMode) -> Unit,
    onDirection: (MangaDirection) -> Unit,
    onCoverOffset: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MangaReadingMode.entries.forEach { mode ->
                FilterChip(
                    selected = prefsState.readingMode == mode,
                    onClick = { onReadingMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        if (prefsState.readingMode != MangaReadingMode.VERTICAL_SCROLL) {
            Text("Direction", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = prefsState.direction == dir,
                        onClick = { onDirection(dir) },
                        label = { Text(dir.label) },
                    )
                }
            }
        }
        if (prefsState.readingMode == MangaReadingMode.DOUBLE_PAGE) {
            SettingToggleRow(
                title = "Cover page offset",
                subtitle = "Show page 1 alone before pairing 2-page spreads",
                checked = prefsState.isCoverOffset,
                onCheckedChange = onCoverOffset,
            )
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun ReaderOverlaySettings(
    prefsState: ReaderPrefsState,
    onOverlayStyle: (MangaOverlayStyle) -> Unit,
    onShowBorders: (Boolean) -> Unit,
    onTapZone: (MangaTapZone) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Text overlay", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MangaOverlayStyle.entries.take(2).forEach { style ->
                FilterChip(
                    selected = prefsState.overlayStyle == style,
                    onClick = { onOverlayStyle(style) },
                    label = { Text(style.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MangaOverlayStyle.entries.drop(2).forEach { style ->
                FilterChip(
                    selected = prefsState.overlayStyle == style,
                    onClick = { onOverlayStyle(style) },
                    label = { Text(style.label) },
                )
            }
        }
        SettingToggleRow(
            title = "Show text box borders",
            subtitle = "Subtle outlines around detected speech bubbles",
            checked = prefsState.hasBorders,
            onCheckedChange = onShowBorders,
        )
        Text("Tap zones", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MangaTapZone.entries.forEach { zone ->
                FilterChip(
                    selected = prefsState.tapZone == zone,
                    onClick = { onTapZone(zone) },
                    label = { Text(zone.label.substringBefore(" (")) },
                )
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconVector: ImageVector? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun rawTapAction(zone: MangaTapZone, nx: Float, ny: Float): TapZoneAction {
    if (zone == MangaTapZone.OFF) return TapZoneAction.MENU
    val isPrev = if (zone == MangaTapZone.EDGE) nx < EDGE_PREV_FRAC else ny < THIRD_LOW || nx < THIRD_LOW
    val isNext = if (zone == MangaTapZone.EDGE) nx > EDGE_NEXT_FRAC else ny > THIRD_HIGH || nx > THIRD_HIGH
    return when {
        isPrev -> TapZoneAction.PREV
        isNext -> TapZoneAction.NEXT
        else -> TapZoneAction.MENU
    }
}

internal fun classifyTapZone(zone: MangaTapZone, nx: Float, ny: Float, rtl: Boolean): TapZoneAction {
    val raw = rawTapAction(zone, nx, ny)
    return when {
        !rtl || raw == TapZoneAction.MENU -> raw
        raw == TapZoneAction.PREV -> TapZoneAction.NEXT
        else -> TapZoneAction.PREV
    }
}
