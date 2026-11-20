package io.github.wilsonnet.droidsentenceminer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiPrompt
import io.github.wilsonnet.droidsentenceminer.ai.AiPromptRenderer
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import kotlinx.coroutines.launch

/**
 * Edits one prompt in place of the AI page. A built-in prompt keeps its name and can always go
 * back to its shipped text (*Restore original*); the user's prompts can be renamed and deleted.
 * Nothing is saved until *Save*; back discards.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiPromptEditor(prompt: AiPrompt, settings: AiSettings, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    var name by remember(prompt) { mutableStateOf(prompt.name) }
    var text by remember(prompt) { mutableStateOf(TextFieldValue(prompt.template)) }
    var isDefault by remember(prompt) {
        mutableStateOf(prompt.id.isNotBlank() && prompt.id == settings.defaultPrompt.id)
    }
    val draft = prompt.copy(name = name.trim(), template = text.text)
    BackHandler(onBack = onClose)
    val save: () -> Unit = {
        scope.launch {
            val saved = container.ai.prefs.savePrompt(draft)
            if (isDefault) container.ai.prefs.setDefaultPrompt(saved.id)
            onClose()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        val isSavable = draft.name.isNotBlank() && draft.template.isNotBlank()
        EditorBar(title = prompt.name.ifBlank { "New prompt" }, canSave = isSavable, onClose = onClose, onSave = save)
        if (prompt.isBuiltIn && draft.isModified) RestoreBanner { text = TextFieldValue(prompt.original.orEmpty()) }
        if (!prompt.isBuiltIn) NameField(name = name) { name = it }
        PromptField(text = text) { text = it }
        PlaceholderChips { text = text.inserting(it) }
        RunFirstSwitch(isDefault = isDefault) { isDefault = it }
        if (prompt.id.isNotBlank() && !prompt.isBuiltIn) DeletePromptButton(id = prompt.id, onDelete = onClose)
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun PromptField(text: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text("Prompt") },
        minLines = PROMPT_MIN_LINES,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun DeletePromptButton(id: String, onDelete: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val delete: () -> Unit = {
        scope.launch {
            container.ai.prefs.deletePrompt(id)
            onDelete()
        }
    }
    TextButton(onClick = delete) { Text("Delete prompt", color = MaterialTheme.colorScheme.error) }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun EditorBar(title: String, canSave: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Discard changes")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(enabled = canSave, onClick = onSave) { Text("Save") }
    }
}

// Placeholder chips (tap = insert at the cursor), then what each one becomes.
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun PlaceholderChips(onInsert: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TAG_GAP)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TAG_GAP)) {
            AiPromptRenderer.PLACEHOLDERS.forEach { (placeholder, _) ->
                AssistChip(onClick = { onInsert(placeholder) }, label = { Text(placeholder) })
            }
        }
        val meanings =
            AiPromptRenderer.PLACEHOLDERS.joinToString(", ") { (placeholder, meaning) ->
                "$placeholder = $meaning"
            }
        Text(
            text = "Tap one to insert it at the cursor. $meanings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun RunFirstSwitch(isDefault: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Run first", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "The AI button starts with this prompt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = isDefault, onCheckedChange = onChange)
    }
}

// A built-in prompt that differs from what shipped: the way back is always one tap away.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun RestoreBanner(onRestore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = BANNER_PADDING), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Edited by you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.History, contentDescription = null)
                Text("Restore original", modifier = Modifier.padding(start = TAG_GAP))
            }
        }
    }
}

// [placeholder] replacing the selection, cursor after it.
private fun TextFieldValue.inserting(placeholder: String): TextFieldValue {
    val start = minOf(a = selection.start, b = selection.end)
    val end = maxOf(a = selection.start, b = selection.end)
    val updated = text.replaceRange(startIndex = start, endIndex = end, replacement = placeholder)
    return TextFieldValue(updated, TextRange(start + placeholder.length))
}

private const val PROMPT_MIN_LINES = 8
private val FIELD_GAP = 12.dp
private val TAG_GAP = 8.dp
private val BANNER_PADDING = 16.dp
