package com.ravango.engine.ai.eye

import android.content.Context
import android.net.Uri
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.media.MediaProbe
import com.ravango.core.model.AiOperation
import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.engine.ai.api.EyeContactService
import com.ravango.engine.ai.di.AiHttpClient
import com.ravango.engine.ai.llm.ProviderResolver
import com.ravango.engine.ai.speech.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Eye-contact (gaze redirection) needs a specialised video model that does not run on Android devices, so this
 * service is an honest integration seam: it is AVAILABLE only when the RavanGo gateway reports an eye-contact
 * backend (`GET {gateway}/v1/capabilities` → `{"eye_contact": true}`; the gateway forwards to a provider such as an
 * NVIDIA Maxine Eye Contact deployment configured by `EYE_CONTACT_API_URL`). Otherwise it reports REQUIRES_SERVICE
 * and [correct] fails with NOT_CONFIGURED — it never fakes a result.
 *
 * Job protocol (see backend/README-ai.md): create job → PUT the file to the returned upload URL → start → poll status
 * → download the result.
 */
@Singleton
class GatewayEyeContactService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ProviderResolver,
    private val auth: AuthSessionProvider,
    private val entitlements: EntitlementProvider,
    private val probe: MediaProbe,
    @AiHttpClient private val http: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : EyeContactService {

    private val _state = MutableStateFlow(CapabilityState.REQUIRES_SERVICE)

    /** Observable variant of [state] (additive; the contract exposes a snapshot). */
    val stateFlow: StateFlow<CapabilityState> = _state.asStateFlow()

    override val state: CapabilityState get() = _state.value

    override val requiredService: String =
        "Server-side gaze-redirection model (e.g. NVIDIA Maxine Eye Contact) behind the RavanGo AI gateway endpoint /v1/video/eye-contact"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    init {
        // Refresh capabilities whenever the user signs in (the gateway requires a session).
        scope.launch {
            auth.authState.filterIsInstance<AuthState.SignedIn>().distinctUntilChanged().collect { refreshCapabilities() }
        }
    }

    @Serializable private data class Capabilities(val eye_contact: Boolean = false)

    @Serializable private data class CreateJobResponse(val job_id: String, val upload_url: String, val upload_headers: Map<String, String> = emptyMap())

    @Serializable private data class JobStatus(val status: String, val progress: Float? = null, val result_url: String? = null, val error: String? = null)

    suspend fun refreshCapabilities(): CapabilityState {
        val base = resolver.gatewayBaseUrl() ?: return CapabilityState.REQUIRES_SERVICE.also { _state.value = it }
        val token = auth.accessToken() ?: return _state.value
        val available = runCatching {
            http.newCall(Request.Builder().url("$base/v1/capabilities").header("Authorization", "Bearer $token").get().build()).await().use { r ->
                r.isSuccessful && json.decodeFromString(Capabilities.serializer(), r.body?.string().orEmpty()).eye_contact
            }
        }.onFailure { RgLog.w(TAG, "capability check failed: ${it.message}") }.getOrDefault(false)
        _state.value = if (available) CapabilityState.AVAILABLE else CapabilityState.REQUIRES_SERVICE
        return _state.value
    }

    override suspend fun correct(inputUri: String, output: File, onProgress: (Float) -> Unit): Outcome<File> {
        if (!resolver.hasConsent()) return Outcome.Failure(ErrorKind.PERMISSION, AiErrors.CONSENT_REQUIRED)
        val base = resolver.gatewayBaseUrl() ?: return Outcome.Failure(ErrorKind.NOT_CONFIGURED, AiErrors.GATEWAY_NOT_CONFIGURED)
        if (state != CapabilityState.AVAILABLE && refreshCapabilities() != CapabilityState.AVAILABLE) {
            return Outcome.Failure(ErrorKind.NOT_CONFIGURED, AiErrors.SERVICE_NOT_AVAILABLE)
        }
        val token = auth.accessToken() ?: return Outcome.Failure(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED)
        val info = probe.probe(inputUri) ?: return Outcome.Failure(ErrorKind.INVALID_INPUT)
        val minutes = ceil(info.durationUs / 60_000_000.0).toInt().coerceAtLeast(1)
        if (!entitlements.tryConsumeAiCredits(AiOperation.VIDEO_ANALYSIS, minutes)) return Outcome.Failure(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
        val result = outcomeOf {
            withContext(io) {
                val auth = "Bearer $token"
                // 1) Create the job.
                val createBody = """{"content_type":"${info.mimeType}","size_bytes":${info.sizeBytes},"duration_ms":${info.durationUs / 1000}}"""
                val job = http.newCall(
                    Request.Builder().url("$base/v1/video/eye-contact").header("Authorization", auth)
                        .post(createBody.toRequestBody(JSON)).build(),
                ).await().use { r -> checkOk(r.code); json.decodeFromString(CreateJobResponse.serializer(), r.body!!.string()) }

                // 2) Upload directly to the provider's storage.
                val mediaType = info.mimeType.toMediaType()
                val upload = Request.Builder().url(job.upload_url).apply { job.upload_headers.forEach { (k, v) -> header(k, v) } }
                    .put(StreamBody(mediaType, info.sizeBytes, { open(inputUri) }) { p -> onProgress(0.4f * p) })
                    .build()
                http.newCall(upload).await().use { r -> checkOk(r.code) }

                // 3) Start and poll.
                http.newCall(Request.Builder().url("$base/v1/video/eye-contact/${job.job_id}/start").header("Authorization", auth).post(ByteArray(0).toRequestBody(null)).build())
                    .await().use { r -> checkOk(r.code) }
                var status: JobStatus
                var polls = 0
                while (true) {
                    if (++polls > MAX_POLLS) throw AppException(ErrorKind.NETWORK, "eye-contact job timed out")
                    delay(POLL_MS)
                    status = http.newCall(Request.Builder().url("$base/v1/video/eye-contact/${job.job_id}").header("Authorization", auth).get().build())
                        .await().use { r -> checkOk(r.code); json.decodeFromString(JobStatus.serializer(), r.body!!.string()) }
                    status.progress?.let { onProgress(0.4f + 0.5f * it.coerceIn(0f, 1f)) }
                    if (status.status == "succeeded" || status.status == "failed") break
                }
                if (status.status == "failed" || status.result_url == null) throw AppException(ErrorKind.UNKNOWN, status.error ?: "eye-contact job failed")

                // 4) Download.
                output.parentFile?.mkdirs()
                http.newCall(Request.Builder().url(status.result_url!!).get().build()).await().use { r ->
                    checkOk(r.code)
                    val body = r.body ?: throw AppException(ErrorKind.NETWORK)
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        output.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) onProgress(0.9f + 0.1f * copied / total)
                            }
                        }
                    }
                }
                onProgress(1f)
                output
            }
        }
        if (result is Outcome.Failure) {
            output.delete()
            withContext(NonCancellable) { entitlements.refundAiCredits(AiOperation.VIDEO_ANALYSIS, minutes) }
        }
        return result
    }

    private fun open(uri: String): InputStream {
        val u = Uri.parse(uri)
        return if (u.scheme == null || u.scheme == "file") File(u.path ?: uri).inputStream() else context.contentResolver.openInputStream(u) ?: throw AppException(ErrorKind.INVALID_INPUT)
    }

    private fun checkOk(code: Int) {
        when {
            code in 200..299 -> Unit
            code == 401 || code == 403 -> throw AppException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED)
            code == 402 -> throw AppException(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
            code == 404 || code == 501 -> throw AppException(ErrorKind.NOT_CONFIGURED, AiErrors.SERVICE_NOT_AVAILABLE)
            code == 429 -> throw AppException(ErrorKind.NETWORK, AiErrors.RATE_LIMITED)
            else -> throw AppException(ErrorKind.NETWORK, "HTTP $code")
        }
    }

    /** Streams a file/content URI with upload progress. */
    private class StreamBody(
        private val type: MediaType,
        private val length: Long,
        private val open: () -> InputStream,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = if (length > 0) length else -1
        override fun writeTo(sink: BufferedSink) {
            open().source().use { source ->
                var written = 0L
                while (true) {
                    val n = source.read(sink.buffer, 64 * 1024)
                    if (n < 0) break
                    written += n
                    sink.emitCompleteSegments()
                    if (length > 0) onProgress((written.toFloat() / length).coerceIn(0f, 1f))
                }
            }
        }
    }

    private companion object {
        const val TAG = "EyeContact"
        const val POLL_MS = 3_000L
        const val MAX_POLLS = 1_200 // one hour
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
