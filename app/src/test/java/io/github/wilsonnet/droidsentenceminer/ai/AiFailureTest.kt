package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AiFailureTest {
    private fun kind(error: IOException) = AiFailure.from(error = error, provider = "OpenRouter").kind

    @Test
    fun `each failure gets its own kind`() {
        assertEquals(AiFailure.Kind.OFFLINE, kind(UnknownHostException("openrouter.ai")))
        assertEquals(AiFailure.Kind.TIMEOUT, kind(SocketTimeoutException("timeout")))
        assertEquals(AiFailure.Kind.KEY_REFUSED, kind(AiException(message = "The API key was refused: bad key", code = 401)))
        assertEquals(AiFailure.Kind.NO_CREDITS, kind(AiException(message = "credits: add credits", code = 402)))
        assertEquals(AiFailure.Kind.RATE_LIMITED, kind(AiException(message = "Rate limit: slow down", code = 429)))
        assertEquals(AiFailure.Kind.BUSY, kind(AiException(message = "busy: high demand", code = 503)))
        assertEquals(AiFailure.Kind.MODEL_GONE, kind(AiException(message = "x: model is no longer available", code = 404)))
        assertEquals(AiFailure.Kind.EMPTY, kind(AiException(message = "The provider sent no answer (OK)")))
        assertEquals(AiFailure.Kind.OTHER, kind(AiException(message = "HTTP 418: teapot", code = 418)))
    }

    @Test
    fun `copy names the provider, says how to recover and keeps the provider's words small`() {
        val failure =
            AiFailure.from(
                error = AiException(message = "The API key was refused: User not found.", code = 401),
                provider = "OpenRouter",
            )
        assertEquals("OpenRouter refused your API key", failure.title)
        assertEquals("It may have been deleted or mistyped. Paste a new key.", failure.advice)
        assertEquals("User not found.", failure.detail)
        assertFalse(failure.kind.isRetryable)
        assertTrue(AiFailure.Kind.BUSY.isRetryable && AiFailure.Kind.BUSY.hasSettingsFix)
        assertFalse(AiFailure.Kind.OFFLINE.hasSettingsFix)
    }
}
