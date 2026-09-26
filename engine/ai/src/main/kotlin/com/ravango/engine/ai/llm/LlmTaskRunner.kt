package com.ravango.engine.ai.llm

import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.model.AiOperation
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.text.charged
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** One-shot LLM calls for engine features (highlights, shorts, titles): consent → provider → credits → call. */
@Singleton
class LlmTaskRunner @Inject constructor(
    private val resolver: ProviderResolver,
    private val entitlements: EntitlementProvider,
) {
    /** Whether an LLM pass can run now without asking the user anything. */
    suspend fun isReady(): Boolean = resolver.hasConsent() && resolver.resolve() is ProviderResolver.Resolution.Ready

    suspend fun complete(request: LlmRequest, operation: AiOperation): Outcome<String> {
        if (!resolver.hasConsent()) return Outcome.Failure(ErrorKind.PERMISSION, AiErrors.CONSENT_REQUIRED)
        val provider = when (val r = resolver.resolve()) {
            is ProviderResolver.Resolution.Ready -> r.provider
            is ProviderResolver.Resolution.Unavailable -> return Outcome.Failure(r.kind, r.code)
        }
        return try {
            val text = entitlements.charged(provider, operation) {
                provider.complete(request).trim().ifEmpty { throw LlmException(ErrorKind.UNKNOWN, AiErrors.EMPTY_RESPONSE) }
            }
            Outcome.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            Outcome.Failure(e.kind, e.code ?: e.message, e)
        }
    }
}
