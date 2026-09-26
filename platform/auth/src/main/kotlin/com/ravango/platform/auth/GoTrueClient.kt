package com.ravango.platform.auth

import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.UserAccount
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttp
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.auth.supabase.await
import com.ravango.platform.auth.supabase.executeForText
import com.ravango.platform.auth.supabase.parseSupabaseError
import com.ravango.platform.auth.supabase.toRequestBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Tokens returned by GoTrue for a successful sign-in / refresh. */
internal data class GoTrueSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val user: JsonObject,
)

/**
 * Thin client for the Supabase GoTrue REST API (`/auth/v1`). No Supabase SDK: plain OkHttp +
 * kotlinx.serialization so the dependency surface stays small. All methods throw [SupabaseHttpException] for
 * HTTP errors and IOException for network failures; the repository maps both to [AuthError].
 */
@Singleton
internal class GoTrueClient @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    @SupabaseHttp private val http: OkHttpClient,
) {

    suspend fun sendOtp(email: String? = null, phone: String? = null, createUser: Boolean = true) {
        val body = buildJsonObject {
            if (email != null) put("email", email)
            if (phone != null) {
                put("phone", phone)
                put("channel", "sms")
            }
            put("create_user", createUser)
        }
        post(endpoints.auth("otp"), body)
    }

    suspend fun verifyOtp(type: String, token: String, email: String? = null, phone: String? = null): GoTrueSession {
        val body = buildJsonObject {
            put("type", type)
            put("token", token)
            if (email != null) put("email", email)
            if (phone != null) put("phone", phone)
        }
        return parseSession(post(endpoints.auth("verify"), body))
    }

    /** Returns a session when the project auto-confirms emails, or null when a confirmation email was sent. */
    suspend fun signUp(email: String, password: String, displayName: String?): GoTrueSession? {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            putJsonObject("data") { if (!displayName.isNullOrBlank()) put("display_name", displayName) }
        }
        val json = post(endpoints.auth("signup"), body)
        return if (json["access_token"] != null) parseSession(json) else null
    }

    suspend fun signInWithPassword(email: String, password: String): GoTrueSession =
        parseSession(post(tokenUrl("password"), buildJsonObject { put("email", email); put("password", password) }))

    suspend fun signInWithIdToken(provider: String, idToken: String, rawNonce: String?): GoTrueSession =
        parseSession(
            post(
                tokenUrl("id_token"),
                buildJsonObject {
                    put("provider", provider)
                    put("id_token", idToken)
                    if (rawNonce != null) put("nonce", rawNonce)
                },
            ),
        )

    suspend fun refresh(refreshToken: String): GoTrueSession =
        parseSession(post(tokenUrl("refresh_token"), buildJsonObject { put("refresh_token", refreshToken) }))

    suspend fun recover(email: String) {
        post(endpoints.auth("recover"), buildJsonObject { put("email", email) })
    }

    suspend fun logout(accessToken: String) {
        val request = endpoints.request(endpoints.auth("logout"), accessToken)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).await().use { response ->
            // 401/403/404 mean the session is already gone server-side, which is the goal.
            if (!response.isSuccessful && response.code !in setOf(401, 403, 404)) {
                throw parseSupabaseError(response.code, response.body?.string().orEmpty())
            }
        }
    }

    suspend fun getUser(accessToken: String): JsonObject {
        val request = endpoints.request(endpoints.auth("user"), accessToken).get().build()
        return parseObject(http.executeForText(request))
    }

    suspend fun updateUserMetadata(accessToken: String, metadata: Map<String, String?>): JsonObject {
        val body = buildJsonObject {
            putJsonObject("data") {
                metadata.forEach { (k, v) -> if (v == null) put(k, JsonNull) else put(k, v) }
            }
        }
        val request = endpoints.request(endpoints.auth("user"), accessToken).put(body.toRequestBody()).build()
        return parseObject(http.executeForText(request))
    }

    /**
     * Deletes the account. Prefers the `delete-account` edge function (removes storage objects and the auth user
     * with the service role); falls back to the `delete_account()` SQL RPC (security definer) when the function is
     * not deployed. See backend/README.md.
     */
    suspend fun deleteAccount(accessToken: String) {
        val fnRequest = endpoints.request(endpoints.function("delete-account"), accessToken)
            .post(buildJsonObject { put("confirm", true) }.toRequestBody())
            .build()
        val fnResult = http.newCall(fnRequest).await().use { r -> r.code to r.body?.string().orEmpty() }
        when {
            fnResult.first in 200..299 -> return
            fnResult.first == 404 -> Unit // function not deployed → RPC fallback
            else -> throw parseSupabaseError(fnResult.first, fnResult.second)
        }
        val rpc = endpoints.request(endpoints.rest("rpc/delete_account"), accessToken)
            .post(JsonObject(emptyMap()).toRequestBody())
            .build()
        http.executeForText(rpc)
    }

    private fun tokenUrl(grant: String) = endpoints.auth("token").newBuilder().addQueryParameter("grant_type", grant).build()

    private suspend fun post(url: okhttp3.HttpUrl, body: JsonElement): JsonObject {
        val request: Request = endpoints.request(url).post(body.toRequestBody()).build()
        val text = http.executeForText(request)
        return if (text.isBlank()) JsonObject(emptyMap()) else parseObject(text)
    }

    private fun parseObject(text: String): JsonObject =
        runCatching { SupabaseJson.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun parseSession(json: JsonObject): GoTrueSession {
        val access = json.string("access_token") ?: throw SupabaseHttpException(500, "invalid_session", "missing access_token")
        val refresh = json.string("refresh_token") ?: throw SupabaseHttpException(500, "invalid_session", "missing refresh_token")
        val expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull?.times(1000)
            ?: (System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) * 1000)
        val user = json["user"] as? JsonObject ?: JsonObject(emptyMap())
        return GoTrueSession(access, refresh, expiresAt, user)
    }
}

internal fun JsonObject.string(key: String): String? = runCatching { (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.getOrNull()

/** Maps a GoTrue user object to the app model. */
internal fun JsonObject.toUserAccount(method: AuthProviderType): UserAccount {
    val meta = get("user_metadata") as? JsonObject ?: JsonObject(emptyMap())
    val appMeta = get("app_metadata") as? JsonObject ?: JsonObject(emptyMap())
    // The last-used method is what the user recognises; Google identities are always shown as Google.
    val provider = if (appMeta.string("provider") == "google") AuthProviderType.GOOGLE else method
    val createdAt = string("created_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
    return UserAccount(
        id = string("id").orEmpty(),
        email = string("email")?.takeIf { it.isNotBlank() },
        phone = string("phone")?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("+")) it else "+$it" },
        displayName = meta.string("display_name") ?: meta.string("full_name") ?: meta.string("name"),
        avatarUrl = meta.string("avatar_url") ?: meta.string("picture"),
        provider = provider,
        createdAt = createdAt,
    )
}

/** Maps transport/server failures to typed auth errors. */
internal fun Throwable.toAuthError(): AuthError = when (this) {
    is SupabaseHttpException -> when {
        isRateLimited -> AuthError.RATE_LIMITED
        errorCode == "otp_expired" -> AuthError.INVALID_OTP
        errorCode in setOf("invalid_credentials", "invalid_grant") && serverMessage?.contains("refresh", true) == true -> AuthError.SESSION_EXPIRED
        errorCode in setOf("invalid_credentials", "invalid_grant") -> AuthError.INVALID_CREDENTIALS
        errorCode == "email_not_confirmed" -> AuthError.EMAIL_NOT_CONFIRMED
        errorCode in setOf("user_already_exists", "email_exists", "phone_exists", "identity_already_exists") -> AuthError.USER_EXISTS
        errorCode == "weak_password" -> AuthError.WEAK_PASSWORD
        errorCode in setOf("email_address_invalid", "email_address_not_authorized") -> AuthError.INVALID_EMAIL
        errorCode in setOf("sms_send_failed", "phone_provider_disabled", "email_provider_disabled", "provider_disabled", "signup_disabled", "otp_disabled") ->
            AuthError.PROVIDER_DISABLED
        errorCode in setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "bad_jwt", "user_not_found") ->
            AuthError.SESSION_EXPIRED
        errorCode == "validation_failed" && serverMessage?.contains("phone", true) == true -> AuthError.INVALID_PHONE
        errorCode == "validation_failed" && serverMessage?.contains("email", true) == true -> AuthError.INVALID_EMAIL
        serverMessage?.contains("Token has expired or is invalid", true) == true -> AuthError.INVALID_OTP
        serverMessage?.contains("Invalid login credentials", true) == true -> AuthError.INVALID_CREDENTIALS
        isUnauthorized -> AuthError.SESSION_EXPIRED
        status >= 500 -> AuthError.NETWORK
        else -> AuthError.UNKNOWN
    }
    is java.io.IOException -> AuthError.NETWORK
    else -> AuthError.UNKNOWN
}
