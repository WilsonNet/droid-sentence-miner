package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.manga.MangaDirection
import io.github.wilsonnet.droidsentenceminer.manga.MangaOverlayStyle
import io.github.wilsonnet.droidsentenceminer.manga.MangaReadingMode
import io.github.wilsonnet.droidsentenceminer.manga.MangaTapZone

/** Section heading inside the reader settings sheet. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun ReaderSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** Small label above a control group. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun ReaderFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Single page / double page / vertical scroll. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun ReadingModeSegments(selected: MangaReadingMode, onSelect: (MangaReadingMode) -> Unit) {
    val entries = MangaReadingMode.entries
    Segments(
        count = entries.size,
        selectedIndex = entries.indexOf(selected),
        label = { i ->
            when (entries[i]) {
                MangaReadingMode.SINGLE_PAGE -> "Single"
                MangaReadingMode.DOUBLE_PAGE -> "Double"
                MangaReadingMode.VERTICAL_SCROLL -> "Scroll"
            }
        },
        onSelect = { i -> onSelect(entries[i]) },
    )
}

/** Right-to-left (manga) or left-to-right page order. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun DirectionSegments(selected: MangaDirection, onSelect: (MangaDirection) -> Unit) {
    val entries = MangaDirection.entries
    Segments(
        count = entries.size,
        selectedIndex = entries.indexOf(selected),
        label = { i -> if (entries[i] == MangaDirection.RTL) "Right to left" else "Left to right" },
        onSelect = { i -> onSelect(entries[i]) },
    )
}

/** How OCR text boxes draw over the artwork. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun OverlayStyleSegments(selected: MangaOverlayStyle, onSelect: (MangaOverlayStyle) -> Unit) {
    val entries = MangaOverlayStyle.entries
    Segments(
        count = entries.size,
        selectedIndex = entries.indexOf(selected),
        label = { i ->
            when (entries[i]) {
                MangaOverlayStyle.TRANSPARENT -> "Clear"
                MangaOverlayStyle.LIGHT_BOX -> "Light"
                MangaOverlayStyle.DARK_BOX -> "Dark"
                MangaOverlayStyle.HIDDEN -> "Hidden"
            }
        },
        onSelect = { i -> onSelect(entries[i]) },
    )
}

/** Where a tap turns the page. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun TapZoneSegments(selected: MangaTapZone, onSelect: (MangaTapZone) -> Unit) {
    val entries = MangaTapZone.entries
    Segments(
        count = entries.size,
        selectedIndex = entries.indexOf(selected),
        label = { i ->
            when (entries[i]) {
                MangaTapZone.EDGE -> "Edges"
                MangaTapZone.L_SHAPE -> "L-shape"
                MangaTapZone.OFF -> "Off"
            }
        },
        onSelect = { i -> onSelect(entries[i]) },
    )
}

// Full-width Material segmented row. No check icon: at 360 dp a four-way row has no room for it,
// and the filled segment already marks the choice.
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun Segments(count: Int, selectedIndex: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until count) {
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = count),
                icon = {},
                label = { Text(label(i), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A whole-row switch: title, one supporting line, the switch at the end; the row is the touch target. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One radio choice with a supporting line; the row is the touch target. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
internal fun SettingRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
