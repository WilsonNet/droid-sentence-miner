package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiApps
import io.github.wilsonnet.droidsentenceminer.ai.AiCardPlacement
import io.github.wilsonnet.droidsentenceminer.ai.AiLookupContext
import io.github.wilsonnet.droidsentenceminer.ai.AiMode
import io.github.wilsonnet.droidsentenceminer.ai.AiPromptRenderer
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings

/**
 * The popup header's AI button. API mode: toggles the answer in place of the definition. App mode:
 * hands the default prompt to the AI app (Gemini…) in one tap. Absent while AI features are off.
 */
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
fun AiHeaderButton(state: AiPopupState, settings: AiSettings?, lookup: () -> AiLookupContext, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(settings?.appPackage) { AiApps.pick(context = context, preferred = settings?.appPackage) }
    if (settings?.isEnabled == true) {
        val handoff = app.takeIf { settings.mode == AiMode.APP }
        FilledTonalIconToggleButton(
            checked = state.isShowing,
            onCheckedChange = { isChecked ->
                if (handoff == null) {
                    state.isShowing = isChecked
                } else {
                    val prompt = AiPromptRenderer.render(template = settings.defaultPrompt.template, context = lookup())
                    AiApps.send(context = context, app = handoff, prompt = prompt)
                }
            },
            // Checked = the answer is showing: primary, so the way back to the definition is obvious.
            colors =
            IconButtonDefaults.filledTonalIconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = modifier,
        ) {
            val description = handoff?.let { "Ask ${it.name}" } ?: "Explain with AI"
            Icon(Icons.Default.AutoAwesome, contentDescription = description, modifier = Modifier.size(ICON_SIZE))
        }
    }
}

/**
 * The popup's body: the AI answer while [state] shows it, else [content] (the definition). Hosts
 * call this instead of branching themselves.
 */
@Suppress("FunctionName", "LongParameterList") // Composables are PascalCase; the popup hands over its context.
@Composable
fun AiOrDefinition(
    state: AiPopupState,
    settings: AiSettings?,
    lookup: () -> AiLookupContext,
    maxHeight: Dp,
    handleBack: Boolean,
    content: @Composable () -> Unit,
) {
    if (state.isShowing && settings != null) {
        AiAnswerPanel(state = state, settings = settings, lookup = lookup(), maxHeight = maxHeight, handleBack = handleBack)
    } else {
        content()
    }
}

// The AI answer: prompt chips, the streamed answer, where it came from. Runs the chosen prompt
// when shown; a finished answer is kept for the card.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AiAnswerPanel(state: AiPopupState, settings: AiSettings, lookup: AiLookupContext, maxHeight: Dp, handleBack: Boolean) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val prompt = settings.prompts.firstOrNull { it.id == state.promptId } ?: settings.defaultPrompt
    val config = settings.config.takeIf { settings.mode == AiMode.API }
    val app = remember(settings.appPackage) { AiApps.pick(context = context, preferred = settings.appPackage) }
    val text = AiPromptRenderer.render(template = prompt.template, context = lookup)
    if (handleBack) BackHandler { state.isShowing = false }
    LaunchedEffect(prompt.id, text, state.runs, config) {
        if (config != null) state.ask(ai = container.ai, config = config, promptId = prompt.id, prompt = text, lemma = lookup.lemma)
    }
    val answer = state.answers[prompt.id]
    Column(Modifier.heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(SMALL_GAP)) {
        PromptChips(state = state, settings = settings, selectedId = prompt.id, canRegenerate = config != null)
        Box(Modifier.fillMaxWidth().height(PROGRESS_HEIGHT)) {
            if (answer?.isBusy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        AnswerBody(
            answer = answer,
            isConfigured = config != null,
            isApiMode = settings.mode == AiMode.API,
            onRetry = { state.regenerate(prompt.id) },
            modifier = Modifier.weight(1f, fill = false),
        )
        AnswerFooter(settings = settings, isDone = answer?.phase == AiPhase.DONE, appName = app?.name) {
            if (app != null) AiApps.send(context = context, app = app, prompt = text)
        }
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AnswerBody(answer: AiAnswer?, isConfigured: Boolean, isApiMode: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        answer?.notice?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        val isEmpty = answer == null || answer.text.isEmpty()
        val isWaiting = isEmpty && answer?.phase != AiPhase.FAILED
        val failure = answer?.failure
        when {
            !isConfigured -> AiSetupHint(isApiMode = isApiMode)

            answer == null || isWaiting -> AnswerSkeleton(isThinking = answer?.phase == AiPhase.THINKING)

            // A cut-off answer keeps what arrived; the error follows it.
            else -> AiMarkdownText(markdown = answer.text, modifier = Modifier.padding(bottom = SMALL_GAP))
        }
        if (failure != null) AiErrorBlock(failure = failure, onRetry = onRetry)
    }
}

@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun PromptChips(state: AiPopupState, settings: AiSettings, selectedId: String, canRegenerate: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SMALL_GAP),
        ) {
            settings.prompts.forEach { prompt ->
                FilterChip(
                    selected = prompt.id == selectedId,
                    onClick = { state.promptId = prompt.id },
                    label = { Text(prompt.name) },
                )
            }
        }
        if (canRegenerate) {
            IconButton(onClick = { state.regenerate(selectedId) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Ask again", modifier = Modifier.size(ICON_SIZE))
            }
        }
    }
}

// Three pulsing bars where the answer will appear: the popup keeps its size while waiting.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AnswerSkeleton(isThinking: Boolean) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(verticalArrangement = Arrangement.spacedBy(SKELETON_GAP), modifier = Modifier.padding(vertical = SMALL_GAP)) {
        SKELETON_WIDTHS.forEach { width ->
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(SKELETON_BAR)
                    .alpha(pulse)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(SKELETON_RADIUS)),
            )
        }
        if (isThinking) {
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The model on the left, and whether the answer will go on the card; hand-off on the right.
@Suppress("FunctionName") // Composables are PascalCase.
@Composable
private fun AnswerFooter(settings: AiSettings, isDone: Boolean, appName: String?, onHandoff: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val card = " · on the card".takeIf { isDone && settings.placement != AiCardPlacement.OFF }.orEmpty()
        Text(
            text = settings.config?.model.orEmpty() + card,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (appName != null) {
            TextButton(onClick = onHandoff) {
                Text("Ask $appName", style = MaterialTheme.typography.labelLarge)
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = SMALL_GAP).size(SMALL_ICON),
                )
            }
        }
    }
}

private val ICON_SIZE = 20.dp
private val SMALL_ICON = 16.dp
private val SMALL_GAP = 6.dp
private val PROGRESS_HEIGHT = 2.dp
private val SKELETON_BAR = 12.dp
private val SKELETON_GAP = 10.dp
private val SKELETON_RADIUS = 6.dp
private val SKELETON_WIDTHS = listOf(0.94f, 0.82f, 0.58f)
private const val SKELETON_MIN_ALPHA = 0.35f
private const val PULSE_MS = 800
