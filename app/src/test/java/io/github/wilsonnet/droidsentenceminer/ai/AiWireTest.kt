package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWireTest {
    private val openCode = AiProviders.ALL.first { it.id == "opencode-go" }
    private val anthropic = AiProviders.ALL.first { it.protocol == AiProtocol.ANTHROPIC }

    private fun config(provider: AiProvider, model: String = "m") =
        AiConfig(provider = provider, baseUrl = provider.baseUrl, protocol = provider.protocol, apiKey = "k", model = model)

    @Test
    fun `urls follow each protocol`() {
        assertEquals("https://opencode.ai/inference/go/openai/v1/chat/completions", AiWire.streamUrl(config(openCode)))
        assertEquals("https://api.anthropic.com/v1/messages", AiWire.streamUrl(config(anthropic)))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:streamGenerateContent?alt=sse",
            AiWire.streamUrl(config(provider = AiProviders.GEMINI, model = "gemini-x")),
        )
        assertEquals("https://opencode.ai/zen/go/v1/models", AiWire.modelsUrl(config(openCode)))
    }

    @Test
    fun `headers carry the key the way each api wants it`() {
        val openAi = AiWire.headers(config = config(openCode), session = "abc")
        assertEquals("Bearer k", openAi["Authorization"])
        assertEquals("ses_abc", openAi["x-opencode-session"])
        assertEquals(AiWire.USER_AGENT, openAi["User-Agent"])
        assertEquals("k", AiWire.headers(config = config(anthropic), session = "s")["x-api-key"])
        assertEquals("k", AiWire.headers(config = config(AiProviders.GEMINI), session = "s")["x-goog-api-key"])
        assertNull(AiWire.headers(config = config(AiProviders.GEMINI), session = "s")["x-opencode-session"])
    }

    @Test
    fun `bodies stream one user message`() {
        val openAi = AiWire.requestBody(config = config(openCode), prompt = """hi "there"""")
        assertTrue(openAi.contains(""""stream":true""") && openAi.contains(""""content":"hi \"there\""""))
        assertTrue(AiWire.requestBody(config = config(anthropic), prompt = "x").contains(""""max_tokens""""))
        assertTrue(openAi.contains(""""thinking":{"type":"disabled"}"""))
        val gemini = AiWire.requestBody(config = config(AiProviders.GEMINI), prompt = "x")
        assertFalse(gemini.contains("thinking"))
        assertTrue(gemini.contains(""""parts":[{"text":"x"}]"""))
    }

    @Test
    fun `openai deltas, reasoning and done`() {
        val text = """{"choices":[{"delta":{"content":"食べ"}}]}"""
        val thinking = """{"choices":[{"delta":{"content":"","reasoning_content":"hmm"}}]}"""
        assertEquals(AiDelta(text = "食べ"), AiStream.parseEvent(protocol = AiProtocol.OPENAI_CHAT, data = text))
        assertEquals(AiDelta(isThinking = true), AiStream.parseEvent(protocol = AiProtocol.OPENAI_CHAT, data = thinking))
        assertNull(AiStream.parseEvent(protocol = AiProtocol.OPENAI_CHAT, data = "[DONE]"))
        assertNull(AiStream.parseEvent(protocol = AiProtocol.OPENAI_CHAT, data = """{"choices":[],"usage":{}}"""))
    }

    @Test
    fun `anthropic and gemini deltas`() {
        val anthropicText = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}"""
        assertEquals(AiDelta(text = "OK"), AiStream.parseEvent(protocol = AiProtocol.ANTHROPIC, data = anthropicText))
        assertNull(AiStream.parseEvent(protocol = AiProtocol.ANTHROPIC, data = """{"type":"ping"}"""))
        val gemini = """{"candidates":[{"content":{"parts":[{"text":"A"},{"text":"B"}],"role":"model"}}]}"""
        assertEquals(AiDelta(text = "AB"), AiStream.parseEvent(protocol = AiProtocol.GEMINI, data = gemini))
        val thought = """{"candidates":[{"content":{"parts":[{"text":"plan","thought":true}]}}]}"""
        assertEquals(AiDelta(isThinking = true), AiStream.parseEvent(protocol = AiProtocol.GEMINI, data = thought))
    }

    @Test
    fun `errors inside the stream and in error bodies`() {
        val error = """{"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session"}}"""
        assertEquals("Request is missing x-opencode-session", AiStream.parseEvent(protocol = AiProtocol.OPENAI_CHAT, data = error)?.error)
        assertEquals("bad key", AiStream.errorBody("""{"error":{"message":"bad key","code":401}}"""))
        assertNull(AiStream.errorBody("<html>"))
    }

    @Test
    fun `model lists keep chat models only`() {
        val openAi = """{"data":[{"id":"b","type":"chat"},{"id":"a"},{"id":"embed","type":"embedding"}]}"""
        assertEquals(listOf("a", "b"), AiStream.parseModels(protocol = AiProtocol.OPENAI_CHAT, body = openAi))
        val gemini =
            """{"models":[{"name":"models/gemini-x","supportedGenerationMethods":["generateContent"]},""" +
                """{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}]}"""
        assertEquals(listOf("gemini-x"), AiStream.parseModels(protocol = AiProtocol.GEMINI, body = gemini))
    }

    @Test
    fun `gemini lists only text models and free models come first`() {
        val gemini =
            listOf(
                """{"models":[""",
                """{"name":"models/gemini-flash-lite-latest","supportedGenerationMethods":["generateContent"]},""",
                """{"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]},""",
                """{"name":"models/gemini-3-pro-image","supportedGenerationMethods":["generateContent"]}]}""",
            ).joinToString("")
        assertEquals(listOf("gemini-flash-lite-latest"), AiStream.parseModels(protocol = AiProtocol.GEMINI, body = gemini))
        val openRouter = """{"data":[{"id":"a/paid"},{"id":"z/model:free"},{"id":"b/other:free"}]}"""
        assertEquals(
            listOf("b/other:free", "z/model:free", "a/paid"),
            AiStream.parseModels(protocol = AiProtocol.OPENAI_CHAT, body = openRouter),
        )
    }

    @Test
    fun `presets with a free tier are ready once a key is pasted`() {
        assertEquals("gemini-flash-lite-latest", AiProviders.GEMINI.defaultModel)
        assertEquals("openrouter/free", AiProviders.byId("openrouter").defaultModel)
    }
}
