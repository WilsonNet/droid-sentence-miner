package io.github.wilsonnet.droidsentenceminer.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiApps
import io.github.wilsonnet.droidsentenceminer.ai.AiMode
import io.github.wilsonnet.droidsentenceminer.ai.AiProviders
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.openAiSettings
import kotlinx.coroutines.launch

/**
 * Home's AI card. AI is opt-in: off, it is one line and a switch; on, it shows whether the ✦
 * button is ready, the app/API choice, and the one step left to finish setting it up.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiHomeCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val flow = container.ai.prefs.settings
    val settings by flow.collectAsState(initial = null)
    val current = settings ?: return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(PADDING), verticalArrangement = Arrangement.spacedBy(GAP)) {
            AiHomeHeader(isEnabled = current.isEnabled) { isOn -> scope.launch { container.ai.prefs.setEnabled(isOn) } }
            if (current.isEnabled) {
                AiSetup(current)
            } else {
                Text(
                    text = "Turn on to get a grammar breakdown of any sentence you look up, from Gemini or your own API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// On: how the ✦ button answers, whether that works yet, and the one action that finishes setup.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AiSetup(settings: AiSettings) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    val app = remember(settings.appPackage) { AiApps.pick(context = context, preferred = settings.appPackage) }
    val modes = listOf(AiMode.APP to "AI app", AiMode.API to "API key")
    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
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
        val config = settings.config
        val isReady = if (settings.mode == AiMode.APP) app != null else config != null
        val status =
            when {
                settings.mode == AiMode.APP && app != null -> "Ready: ✦ in a lookup opens ${app.name} with the question typed."
                settings.mode == AiMode.APP -> "No AI app found. Install Gemini, or use an API key instead."
                config != null -> "Ready: answers appear in the popup (${config.model}, ${settings.provider.name})."
                else -> "One step left: add an API key. Gemini and OpenRouter keys are free, no credit card."
            }
        SetupStatus(isReady = isReady, text = status)
        Row(horizontalArrangement = Arrangement.spacedBy(TAG_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (!isReady && settings.mode == AiMode.API) {
                FilledTonalButton(onClick = { openGeminiKeyPage(context) }) { Text("Get a free Gemini key") }
            }
            TextButton(onClick = { openAiSettings(context) }) { Text("AI settings") }
        }
    }
}

// Icon, title with its "Optional" tag, and the opt-in switch.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AiHomeHeader(isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(GAP))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TAG_GAP)) {
            Text("AI explanations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OptionalTag()
        }
        Switch(checked = isEnabled, onCheckedChange = onToggle)
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun SetupStatus(isReady: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(TAG_GAP)) {
        Icon(
            imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(STATUS_ICON),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun OptionalTag() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = "Optional",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = TAG_PADDING_H, vertical = TAG_PADDING_V),
        )
    }
}

// The quickest free key: picks Gemini as the provider, then opens Google AI Studio's key page.
private fun openGeminiKeyPage(context: Context) {
    val container = AppContainer.from(context)
    container.appScope.launch { container.ai.prefs.setProvider(AiProviders.GEMINI) }
    val url = AiProviders.GEMINI.keyUrl ?: return
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private val PADDING = 16.dp
private val GAP = 12.dp
private val TAG_GAP = 8.dp
private val TAG_PADDING_H = 6.dp
private val TAG_PADDING_V = 2.dp
private val STATUS_ICON = 18.dp
