package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.ai.AiApps
import io.github.wilsonnet.droidsentenceminer.ai.AiCardNotes
import io.github.wilsonnet.droidsentenceminer.ai.AiCardPlacement
import io.github.wilsonnet.droidsentenceminer.ai.AiMode
import io.github.wilsonnet.droidsentenceminer.ai.AiPrefs
import io.github.wilsonnet.droidsentenceminer.ai.AiPromptRenderer
import io.github.wilsonnet.droidsentenceminer.ai.AiProtocol
import io.github.wilsonnet.droidsentenceminer.ai.AiProviders
import io.github.wilsonnet.droidsentenceminer.ai.AiSettings
import io.github.wilsonnet.droidsentenceminer.ui.components.AiPhase
import io.github.wilsonnet.droidsentenceminer.ui.components.AiPopupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * `/ai` (settings), `/ai_ask` (one prompt, streamed and timed), `/ai_models`, `/ai_send` (hand a
 * prompt to the AI app). See the dsm-debug-api skill.
 */
object AiDebug {
    /** Paths answered by [handle]. */
    val PATHS = setOf("/ai", "/ai_ask", "/ai_models", "/ai_send")

    private const val KEY_TAIL = 4
    private const val OK = "ok"
    private const val ERROR = "error"
    private const val MODEL = "model"
    private const val PROMPT = "prompt"
    private const val WORD = "word"
    private const val SENTENCE = "sentence"

    /** Answers one request for a path in [PATHS]. */
    fun handle(app: Application, path: String, params: Map<String, String>): JSONObject {
        val response =
            when (path) {
                "/ai" -> settings(app, params)
                "/ai_models" -> models(app)
                "/ai_send" -> send(app, params)
                else -> if (params["heal"] == "1") askLikePopup(app, params) else ask(app, params)
            }
        return response
    }

    // `/ai?enabled=&mode=app|api&provider=&key=&model=&url=&protocol=&placement=&app=`:
    // changes what is given, returns the settings (key masked).
    private fun settings(app: Application, params: Map<String, String>): JSONObject {
        val prefs = AppContainer.from(app).ai.prefs
        return runBlocking {
            applyGlobal(prefs = prefs, params = params)
            applyProvider(prefs = prefs, params = params)
            describe(app = app, settings = prefs.current())
        }
    }

    private suspend fun applyGlobal(prefs: AiPrefs, params: Map<String, String>) {
        params["enabled"]?.let { prefs.setEnabled(it == "1") }
        AiMode.entries.firstOrNull { it.id == params["mode"] }?.let { prefs.setMode(it) }
        AiCardPlacement.entries.firstOrNull { it.id == params["placement"] }?.let { prefs.setPlacement(it) }
        AiProtocol.entries.firstOrNull { it.name == params["protocol"] }?.let { prefs.setCustomProtocol(it) }
        params["app"]?.let { prefs.setAppPackage(it) }
        params["provider"]?.let { prefs.setProvider(AiProviders.byId(it)) }
    }

    private suspend fun applyProvider(prefs: AiPrefs, params: Map<String, String>) {
        val provider = prefs.current().provider
        params["key"]?.let { prefs.setApiKey(provider = provider, key = it) }
        params[MODEL]?.let { prefs.setModel(provider = provider, model = it) }
        params["url"]?.let { prefs.setBaseUrl(provider = provider, url = it) }
    }

    private fun describe(app: Application, settings: AiSettings): JSONObject {
        val prompts = settings.prompts.map { prompt -> prompt.id + if (prompt.isModified) " (edited)" else "" }
        return JSONObject()
            .put(OK, true)
            .put("enabled", settings.isEnabled)
            .put("mode", settings.mode.id)
            .put("provider", settings.provider.id)
            .put("key", if (settings.apiKey.isBlank()) "" else "…" + settings.apiKey.takeLast(KEY_TAIL))
            .put(MODEL, settings.model)
            .put("baseUrl", settings.baseUrl)
            .put("protocol", settings.protocol.name)
            .put("placement", settings.placement.id)
            .put("ready", settings.config != null)
            .put("prompts", JSONArray(prompts))
            .put("apps", JSONArray(AiApps.installed(app).map { it.packageName }))
    }

    // `/ai_ask?word=&sentence=&prompt=`: runs the prompt like the popup (and keeps the answer for the card).
    private fun ask(app: Application, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            val settings = container.ai.prefs.current()
            val config = settings.config
            val word = params[WORD].orEmpty()
            val sentence = params[SENTENCE].orEmpty()
            val details = container.lookup.lookup(word = word, sentence = sentence)
            val lookup = container.ai.lookupContext(word = word, sentence = sentence, details = details)
            val prompt = settings.prompts.firstOrNull { it.id == params[PROMPT] } ?: settings.defaultPrompt
            val text = AiPromptRenderer.render(template = prompt.template, context = lookup)
            val start = System.currentTimeMillis()
            var firstTextMs = -1L
            val answer = StringBuilder()
            var chunks = 0
            val error =
                try {
                    checkNotNull(config) { "AI not configured" }
                    val deltas = container.ai.ask(config = config, prompt = text, session = "debug")
                    val timed =
                        deltas.onEach { delta ->
                            if (firstTextMs < 0 && delta.text.isNotEmpty()) firstTextMs = System.currentTimeMillis() - start
                            answer.append(delta.text)
                            chunks++
                        }
                    timed.toList()
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IOException) {
                    e.message
                } catch (e: IllegalStateException) {
                    e.message
                }
            AiCardNotes.remember(expression = lookup.lemma, answer = answer.toString())
            JSONObject()
                .put(OK, error == null)
                .put(ERROR, error ?: JSONObject.NULL)
                .put(MODEL, config?.model ?: JSONObject.NULL)
                .put(PROMPT, text)
                .put("chunks", chunks)
                .put("totalMs", System.currentTimeMillis() - start)
                .put("firstTextMs", firstTextMs)
                .put("answer", answer.toString())
        }
    }

    // `/ai_ask?heal=1&word=&sentence=`: the popup's own path (AiPopupState): a retired model is
    // replaced by a live one and asked again; `notice` says so.
    private fun askLikePopup(app: Application, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            val settings = container.ai.prefs.current()
            val config = settings.config
            val response = JSONObject().put(OK, false).put(ERROR, "AI not configured")
            if (config != null) {
                val word = params[WORD].orEmpty()
                val sentence = params[SENTENCE].orEmpty()
                val details = container.lookup.lookup(word = word, sentence = sentence)
                val lookup = container.ai.lookupContext(word = word, sentence = sentence, details = details)
                val prompt = settings.defaultPrompt
                val state = AiPopupState()
                val text = AiPromptRenderer.render(template = prompt.template, context = lookup)
                state.ask(ai = container.ai, config = config, promptId = prompt.id, prompt = text, lemma = lookup.lemma)
                val answer = state.answers[prompt.id]
                val failure = answer?.failure?.run { "$kind: $title. $advice ($detail)" }
                val now = container.ai.prefs.current()
                val model = now.model
                response
                    .put(OK, answer?.phase == AiPhase.DONE)
                    .put(ERROR, failure ?: JSONObject.NULL)
                    .put("notice", answer?.notice ?: JSONObject.NULL)
                    .put(MODEL, model)
                    .put("answer", answer?.text.orEmpty())
            }
            response
        }
    }

    // `/ai_models`: the configured provider's model list.
    private fun models(app: Application): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            val settings = container.ai.prefs.current()
            val config = settings.config ?: settings.copy(model = "probe").config
            val response = JSONObject()
            try {
                val models = config?.let { container.ai.client.models(it) }.orEmpty()
                response.put(OK, true).put("models", JSONArray(models))
            } catch (e: IOException) {
                response.put(OK, false).put(ERROR, e.message)
            }
        }
    }

    // `/ai_send?word=&sentence=`: hands the default prompt to the AI app, like the popup button in app mode.
    private fun send(app: Application, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            val settings = container.ai.prefs.current()
            val target = AiApps.pick(context = app, preferred = settings.appPackage)
            val word = params[WORD].orEmpty()
            val sentence = params[SENTENCE].orEmpty()
            val details = container.lookup.lookup(word = word, sentence = sentence)
            val lookup = container.ai.lookupContext(word = word, sentence = sentence, details = details)
            val text = AiPromptRenderer.render(template = settings.defaultPrompt.template, context = lookup)
            var isSent = false
            if (target != null) {
                isSent = withContext(Dispatchers.Main) { AiApps.send(context = app, app = target, prompt = text) }
            }
            JSONObject()
                .put(OK, isSent)
                .put("app", target?.packageName ?: JSONObject.NULL)
                .put(PROMPT, text)
        }
    }
}
