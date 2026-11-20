package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    private val gemini = AiProviders.GEMINI.modelHints

    @Test
    fun `the current model stays when it is still listed`() {
        assertEquals(
            "gemini-3.8-flash",
            AiModels.pick(available = listOf("a", "gemini-3.8-flash"), current = "gemini-3.8-flash", hints = gemini),
        )
    }

    @Test
    fun `a retired model is replaced by the provider's preferred live one`() {
        val listed = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash", "gemini-flash-lite-latest", "gemini-pro-latest")
        assertEquals("gemini-flash-lite-latest", AiModels.pick(available = listed, current = "gemini-2.5-flash", hints = gemini))
        val openRouter = AiProviders.byId("openrouter").modelHints
        assertEquals("b/model:free", AiModels.pick(available = listOf("a/paid", "b/model:free"), current = "gone", hints = openRouter))
        assertEquals("x", AiModels.pick(available = listOf("x", "y"), current = "gone", hints = listOf("nothing")))
        assertNull(AiModels.pick(available = emptyList(), current = "gone", hints = gemini))
    }

    @Test
    fun `only model errors count as a gone model`() {
        val retired =
            AiException(message = "This model models/gemini-2.5-flash is no longer available to new users", code = 404)
        assertTrue(AiModels.isModelGone(retired))
        assertTrue(
            AiModels.isModelGone(AiException(message = "invalid model: foo/bar is not a valid model ID; model not found", code = 400)),
        )
        assertFalse(AiModels.isModelGone(AiException(message = "Rate limit", code = 429)))
        assertFalse(AiModels.isModelGone(AiException(message = "The API key was refused", code = 401)))
        assertFalse(AiModels.isModelGone(AiException(message = "Bad request: max_tokens too large", code = 400)))
    }

    @Test
    fun `whole json answers are read when a provider does not stream`() {
        val openAi = """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""
        assertEquals(AiDelta(text = "OK"), AiStream.parseWhole(protocol = AiProtocol.OPENAI_CHAT, body = openAi))
        val anthropic = """{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}"""
        assertEquals(AiDelta(text = "AB"), AiStream.parseWhole(protocol = AiProtocol.ANTHROPIC, body = anthropic))
        assertNull(AiStream.parseWhole(protocol = AiProtocol.OPENAI_CHAT, body = "OK"))
        assertEquals("nope", AiStream.parseWhole(protocol = AiProtocol.GEMINI, body = """{"error":{"message":"nope"}}""")?.error)
    }
}
