package io.github.wilsonnet.droidsentenceminer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiCardNotes
import io.github.wilsonnet.droidsentenceminer.ai.AiConfig
import io.github.wilsonnet.droidsentenceminer.ai.AiLookupContext
import io.github.wilsonnet.droidsentenceminer.ai.AiService
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/** Where one prompt's answer is. */
enum class AiPhase {
    /** Request sent, nothing back yet. */
    WAITING,

    /** The model is reasoning before it answers. */
    THINKING,

    /** Answer text is arriving. */
    STREAMING,

    /** Complete. */
    DONE,

    /** The request failed ([AiAnswer.error]). */
    FAILED,
}

/**
 * One prompt's answer in the popup.
 *
 * @property text Markdown received so far.
 * @property phase Progress.
 * @property error Why it failed, fit to show.
 */
data class AiAnswer(val text: String = "", val phase: AiPhase = AiPhase.WAITING, val error: String? = null) {
    /** A request is still running. */
    val isBusy: Boolean get() = phase == AiPhase.WAITING || phase == AiPhase.THINKING || phase == AiPhase.STREAMING
}

/**
 * The popup's AI state for one looked-up word: whether the answer replaces the definition, the
 * prompt chosen, and every prompt's answer so far (switching chips back is instant).
 */
@Stable
class AiPopupState {
    /** The AI answer is shown instead of the definition. */
    var isShowing by mutableStateOf(false)

    /** The chosen prompt; null: the settings' default. */
    var promptId by mutableStateOf<String?>(null)

    /** Bumped by [regenerate]: restarts the request. */
    var runs by mutableIntStateOf(0)

    /** Answers by prompt id. */
    val answers = mutableStateMapOf<String, AiAnswer>()

    /** Routes one popup's requests together (OpenCode's session header). */
    val session: String = UUID.randomUUID().toString()

    /** Drops [promptId]'s answer and asks again. */
    fun regenerate(promptId: String) {
        answers.remove(promptId)
        runs++
    }

    /**
     * Streams [prompt]'s answer into [answers] under [promptId], unless a finished one is there (an
     * answer cut off by closing the panel is asked again). A finished answer goes to [AiCardNotes].
     */
    suspend fun ask(ai: AiService, config: AiConfig, promptId: String, prompt: String, lemma: String) {
        if (answers[promptId]?.phase == AiPhase.DONE) return
        answers[promptId] = AiAnswer()
        AiCardNotes.forget(lemma)
        val answer = StringBuilder()
        try {
            ai.ask(config = config, prompt = prompt, session = session).collect { delta ->
                answer.append(delta.text)
                val phase = if (answer.isEmpty()) AiPhase.THINKING else AiPhase.STREAMING
                answers[promptId] = AiAnswer(text = answer.toString(), phase = phase)
            }
            answers[promptId] = AiAnswer(text = answer.toString(), phase = AiPhase.DONE)
            AiCardNotes.remember(expression = lemma, answer = answer.toString())
        } catch (e: IOException) {
            answers[promptId] = AiAnswer(text = answer.toString(), phase = AiPhase.FAILED, error = failureMessage(e))
        }
    }

    private fun failureMessage(error: IOException): String {
        val message =
            when (error) {
                is UnknownHostException -> "No internet connection."
                is SocketTimeoutException -> "The AI took too long to answer."
                else -> error.message ?: "The AI request failed."
            }
        return message
    }
}

/** The AI state of the popup showing [word] in [sentence]; reset when the word changes. */
@Composable
fun rememberAiPopupState(word: String, sentence: String): AiPopupState {
    val state = remember(key1 = word, key2 = sentence) { AiPopupState() }
    return state
}

/** The AI settings, or null until read. */
@Composable
fun rememberAiSettings(): AiSettings? {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val settings by container.ai.prefs.settings
        .collectAsState(initial = null)
    return settings
}

/** Builds the prompt context of the popup's current lookup on demand (the definition is read only when asked). */
@Composable
fun aiLookupContext(word: String, sentence: String, details: TermLookup.LookupDetails?): () -> AiLookupContext {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    return { container.ai.lookupContext(word = word, sentence = sentence, details = details) }
}
