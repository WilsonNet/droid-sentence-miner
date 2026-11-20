package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.ai.AiFailure
import io.github.wilsonnet.droidsentenceminer.openAiSettings

/**
 * A failed AI answer: what failed, why and what to do, the provider's own words small, and only
 * the recovery buttons that make sense for that failure (*Try again*, *Open AI settings*). The
 * popup's footer keeps *Ask Gemini* as the way out that always works. [isInSettings] drops the
 * settings link where the user already is.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiErrorBlock(failure: AiFailure, onRetry: () -> Unit, modifier: Modifier = Modifier, isInSettings: Boolean = false) {
    val context = LocalContext.current
    NoticeCard(
        icon = iconFor(failure.kind),
        title = failure.title,
        advice = failure.advice,
        detail = failure.detail,
        modifier = modifier,
    ) {
        if (failure.kind.isRetryable) FilledTonalButton(onClick = onRetry) { Text("Try again") }
        if (failure.kind.hasSettingsFix && !isInSettings) {
            TextButton(onClick = {
                openAiSettings(context)
            }) { Text("Open AI settings") }
        }
    }
}

/** Shown instead of an answer while the API isn't set up (or no AI app exists for app mode). */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiSetupHint(isApiMode: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val title = if (isApiMode) "Add an API key to see answers here" else "No AI app found"
    val advice =
        if (isApiMode) {
            "Google Gemini and OpenRouter give free keys without a credit card."
        } else {
            "Install Gemini, or switch to an API key for answers in this popup."
        }
    NoticeCard(icon = Icons.Default.Key, title = title, advice = advice, detail = "", modifier = modifier) {
        FilledTonalButton(onClick = { openAiSettings(context) }) { Text("Open AI settings") }
    }
}

// Icon, title, advice, small provider detail, then the actions. Announced to screen readers.
@Suppress("FunctionName", "LongParameterList") // Composables are PascalCase; one card, its parts.
@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String,
    advice: String,
    detail: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(PADDING), verticalArrangement = Arrangement.spacedBy(GAP)) {
            NoticeText(icon = icon, title = title, advice = advice, detail = detail)
            Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun NoticeText(icon: ImageVector, title: String, advice: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.Top) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(ICON))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TEXT_GAP)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = DETAIL_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun iconFor(kind: AiFailure.Kind): ImageVector {
    val icon =
        when (kind) {
            AiFailure.Kind.OFFLINE -> Icons.Default.CloudOff
            AiFailure.Kind.TIMEOUT -> Icons.Default.HourglassEmpty
            AiFailure.Kind.KEY_REFUSED -> Icons.Default.Key
            AiFailure.Kind.NO_CREDITS -> Icons.Default.CreditCard
            AiFailure.Kind.RATE_LIMITED, AiFailure.Kind.BUSY -> Icons.Default.Timer
            AiFailure.Kind.MODEL_GONE -> Icons.Default.SwapHoriz
            AiFailure.Kind.EMPTY, AiFailure.Kind.OTHER -> Icons.Default.ErrorOutline
        }
    return icon
}

private const val DETAIL_LINES = 2
private val PADDING = 12.dp
private val GAP = 10.dp
private val TEXT_GAP = 2.dp
private val ICON = 20.dp
