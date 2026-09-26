package com.ravango.engine.ai.llm

import com.ravango.core.common.result.ErrorKind
import com.ravango.core.model.AiOperation
import com.ravango.core.model.AiProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

/** Default Claude model for every RavanGo text task. */
const val DEFAULT_CLAUDE_MODEL = "claude-opus-5"

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val text: String)

/**
 * How much reasoning the task deserves. Maps to Claude's `output_config.effort` (thinking is adaptive by default on
 * the default model); OpenAI-compatible providers ignore it unless they understand `reasoning_effort`.
 */
enum class EffortHint { LOW, MEDIUM, HIGH }

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    /** Hard cap on generated tokens (on Claude this includes adaptive thinking). */
    val maxTokens: Int,
    val effort: EffortHint = EffortHint.MEDIUM,
    /** What the request is metered as; sent to the RavanGo gateway (`X-RavanGo-Operation`) so both ledgers agree. */
    val operation: AiOperation? = null,
)

/** A provider failure mapped to the app's error model. [code] is one of [com.ravango.engine.ai.api.AiErrors]. */
class LlmException(
    val kind: ErrorKind,
    val code: String? = null,
    val retryAfterSec: Long? = null,
    message: String? = code,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A text-generation backend. Implementations: [AnthropicLlmProvider] (RavanGo gateway or Anthropic direct) and
 * [OpenAiCompatibleLlmProvider]. Streams text deltas; throws [LlmException] on failure; cancelling collection aborts
 * the HTTP stream.
 */
interface LlmProvider {
    val id: AiProviderId

    /** True when requests are metered in RavanGo credits (gateway). BYOK providers bill the user's own account. */
    val usesRavanGoCredits: Boolean

    fun stream(request: LlmRequest): Flow<String>
}

/** Collects the full response. */
suspend fun LlmProvider.complete(request: LlmRequest): String = stream(request).fold(StringBuilder()) { sb, d -> sb.append(d) }.toString()
