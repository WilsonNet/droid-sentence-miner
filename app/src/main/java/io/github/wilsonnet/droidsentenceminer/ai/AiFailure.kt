package io.github.wilsonnet.droidsentenceminer.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failed AI request as the user should read it: what failed ([title]), why and what to do
 * ([advice]), which recovery buttons make sense, and the provider's own words ([detail], small).
 * Pure: unit tested.
 *
 * @property kind What went wrong.
 * @property title One short line.
 * @property advice Why, when known, and how to recover.
 * @property detail The provider's message, shown small for reference; empty when it adds nothing.
 */
data class AiFailure(val kind: Kind, val title: String, val advice: String, val detail: String) {
    /**
     * What went wrong, which decides the recovery buttons.
     *
     * @property isRetryable *Try again* can help.
     * @property hasSettingsFix Changing the key, model or provider can help (*Open AI settings*).
     */
    enum class Kind(val isRetryable: Boolean, val hasSettingsFix: Boolean) {
        /** No network. */
        OFFLINE(isRetryable = true, hasSettingsFix = false),

        /** No answer in time. */
        TIMEOUT(isRetryable = true, hasSettingsFix = false),

        /** 401/403: the key is wrong, revoked or deleted. */
        KEY_REFUSED(isRetryable = false, hasSettingsFix = true),

        /** 402: the model needs credits or a plan. */
        NO_CREDITS(isRetryable = false, hasSettingsFix = true),

        /** 429: free-tier or rate limit reached. */
        RATE_LIMITED(isRetryable = true, hasSettingsFix = true),

        /** 5xx: the provider is overloaded or down. */
        BUSY(isRetryable = true, hasSettingsFix = true),

        /** The model was retired or renamed and no replacement was found. */
        MODEL_GONE(isRetryable = false, hasSettingsFix = true),

        /** The provider answered with no text. */
        EMPTY(isRetryable = true, hasSettingsFix = true),

        /** Anything else. */
        OTHER(isRetryable = true, hasSettingsFix = true),
    }

    /** User copy per failure. */
    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYMENT = 402
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY = 429
        private val HTTP_SERVER_ERRORS = 500..599
        private const val MAX_DETAIL = 160

        /** [error] from [provider] (its display name) as user copy. */
        fun from(error: IOException, provider: String): AiFailure {
            val aiError = error as? AiException
            val code = aiError?.code ?: 0
            val message = aiError?.message.orEmpty()
            val detail = message.substringAfter(": ").take(MAX_DETAIL)
            val kind = kindOf(error = error, code = code)
            return AiFailure(kind = kind, title = title(kind = kind, provider = provider), advice = advice(kind), detail = detail)
        }

        private fun kindOf(error: IOException, code: Int): Kind {
            val isGone = error is AiException && AiModels.isModelGone(error)
            val isEmpty = error is AiException && code == 0 && error.message.orEmpty().startsWith("The provider sent no answer")
            val kind =
                when {
                    error is UnknownHostException || error is SSLException -> Kind.OFFLINE
                    error is SocketTimeoutException -> Kind.TIMEOUT
                    isGone -> Kind.MODEL_GONE
                    isEmpty -> Kind.EMPTY
                    else -> httpKind(code)
                }
            return kind
        }

        private fun httpKind(code: Int): Kind {
            val kind =
                when (code) {
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> Kind.KEY_REFUSED
                    HTTP_PAYMENT -> Kind.NO_CREDITS
                    HTTP_TOO_MANY -> Kind.RATE_LIMITED
                    in HTTP_SERVER_ERRORS -> Kind.BUSY
                    else -> Kind.OTHER
                }
            return kind
        }

        private fun title(kind: Kind, provider: String): String {
            val title =
                when (kind) {
                    Kind.OFFLINE -> "No internet connection"
                    Kind.TIMEOUT -> "$provider took too long to answer"
                    Kind.KEY_REFUSED -> "$provider refused your API key"
                    Kind.NO_CREDITS -> "This model needs credits on $provider"
                    Kind.RATE_LIMITED -> "$provider's limit is used up for now"
                    Kind.BUSY -> "$provider is busy right now"
                    Kind.MODEL_GONE -> "This model isn't available anymore"
                    Kind.EMPTY -> "$provider sent an empty answer"
                    Kind.OTHER -> "$provider couldn't answer"
                }
            return title
        }

        private fun advice(kind: Kind): String {
            val advice =
                when (kind) {
                    Kind.OFFLINE -> "Check Wi-Fi or mobile data, then try again."
                    Kind.TIMEOUT -> "The model may be overloaded. Try again, or pick a faster model."
                    Kind.KEY_REFUSED -> "It may have been deleted or mistyped. Paste a new key."
                    Kind.NO_CREDITS -> "Pick a free model, or add credits to your account."
                    Kind.RATE_LIMITED -> "Free tiers allow a few requests a minute. Wait a moment, or pick another model."
                    Kind.BUSY -> "This usually passes in a minute. Try again, or pick another model."
                    Kind.MODEL_GONE -> "Pick another model from the list."
                    Kind.EMPTY -> "Try again. If it keeps happening, pick another model."
                    Kind.OTHER -> "Try again, or check the provider and model."
                }
            return advice
        }
    }
}
