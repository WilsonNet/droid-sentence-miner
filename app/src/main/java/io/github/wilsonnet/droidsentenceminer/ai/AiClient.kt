package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Talks to an [AiConfig]'s API: streamed answers and model lists, for every [AiProtocol] ([AiWire], [AiStream]). */
class AiClient(private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** The answer to [prompt] as it streams; a failed request throws [AiException]. */
    fun stream(config: AiConfig, prompt: String, session: String): Flow<AiDelta> {
        val body = AiWire.requestBody(config = config, prompt = prompt).toRequestBody(JSON)
        val request =
            request(config = config, url = AiWire.streamUrl(config), session = session)
                .header(name = "Accept", value = "text/event-stream")
                .post(body)
                .build()
        val answer =
            flow {
                client.newCall(request).execute().use { response ->
                    checkOk(response)
                    val body = response.body ?: throw AiException("Empty response")
                    body.source().use { emitEvents(source = it, protocol = config.protocol) }
                }
            }
        return answer.flowOn(io)
    }

    /** The provider's model ids (chat models where the API tells them apart). */
    suspend fun models(config: AiConfig): List<String> {
        val request = request(config = config, url = AiWire.modelsUrl(config), session = "models").build()
        val models =
            withContext(io) {
                client.newCall(request).execute().use { response ->
                    checkOk(response)
                    AiStream.parseModels(protocol = config.protocol, body = response.body?.string().orEmpty())
                }
            }
        return models
    }

    private fun request(config: AiConfig, url: String, session: String): Request.Builder {
        val builder = Request.Builder().url(url)
        AiWire.headers(config = config, session = session).forEach { (name, value) ->
            builder.header(name = name, value = value)
        }
        return builder
    }

    // Server-sent events: every `data:` line is one event; an error sent mid-stream ends it.
    private suspend fun FlowCollector<AiDelta>.emitEvents(source: BufferedSource, protocol: AiProtocol) {
        var line = source.readUtf8Line()
        while (line != null) {
            val data = line.takeIf { it.startsWith(DATA) }?.run { removePrefix(DATA).trim() }
            val delta = data?.let { AiStream.parseEvent(protocol = protocol, data = it) }
            delta?.error?.let { throw AiException(it) }
            if (delta != null) emit(delta)
            line = source.readUtf8Line()
        }
    }

    private fun checkOk(response: Response) {
        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val message = AiStream.errorBody(body) ?: body.take(MAX_ERROR_CHARS).ifBlank { "HTTP ${response.code}" }
            throw AiException(httpMessage(code = response.code, detail = message))
        }
    }

    private fun httpMessage(code: Int, detail: String): String {
        val hint =
            when (code) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "The API key was refused"
                HTTP_NOT_FOUND -> "Model or endpoint not found"
                HTTP_TOO_MANY -> "Rate limit or quota reached"
                else -> "HTTP $code"
            }
        return "$hint: $detail"
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 90L
        const val MAX_ERROR_CHARS = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
        const val DATA = "data:"
        val JSON = "application/json".toMediaType()
    }
}

/** A failed AI request, with a message fit for the popup. */
class AiException(message: String) : IOException(message)
