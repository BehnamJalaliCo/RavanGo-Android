package com.ravango.engine.ai.config

import com.ravango.core.datastore.SecureStore
import com.ravango.core.model.AiProviderId
import com.ravango.engine.ai.api.AiCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BYOK credentials in the Keystore-backed [SecureStore]. Keys never leave the device except as the auth header of
 * requests to the provider the user chose. [version] ticks on every change so availability can be recomputed.
 */
@Singleton
class SecureAiCredentialStore @Inject constructor(
    private val secureStore: SecureStore,
) : AiCredentialStore {

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    override fun hasKey(provider: AiProviderId): Boolean = !key(provider).isNullOrBlank()

    override fun setKey(provider: AiProviderId, key: String?) {
        val name = slot(provider) ?: return
        secureStore.put(name, key?.trim()?.takeIf { it.isNotEmpty() })
        _version.update { it + 1 }
    }

    override fun hasSpeechKey(): Boolean = !speechKey().isNullOrBlank()

    override fun setSpeechKey(key: String?) {
        secureStore.put(SPEECH_KEY, key?.trim()?.takeIf { it.isNotEmpty() })
        _version.update { it + 1 }
    }

    /** The stored key for [provider]; null for the gateway (it authenticates with the user's session). */
    fun key(provider: AiProviderId): String? = slot(provider)?.let(secureStore::get)

    fun speechKey(): String? = secureStore.get(SPEECH_KEY)

    private fun slot(provider: AiProviderId): String? = when (provider) {
        AiProviderId.RAVANGO_GATEWAY -> null
        AiProviderId.ANTHROPIC_DIRECT -> ANTHROPIC_KEY
        AiProviderId.OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE_KEY
    }

    private companion object {
        const val ANTHROPIC_KEY = "ai_key_anthropic"
        const val OPENAI_COMPATIBLE_KEY = "ai_key_openai_compatible"
        const val SPEECH_KEY = "ai_key_openai_whisper"
    }
}
