package com.ravango.engine.ai.llm

import com.anthropic.client.AnthropicClient
import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AiPreferences
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.core.model.service.AuthState
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AiSetupIssue
import com.ravango.engine.ai.config.SecureAiCredentialStore
import com.ravango.engine.ai.di.AiHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the text provider from the user's preferences and what this build/device has configured, and explains
 * precisely what is missing when nothing usable is selected.
 */
@Singleton
class ProviderResolver @Inject constructor(
    private val appConfig: AppConfig,
    private val preferences: PreferencesDataSource,
    private val credentials: SecureAiCredentialStore,
    private val auth: AuthSessionProvider,
    @AiHttpClient private val http: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface Resolution {
        data class Ready(val provider: LlmProvider) : Resolution
        data class Unavailable(val kind: ErrorKind, val code: String) : Resolution
    }

    val availability: StateFlow<AiAvailability> =
        combine(preferences.userPreferences.map { it.ai }, credentials.version, auth.authState) { ai, _, authState ->
            computeAvailability(ai, authState)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            AiAvailability(configured = false, provider = AiProviderId.RAVANGO_GATEWAY, consentRequired = true),
        )

    /** The provider for the current settings, or why it cannot be used. Does not check consent. */
    suspend fun resolve(): Resolution {
        val ai = preferences.currentUserPreferences().ai
        val a = computeAvailability(ai, auth.authState.value)
        if (!a.configured) return Resolution.Unavailable(kindFor(a.issue), codeFor(a.issue))
        return Resolution.Ready(create(ai))
    }

    suspend fun hasConsent(): Boolean = preferences.currentUserPreferences().ai.cloudProcessingConsent

    /** Base URL of the RavanGo gateway (no trailing slash), or null when not configured in this build. */
    fun gatewayBaseUrl(): String? = appConfig.aiGatewayUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    /** The user's session token for gateway calls. */
    suspend fun gatewayToken(): String? = auth.accessToken()

    private fun create(ai: AiPreferences): LlmProvider = when (ai.textProvider) {
        AiProviderId.RAVANGO_GATEWAY -> AnthropicLlmProvider(
            id = AiProviderId.RAVANGO_GATEWAY,
            clientProvider = { gatewayClient() },
            model = DEFAULT_CLAUDE_MODEL,
            io = io,
        )
        AiProviderId.ANTHROPIC_DIRECT -> AnthropicLlmProvider(
            id = AiProviderId.ANTHROPIC_DIRECT,
            clientProvider = { directClient() },
            model = DEFAULT_CLAUDE_MODEL,
            io = io,
        )
        AiProviderId.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmProvider(
            http = http,
            baseUrl = ai.customBaseUrl.orEmpty(),
            apiKey = credentials.key(AiProviderId.OPENAI_COMPATIBLE),
            model = ai.customModel.orEmpty(),
            io = io,
        )
    }

    // SDK clients own connection pools; keep one per credential and close replaced ones.
    private val clientLock = Mutex()
    private var cachedClient: Pair<String, AnthropicClient>? = null

    private suspend fun gatewayClient(): AnthropicClient {
        val base = gatewayBaseUrl() ?: throw LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.GATEWAY_NOT_CONFIGURED)
        val token = auth.accessToken() ?: throw LlmException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED)
        return cached("gw|$base|$token") { AnthropicLlmProvider.buildClient(base, bearerToken = token) }
    }

    private suspend fun directClient(): AnthropicClient {
        val key = credentials.key(AiProviderId.ANTHROPIC_DIRECT)
            ?: throw LlmException(ErrorKind.NOT_CONFIGURED, AiErrors.API_KEY_MISSING)
        return cached("direct|${key.hashCode()}") { AnthropicLlmProvider.buildClient(AnthropicLlmProvider.ANTHROPIC_API_URL, apiKey = key) }
    }

    private suspend fun cached(cacheKey: String, create: () -> AnthropicClient): AnthropicClient = clientLock.withLock {
        cachedClient?.takeIf { it.first == cacheKey }?.let { return@withLock it.second }
        val client = create()
        val previous = cachedClient?.second
        cachedClient = cacheKey to client
        if (previous != null) {
            // The previous client may still be serving an in-flight stream (bounded by its 10-minute timeout).
            scope.launch {
                delay(RETIRED_CLIENT_GRACE_MS)
                runCatching { previous.close() }
            }
        }
        client
    }

    private fun computeAvailability(ai: AiPreferences, authState: AuthState): AiAvailability {
        val consentRequired = !ai.cloudProcessingConsent
        val issue: AiSetupIssue? = when (ai.textProvider) {
            AiProviderId.RAVANGO_GATEWAY -> when {
                !appConfig.isAiGatewayConfigured -> AiSetupIssue.GATEWAY_NOT_CONFIGURED
                authState !is AuthState.SignedIn -> AiSetupIssue.SIGN_IN_REQUIRED
                else -> null
            }
            AiProviderId.ANTHROPIC_DIRECT -> if (credentials.hasKey(AiProviderId.ANTHROPIC_DIRECT)) null else AiSetupIssue.API_KEY_MISSING
            AiProviderId.OPENAI_COMPATIBLE -> when {
                ai.customBaseUrl.isNullOrBlank() || ai.customModel.isNullOrBlank() -> AiSetupIssue.CUSTOM_ENDPOINT_MISSING
                else -> null // A key is optional for self-hosted endpoints.
            }
        }
        return AiAvailability(
            configured = issue == null,
            provider = ai.textProvider,
            consentRequired = consentRequired,
            detail = issue?.let { detailFor(it) },
            issue = issue,
        )
    }

    private fun detailFor(issue: AiSetupIssue): String = when (issue) {
        AiSetupIssue.GATEWAY_NOT_CONFIGURED -> "AI gateway URL not configured in this build (RAVANGO_AI_GATEWAY_URL)"
        AiSetupIssue.SIGN_IN_REQUIRED -> "Sign in to use RavanGo AI credits"
        AiSetupIssue.API_KEY_MISSING -> "Add your Anthropic API key in Settings"
        AiSetupIssue.CUSTOM_ENDPOINT_MISSING -> "Set the base URL and model of your OpenAI-compatible endpoint in Settings"
        AiSetupIssue.ON_DEVICE_UNSUPPORTED -> "On-device recognition is not available on this device"
    }

    companion object {
        private const val RETIRED_CLIENT_GRACE_MS = 11 * 60_000L

        fun kindFor(issue: AiSetupIssue?): ErrorKind = when (issue) {
            AiSetupIssue.SIGN_IN_REQUIRED -> ErrorKind.AUTH
            AiSetupIssue.ON_DEVICE_UNSUPPORTED -> ErrorKind.NOT_SUPPORTED
            else -> ErrorKind.NOT_CONFIGURED
        }

        fun codeFor(issue: AiSetupIssue?): String = when (issue) {
            AiSetupIssue.GATEWAY_NOT_CONFIGURED -> AiErrors.GATEWAY_NOT_CONFIGURED
            AiSetupIssue.SIGN_IN_REQUIRED -> AiErrors.SIGN_IN_REQUIRED
            AiSetupIssue.API_KEY_MISSING -> AiErrors.API_KEY_MISSING
            AiSetupIssue.CUSTOM_ENDPOINT_MISSING -> AiErrors.CUSTOM_ENDPOINT_MISSING
            AiSetupIssue.ON_DEVICE_UNSUPPORTED -> AiErrors.ON_DEVICE_UNSUPPORTED
            null -> AiErrors.SERVICE_NOT_AVAILABLE
        }
    }
}
