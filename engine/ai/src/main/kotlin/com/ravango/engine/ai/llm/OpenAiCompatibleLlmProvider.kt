package com.ravango.engine.ai.llm

import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.AiProviderId
import com.ravango.engine.ai.api.AiErrors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Any OpenAI-compatible Chat Completions endpoint (self-hosted vLLM/Ollama/LM Studio, OpenRouter, …) configured by
 * the user: `POST {baseUrl}/v1/chat/completions` with `stream: true`, parsed as server-sent events. Keeps the
 * provider layer swappable; the user's key is sent only to the base URL they entered.
 */
class OpenAiCompatibleLlmProvider(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val io: CoroutineDispatcher,
) : LlmProvider {

    override val id: AiProviderId = AiProviderId.OPENAI_COMPATIBLE
    override val usesRavanGoCredits: Boolean = false

    override fun stream(request: LlmRequest): Flow<String> = callbackFlow {
        val call = http.newCall(buildRequest(request))
        val producer = launch(io) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val retry = response.header("retry-after")?.trim()?.toLongOrNull()
                        throw httpError(response.code, retry)
                    }
                    val source = response.body?.source() ?: throw LlmException(ErrorKind.NETWORK, AiErrors.EMPTY_RESPONSE)
                    val parser = SseChatParser()
                    while (true) {
                        ensureActive()
                        val line = source.readUtf8Line() ?: break
                        when (val r = parser.onLine(line)) {
                            is SseChatParser.Result.Text -> send(r.text)
                            SseChatParser.Result.Done -> break
                            is SseChatParser.Result.Error -> throw LlmException(ErrorKind.UNKNOWN, r.message)
                            SseChatParser.Result.Refusal -> throw LlmException(ErrorKind.INVALID_INPUT, AiErrors.REFUSED)
                            null -> Unit
                        }
                    }
                }
                close()
            } catch (e: CancellationException) {
                // collector cancelled
            } catch (e: LlmException) {
                close(e)
            } catch (e: IOException) {
                close(LlmException(ErrorKind.NETWORK, cause = e, message = e.message))
            } catch (e: Throwable) {
                close(LlmException(ErrorKind.UNKNOWN, cause = e, message = e.message))
            }
        }
        awaitClose {
            call.cancel()
            producer.cancel()
        }
    }.buffer(64)

    private fun buildRequest(request: LlmRequest): Request {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.system) })
                    for (m in request.messages) {
                        add(buildJsonObject { put("role", if (m.role == LlmRole.USER) "user" else "assistant"); put("content", m.text) })
                    }
                },
            )
            putJsonObject("stream_options") { put("include_usage", false) }
        }
        val url = chatCompletionsUrl(baseUrl)
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(Json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
    }

    private fun httpError(code: Int, retryAfter: Long?): LlmException {
        RgLog.w(TAG, "HTTP $code from custom endpoint")
        return when (code) {
            401, 403 -> LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.INVALID_API_KEY)
            402 -> LlmException(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
            404 -> LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.SERVICE_NOT_AVAILABLE)
            400, 422 -> LlmException(ErrorKind.INVALID_INPUT, "HTTP $code")
            429 -> LlmException(ErrorKind.NETWORK, AiErrors.RATE_LIMITED, retryAfter)
            in 500..599 -> LlmException(ErrorKind.NETWORK, AiErrors.OVERLOADED, retryAfter)
            else -> LlmException(ErrorKind.UNKNOWN, "HTTP $code")
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatLlm"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Accepts `https://host`, `https://host/v1` or a full `/chat/completions` URL. */
        fun chatCompletionsUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return when {
                b.endsWith("/chat/completions") -> b
                b.endsWith("/v1") -> "$b/chat/completions"
                else -> "$b/v1/chat/completions"
            }
        }
    }
}

/** Incremental parser for OpenAI Chat Completions SSE lines (`data: {...}` / `data: [DONE]`). */
internal class SseChatParser {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object Done : Result
        data object Refusal : Result
        data class Error(val message: String?) : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun onLine(line: String): Result? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") return Result.Done
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        (obj["error"] as? JsonObject)?.let { err -> return Result.Error((err["message"] as? JsonPrimitive)?.contentOrNull) }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val delta = choice["delta"] as? JsonObject
        val refusal = (delta?.get("refusal") as? JsonPrimitive)?.contentOrNull
        if (!refusal.isNullOrEmpty()) return Result.Refusal
        val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
        if (!content.isNullOrEmpty()) return Result.Text(content)
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "content_filter") return Result.Refusal
        return null
    }
}
