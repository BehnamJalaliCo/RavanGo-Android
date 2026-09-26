package com.ravango.engine.ai.speech

import android.content.Context
import android.os.Build
import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AiOperation
import com.ravango.core.model.AiPreferences
import com.ravango.core.model.SpeechProviderId
import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.AiSetupIssue
import com.ravango.engine.ai.api.SpeechAvailability
import com.ravango.engine.ai.api.SpeechToTextService
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.config.SecureAiCredentialStore
import com.ravango.engine.ai.di.AiHttpClient
import com.ravango.engine.ai.llm.ProviderResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Speech-to-text with three providers:
 * - RAVANGO_GATEWAY: `{gateway}/v1/audio/transcriptions` with the user's session token; metered at
 *   [AiOperation.TRANSCRIBE_PER_MINUTE] × started minutes.
 * - OPENAI_WHISPER: OpenAI's Whisper API with the user's own key.
 * - ANDROID_ON_DEVICE: the platform's on-device recognizer (API 33+), no network, no consent needed.
 *
 * Audio is prepared once ([AudioPreparer]: 16 kHz mono WAV chunks ≤ 10 min with 1.5 s overlap), transcribed chunk by
 * chunk with progress, then merged with source-relative timestamps ([TranscriptMerger]).
 */
@Singleton
class DefaultSpeechToTextService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfig: AppConfig,
    private val preferences: PreferencesDataSource,
    private val credentials: SecureAiCredentialStore,
    private val auth: AuthSessionProvider,
    private val entitlements: EntitlementProvider,
    private val preparer: AudioPreparer,
    private val resolver: ProviderResolver,
    @AiHttpClient private val http: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) : SpeechToTextService {

    override val availability: StateFlow<SpeechAvailability> =
        combine(preferences.userPreferences.map { it.ai }, credentials.version, auth.authState) { ai, _, authState -> compute(ai, authState) }
            .stateIn(scope, SharingStarted.Eagerly, SpeechAvailability(false, SpeechProviderId.RAVANGO_GATEWAY, consentRequired = true))

    override suspend fun transcribe(
        uri: String,
        language: String?,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit,
    ): Outcome<Transcript> {
        val ai = preferences.currentUserPreferences().ai
        val a = compute(ai, auth.authState.value)
        if (!a.configured) return Outcome.Failure(ProviderResolver.kindFor(a.issue), ProviderResolver.codeFor(a.issue))
        if (a.consentRequired) return Outcome.Failure(ErrorKind.PERMISSION, AiErrors.CONSENT_REQUIRED)
        return outcomeOf {
            // Preparation is the first ~15% of progress; recognition the rest.
            preparer.prepare(uri, startUs, endUs, onProgress = { onProgress(it * PREP_SHARE) }).use { prepared ->
                val results = when (ai.speechProvider) {
                    SpeechProviderId.RAVANGO_GATEWAY -> viaGateway(prepared, language, onProgress)
                    SpeechProviderId.OPENAI_WHISPER -> viaWhisper(
                        WhisperTranscriber(
                            http, WhisperTranscriber.OPENAI_TRANSCRIPTIONS_URL,
                            credentials.speechKey() ?: throw AppException(ErrorKind.NOT_CONFIGURED, AiErrors.API_KEY_MISSING),
                            WhisperTranscriber.OPENAI_MODEL, io, isGateway = false,
                        ),
                        prepared, language, onProgress,
                    )
                    SpeechProviderId.ANDROID_ON_DEVICE -> viaOnDevice(prepared, language, onProgress)
                }
                val merged = TranscriptMerger.merge(results)
                onProgress(1f)
                if (merged.segments.isEmpty()) throw AppException(ErrorKind.INVALID_INPUT, AiErrors.NO_SPEECH)
                merged
            }
        }
    }

    private suspend fun viaGateway(prepared: AudioPreparer.Prepared, language: String?, onProgress: (Float) -> Unit): List<TranscriptMerger.ChunkResult> {
        val base = resolver.gatewayBaseUrl() ?: throw AppException(ErrorKind.NOT_CONFIGURED, AiErrors.GATEWAY_NOT_CONFIGURED)
        val token = auth.accessToken() ?: throw AppException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED)
        val minutes = ceil(prepared.totalDurationUs / 60_000_000.0).toInt().coerceAtLeast(1)
        if (!entitlements.tryConsumeAiCredits(AiOperation.TRANSCRIBE_PER_MINUTE, minutes)) throw AppException(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
        try {
            val transcriber = WhisperTranscriber(http, "$base/v1/audio/transcriptions", token, WhisperTranscriber.OPENAI_MODEL, io, isGateway = true)
            return viaWhisper(transcriber, prepared, language, onProgress)
        } catch (e: Throwable) {
            withContext(NonCancellable) { entitlements.refundAiCredits(AiOperation.TRANSCRIBE_PER_MINUTE, minutes) }
            throw e
        }
    }

    private suspend fun viaWhisper(
        transcriber: WhisperTranscriber,
        prepared: AudioPreparer.Prepared,
        language: String?,
        onProgress: (Float) -> Unit,
    ): List<TranscriptMerger.ChunkResult> {
        val total = prepared.chunks.size
        return prepared.chunks.mapIndexed { i, chunk ->
            val t = transcriber.transcribe(chunk.file, language)
            onProgress(PREP_SHARE + (1 - PREP_SHARE) * (i + 1f) / total)
            TranscriptMerger.ChunkResult(chunk.startUs, chunk.durationUs, t)
        }
    }

    private suspend fun viaOnDevice(prepared: AudioPreparer.Prepared, language: String?, onProgress: (Float) -> Unit): List<TranscriptMerger.ChunkResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw AppException(ErrorKind.NOT_SUPPORTED, AiErrors.ON_DEVICE_UNSUPPORTED)
        val transcriber = OnDeviceTranscriber(context, io)
        val total = prepared.chunks.size
        return prepared.chunks.mapIndexed { i, chunk ->
            val samples = withContext(io) { WavWriter.readSamples(chunk.file) }
            val t = transcriber.transcribe(samples, AudioPreparer.TARGET_RATE, language) { p ->
                onProgress(PREP_SHARE + (1 - PREP_SHARE) * (i + p) / total)
            }
            TranscriptMerger.ChunkResult(chunk.startUs, chunk.durationUs, t)
        }
    }

    private fun compute(ai: AiPreferences, authState: AuthState): SpeechAvailability {
        val provider = ai.speechProvider
        val issue: AiSetupIssue? = when (provider) {
            SpeechProviderId.RAVANGO_GATEWAY -> when {
                !appConfig.isAiGatewayConfigured -> AiSetupIssue.GATEWAY_NOT_CONFIGURED
                authState !is AuthState.SignedIn -> AiSetupIssue.SIGN_IN_REQUIRED
                else -> null
            }
            SpeechProviderId.OPENAI_WHISPER -> if (credentials.hasSpeechKey()) null else AiSetupIssue.API_KEY_MISSING
            SpeechProviderId.ANDROID_ON_DEVICE -> if (OnDeviceTranscriber.isAvailable(context)) null else AiSetupIssue.ON_DEVICE_UNSUPPORTED
        }
        val detail = when (issue) {
            AiSetupIssue.GATEWAY_NOT_CONFIGURED -> "AI gateway URL not configured in this build (RAVANGO_AI_GATEWAY_URL)"
            AiSetupIssue.SIGN_IN_REQUIRED -> "Sign in to transcribe with RavanGo AI credits"
            AiSetupIssue.API_KEY_MISSING -> "Add your OpenAI API key for Whisper in Settings"
            AiSetupIssue.ON_DEVICE_UNSUPPORTED -> "On-device speech recognition from files needs Android 13+ with an on-device recognizer installed"
            else -> null
        }
        if (issue != null) RgLog.d(TAG, "speech provider $provider unavailable: $issue")
        return SpeechAvailability(
            configured = issue == null,
            provider = provider,
            // Audio never leaves the device with the on-device recognizer.
            consentRequired = provider != SpeechProviderId.ANDROID_ON_DEVICE && !ai.cloudProcessingConsent,
            detail = detail,
            issue = issue,
        )
    }

    private companion object {
        const val TAG = "Stt"
        const val PREP_SHARE = 0.15f
    }
}
