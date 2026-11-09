package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.anki.CardFields
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.update.WhatsNew
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug endpoints routed before [DebugApi]'s big `when` (adding a branch there resurfaces its
 * baselined lint findings, see the dsm-lint skill). Add new endpoints here.
 */
object DebugExtras {
    /** Paths answered by [handle]. */
    val PATHS = setOf("/whats_new", "/card_contents")

    /** Answers one request for a path in [PATHS]. */
    fun handle(app: Application, path: String, params: Map<String, String>): JSONObject =
        if (path == "/whats_new") whatsNew(app, params) else cardContents(app)

    // `/whats_new`: seen version and what the dialog would show; `?reset=1` looks like an update
    // (the next launch shows it), `?show=1` opens it now.
    private fun whatsNew(app: Application, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            if (params["reset"] == "1") container.prefs.setWhatsNewSeen("0.0.0+0")
            val sections = WhatsNew.load(app)
            if (params["show"] == "1") container.whatsNew.value = sections
            JSONObject()
                .put("ok", true)
                .put("version", WhatsNew.versionKey)
                .put("seen", container.prefs.whatsNewSeen.first() ?: JSONObject.NULL)
                .put("pending", JSONArray(WhatsNew.pending(app, container.prefs).map { it.version }))
                .put("sections", JSONArray(sections.map { it.version }))
        }
    }

    // `/card_contents`: the study language's contents and the ones the current note type's field
    // mapping receives (a field set to Nothing leaves its content out).
    private fun cardContents(app: Application): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            JSONObject()
                .put("ok", true)
                .put("language", Languages.current.code)
                .put("all", JSONArray(CardFields.current))
                .put("enabled", JSONArray(container.miner.cardFields()))
        }
    }
}
