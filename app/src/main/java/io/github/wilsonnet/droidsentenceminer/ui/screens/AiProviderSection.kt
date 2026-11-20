package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiConfig
import io.github.wilsonnet.droidsentenceminer.ai.AiProtocol
import io.github.wilsonnet.droidsentenceminer.ai.AiProvider
import io.github.wilsonnet.droidsentenceminer.ai.AiProviders
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/** Settings → AI with an API key: provider, key, endpoint, model and a test request. */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiProviderSection(settings: AiSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val provider = settings.provider
    val prefs = container.ai.prefs
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        ProviderPicker(settings) { scope.launch { prefs.setProvider(it) } }
        provider.keyUrl?.let { url ->
            TextButton(onClick = { openUrl(context = context, url = url) }, contentPadding = TIGHT_BUTTON) {
                Text("Get a key from ${provider.name}")
            }
        }
        if (provider.hasEditableUrl) {
            DraftField(label = "Base URL", saved = settings.baseUrl, keyboard = KeyboardType.Uri) { url ->
                prefs.setBaseUrl(provider = provider, url = url)
            }
        }
        if (provider.id == AiProviders.CUSTOM_ID) {
            ProtocolPicker(settings.protocol) {
                scope.launch { prefs.setCustomProtocol(it) }
            }
        }
        if (provider.isKeyNeeded) {
            DraftField(
                label = "API key",
                saved = settings.apiKey,
                isSecret = true,
            ) { key -> prefs.setApiKey(provider = provider, key = key) }
        }
        ModelPicker(settings)
        TestRequest(settings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ProviderPicker(settings: AiSettings, onPick: (AiProvider) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = isOpen, onExpandedChange = { isOpen = it }) {
        OutlinedTextField(
            value = settings.provider.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            supportingText =
            settings.provider.note
                .takeIf { it.isNotBlank() }
                ?.let { note -> { Text(note) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isOpen) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            AiProviders.ALL.forEach { provider ->
                DropdownMenuItem(
                    text = { ProviderItem(provider) },
                    onClick = {
                        isOpen = false
                        onPick(provider)
                    },
                )
            }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ProviderItem(provider: AiProvider) {
    Column {
        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
        if (provider.note.isNotBlank()) {
            Text(provider.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ProtocolPicker(selected: AiProtocol, onPick: (AiProtocol) -> Unit) {
    val protocols = AiProtocol.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        protocols.forEachIndexed { i, protocol ->
            SegmentedButton(
                selected = protocol == selected,
                onClick = { onPick(protocol) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = protocols.size),
                icon = {},
                label = { Text(protocol.label.substringBefore(' '), maxLines = 1) },
            )
        }
    }
}

// A text field that saves itself shortly after typing stops (no Save button to forget).
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun DraftField(
    label: String,
    saved: String,
    isSecret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onSave: suspend (String) -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var isRevealed by remember { mutableStateOf(false) }
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(draft) {
        if (draft != saved) {
            delay(SAVE_DELAY_MS)
            save(draft)
        }
    }
    val isHidden = isSecret && !isRevealed
    val reveal: (@Composable () -> Unit)? =
        if (isSecret) {
            { RevealButton(isRevealed = isRevealed) { isRevealed = !isRevealed } }
        } else {
            null
        }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (isSecret) KeyboardType.Password else keyboard),
        visualTransformation = if (isHidden) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = if (isSecret) SECRET_NOTE else null,
        trailingIcon = reveal,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun RevealButton(isRevealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        val icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility
        Icon(icon, contentDescription = if (isRevealed) "Hide key" else "Show key")
    }
}

// Editable model id with the provider's list (loaded once the endpoint is usable).
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun ModelPicker(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val probe = settings.config ?: settings.copy(model = PROBE_MODEL).config
    var models by remember(key1 = probe?.baseUrl, key2 = probe?.apiKey) { mutableStateOf<List<String>>(emptyList()) }
    var status by remember(key1 = probe?.baseUrl, key2 = probe?.apiKey) { mutableStateOf<String?>(null) }
    var isOpen by remember { mutableStateOf(false) }
    LaunchedEffect(key1 = probe?.baseUrl, key2 = probe?.apiKey, key3 = probe?.protocol) {
        if (probe != null) {
            status = "Loading models…"
            status =
                try {
                    models = container.ai.client.models(probe)
                    if (models.isEmpty()) "Type the model id" else "${models.size} models"
                } catch (e: IOException) {
                    e.message ?: "Couldn't load models"
                }
        }
    }
    val isMenuShown = isOpen && models.isNotEmpty()
    ExposedDropdownMenuBox(expanded = isMenuShown, onExpandedChange = { isOpen = it }) {
        DraftModelField(
            settings = settings,
            status = status,
            isOpen = isMenuShown,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = isMenuShown, onDismissRequest = { isOpen = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        isOpen = false
                        container.appScope.launch {
                            container.ai.prefs.setModel(provider = settings.provider, model = model)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun DraftModelField(settings: AiSettings, status: String?, isOpen: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    var draft by remember(settings.model) { mutableStateOf(settings.model) }
    LaunchedEffect(draft) {
        if (draft != settings.model) {
            delay(SAVE_DELAY_MS)
            container.ai.prefs.setModel(provider = settings.provider, model = draft)
        }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Model") },
        singleLine = true,
        supportingText = status?.let { { Text(it) } },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isOpen) },
        modifier = modifier.fillMaxWidth(),
    )
}

// One short request: proves key, model and network together.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun TestRequest(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    var result by remember(settings.config) { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isOk by remember { mutableStateOf(false) }
    val config = settings.config
    val run: (AiConfig) -> Unit = { endpoint ->
        isRunning = true
        scope.launch {
            val start = System.currentTimeMillis()
            result =
                try {
                    container.ai.ask(config = endpoint, prompt = TEST_PROMPT, session = "test").toList()
                    isOk = true
                    String.format(Locale.US, "Answered in %.1f s", (System.currentTimeMillis() - start) / MS_PER_S)
                } catch (e: IOException) {
                    isOk = false
                    e.message ?: "Failed"
                }
            isRunning = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        FilledTonalButton(enabled = config != null && !isRunning, onClick = { config?.let(run) }) {
            if (isRunning) CircularProgressIndicator(Modifier.size(SMALL_ICON), strokeWidth = 2.dp) else Text("Send a test")
        }
        result?.let { text ->
            if (isOk) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            val color = if (isOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val SAVE_DELAY_MS = 600L
private const val MS_PER_S = 1000.0
private const val PROBE_MODEL = "probe"
private const val TEST_PROMPT = "Reply with the single word: OK"
private val FIELD_GAP = 8.dp
private val SMALL_ICON = 18.dp
private val TIGHT_BUTTON = PaddingValues(horizontal = 4.dp)
private val SECRET_NOTE: @Composable () -> Unit = { Text("Stored encrypted on this device") }
