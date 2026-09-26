package com.ravango.platform.auth.supabase

import com.ravango.core.common.AppConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OkHttp client dedicated to Supabase (auth, PostgREST, storage, edge functions). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SupabaseHttp

/** JSON codec for Supabase payloads: lenient, tolerant to server-side schema additions. */
val SupabaseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}

val JsonMediaType = "application/json; charset=utf-8".toMediaType()

fun JsonElement.toRequestBody(): RequestBody = SupabaseJson.encodeToString(JsonElement.serializer(), this).toRequestBody(JsonMediaType)

/**
 * Endpoints and headers for the configured Supabase project. When [isConfigured] is false the whole app runs in
 * local/guest mode and every cloud feature says "Cloud not configured in this build".
 */
@Singleton
class SupabaseEndpoints @Inject constructor(private val config: AppConfig) {
    val isConfigured: Boolean get() = config.isCloudConfigured
    val anonKey: String get() = config.supabaseAnonKey
    val baseUrl: String get() = config.supabaseUrl.trimEnd('/')

    fun url(path: String): HttpUrl = (baseUrl + "/" + path.trimStart('/')).toHttpUrl()

    fun auth(path: String): HttpUrl = url("auth/v1/" + path.trimStart('/'))
    fun rest(path: String): HttpUrl = url("rest/v1/" + path.trimStart('/'))
    fun storage(path: String): HttpUrl = url("storage/v1/" + path.trimStart('/'))
    fun function(name: String): HttpUrl = url("functions/v1/$name")

    /** Adds the project api key and the bearer token (user access token, or the anon key for public endpoints). */
    fun request(url: HttpUrl, accessToken: String? = null): Request.Builder = Request.Builder()
        .url(url)
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${accessToken ?: anonKey}")
        .header("X-Client-Info", "ravango-android/${config.versionName}")
}

/** An HTTP failure with the parsed Supabase error payload. */
class SupabaseHttpException(
    val status: Int,
    val errorCode: String?,
    val serverMessage: String?,
) : IOException("HTTP $status ${errorCode.orEmpty()} ${serverMessage.orEmpty()}".trim()) {
    val isRateLimited: Boolean get() = status == 429 || errorCode?.startsWith("over_") == true
    val isUnauthorized: Boolean get() = status == 401 || status == 403
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** Executes the request, returning the body text for 2xx and throwing [SupabaseHttpException] otherwise. */
suspend fun OkHttpClient.executeForText(request: Request): String {
    newCall(request).await().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw parseSupabaseError(response.code, text)
        return text
    }
}

fun parseSupabaseError(status: Int, body: String): SupabaseHttpException {
    val obj = runCatching { SupabaseJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    fun str(key: String) = runCatching { obj?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
    val code = str("error_code") ?: str("code")?.takeIf { it.toIntOrNull() == null } ?: str("error")
    val message = str("msg") ?: str("error_description") ?: str("message") ?: str("error")
    val statusFromBody = runCatching { obj?.get("code")?.jsonPrimitive?.intOrNull }.getOrNull()
    return SupabaseHttpException(statusFromBody?.takeIf { it in 400..599 } ?: status, code, message)
}
