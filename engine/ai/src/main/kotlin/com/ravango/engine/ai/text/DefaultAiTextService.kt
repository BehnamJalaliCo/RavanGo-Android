package com.ravango.engine.ai.text

import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.AiOperation
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AiStreamEvent
import com.ravango.engine.ai.api.AiTextService
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.llm.LlmException
import com.ravango.engine.ai.llm.LlmMessage
import com.ravango.engine.ai.llm.LlmProvider
import com.ravango.engine.ai.llm.LlmRequest
import com.ravango.engine.ai.llm.LlmRole
import com.ravango.engine.ai.llm.ProviderResolver
import com.ravango.engine.ai.prompts.PromptLibrary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * [AiTextService] over the resolved [LlmProvider]. Order of checks for every run:
 * input → consent → provider configured → credits (gateway only) → stream. Credits are refunded if the request fails
 * or is cancelled before the first token, and on refusals (the partial output is discarded).
 */
@Singleton
class DefaultAiTextService @Inject constructor(
    private val resolver: ProviderResolver,
    private val entitlements: EntitlementProvider,
) : AiTextService {

    override val availability: StateFlow<AiAvailability> get() = resolver.availability

    override fun run(request: TextRequest): Flow<AiStreamEvent> = flow {
        if (request.input.isBlank()) {
            emit(AiStreamEvent.Failed(ErrorKind.INVALID_INPUT))
            return@flow
        }
        if (!resolver.hasConsent()) {
            emit(AiStreamEvent.Failed(ErrorKind.PERMISSION, AiErrors.CONSENT_REQUIRED))
            return@flow
        }
        val provider = when (val r = resolver.resolve()) {
            is ProviderResolver.Resolution.Ready -> r.provider
            is ProviderResolver.Resolution.Unavailable -> {
                emit(AiStreamEvent.Failed(r.kind, r.code))
                return@flow
            }
        }
        val spec = PromptLibrary.build(request)
        val llmRequest = LlmRequest(
            system = spec.system,
            messages = listOf(LlmMessage(LlmRole.USER, spec.user)),
            maxTokens = spec.maxTokens,
            effort = spec.effort,
            operation = spec.operation,
        )
        val charge = if (provider.usesRavanGoCredits) spec.operation else null
        if (charge != null && !entitlements.tryConsumeAiCredits(charge)) {
            emit(AiStreamEvent.Failed(ErrorKind.QUOTA, AiErrors.NO_CREDITS))
            return@flow
        }
        val text = StringBuilder()
        var refund = charge != null
        try {
            provider.stream(llmRequest).collect { delta ->
                if (text.isEmpty()) refund = false
                text.append(delta)
                emit(AiStreamEvent.Delta(delta))
            }
            val result = text.toString().trim()
            if (result.isEmpty()) {
                refund = charge != null
                emit(AiStreamEvent.Failed(ErrorKind.UNKNOWN, AiErrors.EMPTY_RESPONSE))
            } else {
                emit(AiStreamEvent.Completed(result, charge?.credits ?: 0))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            if (e.code == AiErrors.REFUSED) refund = charge != null
            RgLog.w(TAG, "text task ${request.task} failed: ${e.kind} ${e.code}")
            emit(AiStreamEvent.Failed(e.kind, e.code ?: e.message))
        } finally {
            if (refund && charge != null) withContext(NonCancellable) { entitlements.refundAiCredits(charge) }
        }
    }

    override fun parseList(text: String): List<String> = ListParser.parse(text)

    private companion object {
        const val TAG = "AiText"
    }
}

/** Charges [operation] around [block] for gateway providers, refunding when [block] throws. */
internal suspend fun <T> EntitlementProvider.charged(
    provider: LlmProvider,
    operation: AiOperation,
    units: Int = 1,
    block: suspend () -> T,
): T {
    val charge = provider.usesRavanGoCredits
    if (charge && !tryConsumeAiCredits(operation, units)) throw LlmException(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
    try {
        return block()
    } catch (e: Throwable) {
        if (charge) withContext(NonCancellable) { refundAiCredits(operation, units) }
        throw e
    }
}
