package com.ravango.engine.ai.speech

import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.engine.ai.api.AiErrors
import com.ravango.engine.ai.api.Transcript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Whisper-compatible `POST /v1/audio/transcriptions` client (multipart, `response_format=verbose_json`,
 * `timestamp_granularities[]=word` + `segment`). Targets either the RavanGo gateway (user JWT) or OpenAI directly
 * (user key). Cancellation cancels the HTTP call.
 */
class WhisperTranscriber(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val bearer: String,
    private val model: String,
    private val io: CoroutineDispatcher,
    private val isGateway: Boolean,
) {

    suspend fun transcribe(wav: File, language: String?): Transcript = withContext(io) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody(WAV))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { language?.substringBefore('-')?.takeIf { it.length == 2 }?.let { addFormDataPart("language", it) } }
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearer")
            .post(body)
            .build()
        val response = http.newCall(request).await()
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code)
            WhisperParser.parse(text) ?: throw AppException(ErrorKind.UNKNOWN, AiErrors.INVALID_OUTPUT)
        }
    }

    private fun httpError(code: Int): AppException {
        RgLog.w(TAG, "transcription HTTP $code")
        return when (code) {
            401, 403 -> if (isGateway) AppException(ErrorKind.AUTH, AiErrors.SIGN_IN_REQUIRED) else AppException(ErrorKind.NOT_CONFIGURED, AiErrors.INVALID_API_KEY)
            402 -> AppException(ErrorKind.QUOTA, AiErrors.NO_CREDITS)
            404, 501 -> AppException(ErrorKind.NOT_CONFIGURED, AiErrors.SERVICE_NOT_AVAILABLE)
            413 -> AppException(ErrorKind.INVALID_INPUT, "audio chunk too large")
            429 -> AppException(ErrorKind.NETWORK, AiErrors.RATE_LIMITED)
            in 500..599 -> AppException(ErrorKind.NETWORK, AiErrors.OVERLOADED)
            else -> AppException(ErrorKind.UNKNOWN, "HTTP $code")
        }
    }

    companion object {
        private const val TAG = "Whisper"
        private val WAV = "audio/wav".toMediaType()
        const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val OPENAI_MODEL = "whisper-1"
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
    cont.invokeOnCancellation { runCatching { cancel() } }
}
