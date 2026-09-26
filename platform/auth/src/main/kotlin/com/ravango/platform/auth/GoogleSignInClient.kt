package com.ravango.platform.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.ravango.core.common.AppConfig
import com.ravango.core.common.log.RgLog
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** A Google ID token plus the raw nonce whose SHA-256 was embedded in it (Supabase verifies the pair). */
internal data class GoogleIdToken(val idToken: String, val rawNonce: String)

/**
 * Google Sign-In through Credential Manager. First tries the bottom-sheet account picker ([GetGoogleIdOption]);
 * when the device has no eligible saved account it falls back to the full "Sign in with Google" flow
 * ([GetSignInWithGoogleOption]). Must be called with an Activity context.
 */
@Singleton
internal class GoogleSignInClient @Inject constructor(private val config: AppConfig) {

    val isConfigured: Boolean get() = config.isGoogleSignInConfigured

    sealed interface Result {
        data class Success(val token: GoogleIdToken) : Result
        data class Failure(val error: AuthError, val detail: String? = null) : Result
    }

    suspend fun requestIdToken(activityContext: Context): Result {
        if (!isConfigured) return Result.Failure(AuthError.GOOGLE_NOT_CONFIGURED)
        val manager = CredentialManager.create(activityContext)
        val rawNonce = randomNonce()
        val hashedNonce = sha256Hex(rawNonce)

        val pickerOption = GetGoogleIdOption.Builder()
            .setServerClientId(config.googleWebClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()
        val response = try {
            manager.getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(pickerOption).build())
        } catch (e: NoCredentialException) {
            RgLog.i(TAG, "no saved Google account, falling back to Sign in with Google")
            val siwg = GetSignInWithGoogleOption.Builder(config.googleWebClientId).setNonce(hashedNonce).build()
            try {
                manager.getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(siwg).build())
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: GetCredentialException) {
                return mapException(e2)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialException) {
            return mapException(e)
        }
        return extractToken(response, rawNonce)
    }

    private fun extractToken(response: GetCredentialResponse, rawNonce: String): Result {
        val credential = response.credential
        if (credential is CustomCredential &&
            (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL || credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)
        ) {
            return try {
                Result.Success(GoogleIdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken, rawNonce))
            } catch (e: GoogleIdTokenParsingException) {
                RgLog.w(TAG, "invalid Google ID token", e)
                Result.Failure(AuthError.GOOGLE_UNAVAILABLE, e.message)
            }
        }
        return Result.Failure(AuthError.GOOGLE_UNAVAILABLE, "unexpected credential type ${credential.type}")
    }

    private fun mapException(e: GetCredentialException): Result = when (e) {
        is GetCredentialCancellationException -> Result.Failure(AuthError.CANCELLED)
        is GetCredentialInterruptedException -> Result.Failure(AuthError.CANCELLED, e.message)
        is NoCredentialException -> Result.Failure(AuthError.GOOGLE_UNAVAILABLE, "no Google account on this device")
        is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException ->
            Result.Failure(AuthError.GOOGLE_UNAVAILABLE, e.message)
        else -> {
            RgLog.w(TAG, "Google sign-in failed: ${e.type}", e)
            Result.Failure(AuthError.GOOGLE_UNAVAILABLE, e.message)
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "GoogleSignIn"
    }
}
