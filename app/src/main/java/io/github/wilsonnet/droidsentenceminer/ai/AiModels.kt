package io.github.wilsonnet.droidsentenceminer.ai

/**
 * Model ids change all the time (OpenRouter, OpenCode, Gemini retire and rename them), so nothing
 * here is a fixed list: the provider's live model list is read, and a model that stops working is
 * replaced by the best live match for the provider's [AiProvider.modelHints]. Pure: unit tested.
 */
object AiModels {
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
    private val GONE_WORDS =
        Regex(
            """model.*(not found|not exist|no longer|deprecated|unknown|invalid|not supported)""",
            RegexOption.IGNORE_CASE,
        )

    /** Hints for providers without their own: small fast chat models first. */
    val GENERIC_HINTS = listOf("flash-lite", "flash", "mini", "haiku", "small", "lite")

    /**
     * The model to use from [available]: [current] if it is there, else the first id containing a
     * [hints] entry (in hint order), else the first one; null when nothing is listed.
     */
    fun pick(available: List<String>, current: String, hints: List<String>): String? {
        val chat = available.filterNot { it.isBlank() }
        val hinted = hints.firstNotNullOfOrNull { hint -> chat.firstOrNull { it.contains(hint, ignoreCase = true) } }
        return chat.firstOrNull { it == current } ?: hinted ?: chat.firstOrNull()
    }

    /** Whether [error] says the model itself is gone (renamed, retired, not offered to this key). */
    fun isModelGone(error: AiException): Boolean {
        val isGoneCode = error.code == HTTP_NOT_FOUND || error.code == HTTP_GONE
        val isGoneMessage = (error.code == HTTP_BAD_REQUEST || isGoneCode) && GONE_WORDS.containsMatchIn(error.message.orEmpty())
        return isGoneCode || isGoneMessage
    }
}
