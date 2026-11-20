package io.github.wilsonnet.droidsentenceminer.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * An AI app that can take a prompt through Android's share intent.
 *
 * @property packageName The app's package.
 * @property name Its name as the launcher shows it.
 * @property isPrefilled The app puts shared text into its prompt box (checked on a device): no clipboard copy needed.
 */
data class AiApp(val packageName: String, val name: String, val isPrefilled: Boolean)

/**
 * Hands prompts to installed AI apps (`ACTION_SEND` text/plain to the app's package). Gemini
 * puts the text in its prompt box, ready to send; apps not known to do that also get the prompt on
 * the clipboard.
 */
object AiApps {
    /** Gemini's package: the AI app most Android phones already have. */
    const val GEMINI = "com.google.android.apps.bard"

    // Known AI apps in preference order; true = verified to prefill the prompt box.
    private val KNOWN =
        listOf(
            GEMINI to true,
            "com.anthropic.claude" to false,
            "com.openai.chatgpt" to false,
            "ai.perplexity.app.android" to false,
            "com.microsoft.copilot" to false,
            "com.deepseek.chat" to false,
            "ai.x.grok" to false,
            "ai.mistral.chat" to false,
        )

    /** The known AI apps installed here that accept shared text, Gemini first. */
    fun installed(context: Context): List<AiApp> {
        val pm = context.packageManager
        val apps =
            KNOWN.mapNotNull { (pkg, isPrefilled) ->
                val intent = shareIntent(packageName = pkg, text = "")
                val isAccepted = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
                if (isAccepted) AiApp(packageName = pkg, name = label(pm = pm, packageName = pkg), isPrefilled = isPrefilled) else null
            }
        return apps
    }

    /** [preferred] if installed, else the first installed AI app; null when there is none. */
    fun pick(context: Context, preferred: String?): AiApp? {
        val apps = installed(context)
        return apps.firstOrNull { it.packageName == preferred } ?: apps.firstOrNull()
    }

    /**
     * Opens [app] with [prompt]. Works from an overlay service (new task). Returns false when the
     * app could not be started.
     */
    fun send(context: Context, app: AiApp, prompt: String): Boolean {
        if (!app.isPrefilled) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("AI prompt", prompt))
        }
        val isStarted =
            runCatching {
                context.startActivity(shareIntent(packageName = app.packageName, text = prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        val message =
            when {
                !isStarted -> "Couldn't open ${app.name}"
                app.isPrefilled -> "Opening ${app.name}: tap send"
                else -> "Opening ${app.name}: the prompt is also on the clipboard"
            }
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        return isStarted
    }

    private fun shareIntent(packageName: String, text: String): Intent =
        Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(packageName).putExtra(Intent.EXTRA_TEXT, text)

    private fun label(pm: PackageManager, packageName: String): String {
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
        return label.getOrDefault(packageName)
    }
}
