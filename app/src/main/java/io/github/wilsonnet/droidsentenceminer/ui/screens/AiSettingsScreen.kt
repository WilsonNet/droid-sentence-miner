package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiApps
import io.github.wilsonnet.droidsentenceminer.ai.AiCardPlacement
import io.github.wilsonnet.droidsentenceminer.ai.AiMode
import io.github.wilsonnet.droidsentenceminer.ai.AiPrompt
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import kotlinx.coroutines.launch

/**
 * The AI row of the Settings list: the master switch (off hides every AI control in the app) and,
 * while on, the way into [AiSettingsScreen].
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiSettingsRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val settings by container.ai.prefs.settings
        .collectAsState(initial = null)
    val current = settings ?: return
    val app = remember(current.appPackage) { AiApps.pick(context = context, preferred = current.appPackage) }
    val api = current.config?.let { "${it.model} · ${current.provider.name}" } ?: "Needs an API key"
    val subtitle =
        when {
            !current.isEnabled -> "Off: no AI buttons anywhere"
            current.mode == AiMode.API -> api
            else -> app?.let { "Opens ${it.name}" } ?: "Needs an AI app or an API key"
        }
    Card(onClick = { if (current.isEnabled) onOpen() }, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(ROW_PADDING), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(ROW_PADDING))
            Column(Modifier.weight(1f)) {
                Text("AI explanations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = current.isEnabled, onCheckedChange = { isOn ->
                scope.launch { container.ai.prefs.setEnabled(isOn) }
            })
            if (current.isEnabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Settings → AI explanations: where answers come from, the prompts, and what goes on cards. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val settings by container.ai.prefs.settings
        .collectAsState(initial = null)
    var editing by remember { mutableStateOf<AiPrompt?>(null) }
    val current = settings ?: return
    Column(
        modifier
            .fillMaxSize()
            // A fresh scroll for the editor: it opened at the list's position, title and Save off screen.
            .verticalScroll(remember(editing) { ScrollState(0) })
            .padding(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        val prompt = editing
        if (prompt != null) {
            AiPromptEditor(prompt = prompt, settings = current, onClose = { editing = null })
        } else {
            Text("AI explanations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Section(title = "Answers") { AnswerSource(current) }
            Section(title = "Prompts", description = PROMPTS_NOTE) {
                AiPromptList(settings = current, onEdit = { editing = it })
            }
            Section(title = "On the card", description = CARD_NOTE) { PlacementChoice(current) }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun Section(title: String, description: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ROW_PADDING), verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

// App hand-off or API key, and the chosen path's settings below.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AnswerSource(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val modes = listOf(AiMode.APP to "AI app", AiMode.API to "API key")
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { scope.launch { container.ai.prefs.setMode(mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    label = { Text(label) },
                )
            }
        }
        val explanation =
            if (settings.mode == AiMode.APP) {
                "The AI button opens the app with the prompt ready to send. Free, nothing to set up."
            } else {
                "Answers appear in the popup, streamed from the provider with your own key."
            }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.mode == AiMode.APP) AppChoice(settings) else AiProviderSection(settings)
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AppChoice(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val apps = remember { AiApps.installed(context) }
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
        if (apps.isEmpty()) {
            Text(
                "No AI app found. Install Gemini, Claude or ChatGPT, or switch to an API key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val selected = AiApps.pick(context = context, preferred = settings.appPackage)?.packageName
        apps.forEach { app ->
            val detail = if (app.isPrefilled) "Prompt goes straight into the message box" else "Prompt is also copied: paste it if needed"
            ChoiceRow(title = app.name, detail = detail, isSelected = app.packageName == selected) {
                scope.launch { container.ai.prefs.setAppPackage(app.packageName) }
            }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun PlacementChoice(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
        AiCardPlacement.entries.forEach { placement ->
            val detail =
                if (placement == AiCardPlacement.OWN_FIELD) {
                    "A field named Grammar or AI Explanation, or the one you pick in Anki → Fields."
                } else {
                    null
                }
            ChoiceRow(title = placement.label, detail = detail, isSelected = settings.placement == placement) {
                scope.launch { container.ai.prefs.setPlacement(placement) }
            }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ChoiceRow(title: String, detail: String?, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(RADIO_PADDING))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Settings → AI → Prompts: one row per prompt (name, first line, Default / Edited tags) and
 * *New prompt*. A tap opens [AiPromptEditor].
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiPromptList(settings: AiSettings, onEdit: (AiPrompt) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        settings.prompts.forEach { prompt ->
            PromptRow(prompt = prompt, isDefault = prompt.id == settings.defaultPrompt.id) { onEdit(prompt) }
        }
        TextButton(onClick = { onEdit(AiPrompt(id = "", name = "", template = "")) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("New prompt", modifier = Modifier.padding(start = TAG_GAP))
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun PromptRow(prompt: AiPrompt, isDefault: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = PROMPT_ROW_PADDING),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TAG_GAP), verticalAlignment = Alignment.CenterVertically) {
            Text(prompt.name, style = MaterialTheme.typography.bodyLarge)
            if (isDefault) Tag("Default")
            if (prompt.isModified) Tag("Edited")
        }
        Text(
            text =
            prompt.template
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun Tag(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = TAG_PADDING_H, vertical = TAG_PADDING_V),
        )
    }
}

private const val PROMPTS_NOTE =
    "The popup's AI button runs the first one; the others are a tap away. Built-in prompts can always be restored."
private const val CARD_NOTE = "Only an answer you opened in the popup is added. Mining never asks the AI on its own."
private val PAGE_PADDING = 16.dp
private val ROW_PADDING = 16.dp
private val SECTION_GAP = 14.dp
private val ITEM_GAP = 8.dp
private val RADIO_PADDING = 12.dp
private val TAG_GAP = 8.dp
private val TAG_PADDING_H = 6.dp
private val TAG_PADDING_V = 2.dp
private val PROMPT_ROW_PADDING = 10.dp
