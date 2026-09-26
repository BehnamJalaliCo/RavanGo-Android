package com.ravango.platform.auth

import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.UserAccount
import kotlinx.serialization.Serializable

/** Typed authentication failures; the UI maps each to a localized message. */
enum class AuthError {
    /** Supabase URL / anon key missing in this build. */
    NOT_CONFIGURED,
    /** Google Sign-In client id missing in this build. */
    GOOGLE_NOT_CONFIGURED,
    /** Device has no Google Play services / Credential Manager provider, or no Google account. */
    GOOGLE_UNAVAILABLE,
    NETWORK,
    RATE_LIMITED,
    INVALID_OTP,
    INVALID_CREDENTIALS,
    INVALID_EMAIL,
    INVALID_PHONE,
    WEAK_PASSWORD,
    USER_EXISTS,
    EMAIL_NOT_CONFIRMED,
    /** The sign-in method (e.g. SMS provider) is disabled in the Supabase project. */
    PROVIDER_DISABLED,
    /** Refresh token revoked or expired: the user must sign in again. */
    SESSION_EXPIRED,
    NOT_SIGNED_IN,
    CANCELLED,
    UNKNOWN,
}

/** Result of an auth operation. */
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failure(val error: AuthError, val detail: String? = null) : AuthResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
}

/** Outcome of an email + password sign-up. */
sealed interface SignUpResult {
    data class SignedIn(val user: UserAccount) : SignUpResult
    /** The project requires email confirmation; a link was sent to [email]. */
    data class ConfirmationRequired(val email: String) : SignUpResult
}

/** Persisted session (encrypted in SecureStore). */
@Serializable
internal data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    /** Absolute expiry of [accessToken] in epoch ms. */
    val expiresAtMs: Long,
    val user: UserAccount,
    val method: AuthProviderType,
)

/** Receives account lifecycle callbacks (cloud sync clears cursors, deletes backed-up media, wipes local data). */
interface AccountLifecycleListener {
    /** Called before the server-side account deletion; throw to abort (e.g. media could not be deleted). */
    suspend fun beforeAccountDeletion(userId: String, accessToken: String) {}

    /** Called after the local session was cleared. [wipeLocalData] = the user asked to also erase this device's data. */
    suspend fun onSignedOut(userId: String, wipeLocalData: Boolean) {}

    /** Called after a successful sign-in (not on app restart with a stored session). */
    suspend fun onSignedIn(user: UserAccount) {}
}
