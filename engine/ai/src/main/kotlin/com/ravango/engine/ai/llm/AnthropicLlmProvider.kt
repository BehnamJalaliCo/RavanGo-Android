package com.ravango.engine.ai.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NoCredentialsException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
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
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.RawMessageStreamEvent
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Claude through the official Anthropic Java SDK. Used for two providers:
 * - [AiProviderId.RAVANGO_GATEWAY]: `baseUrl` = the RavanGo gateway (Anthropic-compatible `/v1/messages`),
 *   authenticated with the user's Supabase session token (`Authorization: Bearer <jwt>`). The gateway holds the
 *   Anthropic key, meters credits and forwards to api.anthropic.com.
 * - [AiProviderId.ANTHROPIC_DIRECT]: the user's own key against api.anthropic.com.
 *
 * Request shape for `claude-opus-5`: adaptive thinking is the model default (no `thinking` param), depth is set with
 * `output_config.effort`; no sampling params, no assistant prefill, no `budget_tokens`. Refusals (`stop_reason:
 * "refusal"`) surface as [AiErrors.REFUSED]; server-side refusal fallbacks are requested with `fallbacks: "default"`.
 */
class AnthropicLlmProvider(
    override val id: AiProviderId,
    private val clientProvider: suspend () -> AnthropicClient,
    private val model: String,
    private val io: CoroutineDispatcher,
) : LlmProvider {

    override val usesRavanGoCredits: Boolean get() = id == AiProviderId.RAVANGO_GATEWAY

    override fun stream(request: LlmRequest): Flow<String> = callbackFlow {
        val params = buildParams(request)
        val client = try {
            clientProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(mapError(e))
            awaitClose { }
            return@callbackFlow
        }
        val streamRef = AtomicReference<StreamResponse<RawMessageStreamEvent>?>(null)
        val producer = launch(io) {
            try {
                val response = client.messages().createStreaming(params)
                streamRef.set(response)
                val iterator = response.stream().iterator()
                var stopReason: String? = null
                while (iterator.hasNext()) {
                    ensureActive()
                    val event = iterator.next()
                    val text = event.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse(null)
                    if (!text.isNullOrEmpty()) send(text)
                    event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { stopReason = it.asString() }
                }
                when (stopReason) {
                    StopReason.REFUSAL.asString() -> throw LlmException(ErrorKind.INVALID_INPUT, AiErrors.REFUSED)
                    "max_tokens" -> RgLog.w(TAG, "response hit max_tokens (${request.maxTokens})")
                }
                close()
            } catch (e: CancellationException) {
                // Collector went away; the HTTP stream is closed in awaitClose.
            } catch (e: Throwable) {
                close(mapError(e))
            } finally {
                runCatching { streamRef.getAndSet(null)?.close() }
            }
        }
        awaitClose {
            // Closing the HTTP stream unblocks the reader thread immediately.
            runCatching { streamRef.getAndSet(null)?.close() }
            producer.cancel()
        }
    }.buffer(64)

    private fun buildParams(request: LlmRequest): MessageCreateParams {
        val effort = when (request.effort) {
            EffortHint.LOW -> OutputConfig.Effort.LOW
            EffortHint.MEDIUM -> OutputConfig.Effort.MEDIUM
            EffortHint.HIGH -> OutputConfig.Effort.HIGH
        }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
            .outputConfig(OutputConfig.builder().effort(effort).build())
        require(request.messages.isNotEmpty() && request.messages.last().role == LlmRole.USER) {
            "The last message must be from the user (assistant prefill is not supported)"
        }
        for (m in request.messages) {
            when (m.role) {
                LlmRole.USER -> builder.addUserMessage(m.text)
                LlmRole.ASSISTANT -> builder.addAssistantMessage(m.text)
            }
        }
        if (id == AiProviderId.RAVANGO_GATEWAY && request.operation != null) {
            builder.putAdditionalHeader(OPERATION_HEADER, request.operation.name.lowercase())
        }
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable")) {
            // Server-side refusal fallback: a declined request is retried on Anthropic's recommended model.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun mapError(e: Throwable): LlmException {
        if (e is LlmException) return e
        val gateway = id == AiProviderId.RAVANGO_GATEWAY
        RgLog.w(TAG, "request failed: ${e.javaClass.simpleName} ${(e as? AnthropicServiceException)?.statusCode() ?: ""}")
        return when (e) {
            is UnauthorizedException -> if (gateway) {
                LlmException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED, cause = e)
            } else {
                LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.INVALID_API_KEY, cause = e)
            }
            is PermissionDeniedException -> if (gateway) {
                LlmException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED, cause = e)
            } else {
                LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.INVALID_API_KEY, cause = e)
            }
            is RateLimitException -> LlmException(ErrorKind.NETWORK, AiErrors.RATE_LIMITED, retryAfter(e), cause = e)
            is NotFoundException -> LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.SERVICE_NOT_AVAILABLE, cause = e)
            is BadRequestException, is UnprocessableEntityException -> LlmException(ErrorKind.INVALID_INPUT, e.message, cause = e)
            is InternalServerException -> LlmException(ErrorKind.NETWORK, AiErrors.OVERLOADED, retryAfter(e), cause = e)
            is SseException -> mapServiceByType(e) ?: LlmException(ErrorKind.NETWORK, AiErrors.OVERLOADED, cause = e)
            is AnthropicServiceException -> mapServiceByType(e) ?: when (e.statusCode()) {
                402 -> LlmException(ErrorKind.QUOTA, AiErrors.NO_CREDITS, cause = e)
                408, 504 -> LlmException(ErrorKind.NETWORK, cause = e)
                529 -> LlmException(ErrorKind.NETWORK, AiErrors.OVERLOADED, retryAfter(e), cause = e)
                else -> LlmException(ErrorKind.UNKNOWN, e.message, cause = e)
            }
            is NoCredentialsException -> LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.API_KEY_MISSING, cause = e)
            is AnthropicIoException, is IOException -> LlmException(ErrorKind.NETWORK, cause = e)
            else -> LlmException(ErrorKind.UNKNOWN, e.message, cause = e)
        }
    }

    private fun mapServiceByType(e: AnthropicServiceException): LlmException? {
        val type = e.errorType().orElse(null) ?: return null
        return when (type.asString()) {
            ErrorType.BILLING_ERROR.asString() -> LlmException(ErrorKind.QUOTA, AiErrors.NO_CREDITS, cause = e)
            ErrorType.OVERLOADED_ERROR.asString(), ErrorType.API_ERROR.asString() ->
                LlmException(ErrorKind.NETWORK, AiErrors.OVERLOADED, retryAfter(e), cause = e)
            ErrorType.RATE_LIMIT_ERROR.asString() -> LlmException(ErrorKind.NETWORK, AiErrors.RATE_LIMITED, retryAfter(e), cause = e)
            ErrorType.TIMEOUT_ERROR.asString() -> LlmException(ErrorKind.NETWORK, cause = e)
            ErrorType.AUTHENTICATION_ERROR.asString() -> if (id == AiProviderId.RAVANGO_GATEWAY) {
                LlmException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED, cause = e)
            } else {
                LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.INVALID_API_KEY, cause = e)
            }
            else -> null
        }
    }

    private fun retryAfter(e: AnthropicServiceException): Long? =
        e.headers().values("retry-after").firstOrNull()?.trim()?.toLongOrNull()

    companion object {
        private const val TAG = "AnthropicLlm"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val OPERATION_HEADER = "X-RavanGo-Operation"
        const val ANTHROPIC_API_URL = "https://api.anthropic.com"

        /** Builds an SDK client. [bearerToken] is for the gateway (user JWT); [apiKey] for direct BYOK. */
        fun buildClient(baseUrl: String, apiKey: String? = null, bearerToken: String? = null): AnthropicClient {
            val builder = AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl.trimEnd('/'))
                .maxRetries(2)
                .timeout(Duration.ofMinutes(10))
            if (apiKey != null) builder.apiKey(apiKey)
            if (bearerToken != null) builder.authToken(bearerToken)
            return builder.build()
        }
    }
}
