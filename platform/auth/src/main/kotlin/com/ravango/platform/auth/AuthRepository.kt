package com.ravango.platform.auth

import android.content.Context
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.SecureStore
import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.Clock
import com.ravango.core.model.UserAccount
import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.core.model.service.AuthState
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Account API for the UI. Also implements [AuthSessionProvider] for other modules. */
interface AuthRepository : AuthSessionProvider {
    /** Supabase configured in this build. When false, only guest mode is available. */
    val isConfigured: Boolean
    val isGoogleConfigured: Boolean
    val currentUser: UserAccount? get() = (authState.value as? AuthState.SignedIn)?.user

    suspend fun sendEmailOtp(email: String): AuthResult<String>
    suspend fun verifyEmailOtp(email: String, code: String): AuthResult<UserAccount>

    /** Sends an SMS code; returns the normalized E.164 number that must be passed to [verifyPhoneOtp]. */
    suspend fun sendPhoneOtp(phone: String): AuthResult<String>
    suspend fun verifyPhoneOtp(phone: String, code: String): AuthResult<UserAccount>

    suspend fun signUpWithPassword(email: String, password: String, displayName: String? = null): AuthResult<SignUpResult>
    suspend fun signInWithPassword(email: String, password: String): AuthResult<UserAccount>
    suspend fun sendPasswordReset(email: String): AuthResult<Unit>

    /** Requires an Activity context (Credential Manager shows UI). */
    suspend fun signInWithGoogle(activityContext: Context): AuthResult<UserAccount>

    suspend fun refreshUser(): AuthResult<UserAccount>
    suspend fun updateProfile(displayName: String?, avatarUrl: String? = null): AuthResult<UserAccount>

    /** Revokes the session server-side when possible and always clears local secrets. */
    suspend fun signOut(wipeLocalData: Boolean = false)

    /** Permanently deletes the account and its cloud data, then signs out. */
    suspend fun deleteAccount(wipeLocalData: Boolean): AuthResult<Unit>
}

@Singleton
internal class SupabaseAuthRepository @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    private val goTrue: GoTrueClient,
    private val google: GoogleSignInClient,
    private val secureStore: SecureStore,
    private val clock: Clock,
    // Provider: listeners (cloud sync) depend on this repository themselves.
    private val listenersProvider: Provider<Set<@JvmSuppressWildcards AccountLifecycleListener>>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val isConfigured: Boolean get() = endpoints.isConfigured
    override val isGoogleConfigured: Boolean get() = endpoints.isConfigured && google.isConfigured

    private val listeners: Set<AccountLifecycleListener> get() = listenersProvider.get()

    private val sessionMutex = Mutex()
    @Volatile private var session: StoredSession? = null

    init {
        scope.launch(io) {
            restoreSession()
            keepSessionFresh()
        }
    }

    private fun restoreSession() {
        if (!endpoints.isConfigured) {
            _authState.value = AuthState.Guest
            return
        }
        val stored = secureStore.get(KEY_SESSION)?.let { raw ->
            runCatching { SupabaseJson.decodeFromString(StoredSession.serializer(), raw) }
                .onFailure { RgLog.w(TAG, "stored session unreadable", it) }
                .getOrNull()
        }
        session = stored
        _authState.value = stored?.let { AuthState.SignedIn(it.user) } ?: AuthState.Guest
    }

    /** Refreshes the access token shortly before it expires while the process is alive and signed in. */
    private suspend fun keepSessionFresh() {
        authState.distinctUntilChangedBy { (it as? AuthState.SignedIn)?.user?.id }.collectLatest { state ->
            if (state !is AuthState.SignedIn) return@collectLatest
            while (true) {
                val current = session ?: return@collectLatest
                val wait = current.expiresAtMs - clock.now() - REFRESH_MARGIN_MS
                if (wait > 0) delay(wait)
                val refreshed = sessionMutex.withLock { refreshLocked(force = false) }
                // Offline: retry later; the token is also refreshed on demand in accessToken().
                if (refreshed == null || refreshed.expiresAtMs == current.expiresAtMs) delay(RETRY_DELAY_MS)
            }
        }
    }

    override suspend fun accessToken(): String? {
        if (!endpoints.isConfigured) return null
        return sessionMutex.withLock {
            val current = session ?: return@withLock null
            if (current.expiresAtMs - clock.now() > MIN_VALIDITY_MS) current.accessToken else refreshLocked(force = false)?.accessToken
        }
    }

    /**
     * Must hold [sessionMutex]. Returns the fresh session, or null when offline / revoked. A revoked refresh token
     * signs the user out locally (their data stays on the device).
     */
    private suspend fun refreshLocked(force: Boolean): StoredSession? {
        val current = session ?: return null
        if (!force && current.expiresAtMs - clock.now() > REFRESH_MARGIN_MS) return current
        return try {
            val fresh = withContext(io) { goTrue.refresh(current.refreshToken) }
            val updated = current.copy(
                accessToken = fresh.accessToken,
                refreshToken = fresh.refreshToken,
                expiresAtMs = fresh.expiresAtMs,
                user = if (fresh.user.isEmpty()) current.user else fresh.user.toUserAccount(current.method),
            )
            persist(updated)
            updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toAuthError()
            RgLog.w(TAG, "token refresh failed: $error")
            if (error == AuthError.SESSION_EXPIRED || error == AuthError.INVALID_CREDENTIALS) {
                clearLocalSession(current.user.id, wipeLocalData = false)
                null
            } else {
                // Network: keep the session; the old access token is still usable if not yet expired.
                current.takeIf { it.expiresAtMs > clock.now() }
            }
        }
    }

    private fun persist(stored: StoredSession) {
        session = stored
        secureStore.put(KEY_SESSION, SupabaseJson.encodeToString(StoredSession.serializer(), stored))
        _authState.value = AuthState.SignedIn(stored.user)
    }

    private suspend fun clearLocalSession(userId: String, wipeLocalData: Boolean) {
        session = null
        secureStore.put(KEY_SESSION, null)
        _authState.value = AuthState.Guest
        listeners.forEach { listener ->
            runCatching { listener.onSignedOut(userId, wipeLocalData) }.onFailure { RgLog.e(TAG, "sign-out listener failed", it) }
        }
    }

    private suspend fun completeSignIn(result: GoTrueSession, method: AuthProviderType): UserAccount {
        val user = result.user.toUserAccount(method)
        sessionMutex.withLock {
            persist(StoredSession(result.accessToken, result.refreshToken, result.expiresAtMs, user, method))
        }
        listeners.forEach { listener -> runCatching { listener.onSignedIn(user) }.onFailure { RgLog.e(TAG, "sign-in listener failed", it) } }
        return user
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): AuthResult<T> {
        if (!endpoints.isConfigured) return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        return try {
            AuthResult.Success(withContext(io) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthFailure) {
            AuthResult.Failure(e.error, e.message)
        } catch (e: Exception) {
            RgLog.w(TAG, "auth call failed", e)
            AuthResult.Failure(e.toAuthError(), e.message)
        }
    }

    private class AuthFailure(val error: AuthError, message: String? = null) : Exception(message)

    private fun requireEmail(email: String) = EmailAddresses.normalize(email) ?: throw AuthFailure(AuthError.INVALID_EMAIL)
    private fun requirePhone(phone: String) = PhoneNumbers.normalize(phone) ?: throw AuthFailure(AuthError.INVALID_PHONE)
    private fun requireCode(code: String) = OtpCodes.normalize(code).takeIf { it.length in 6..10 } ?: throw AuthFailure(AuthError.INVALID_OTP)

    override suspend fun sendEmailOtp(email: String): AuthResult<String> = guarded {
        val normalized = requireEmail(email)
        goTrue.sendOtp(email = normalized)
        normalized
    }

    override suspend fun verifyEmailOtp(email: String, code: String): AuthResult<UserAccount> = guarded {
        val normalized = requireEmail(email)
        completeSignIn(goTrue.verifyOtp(type = "email", token = requireCode(code), email = normalized), AuthProviderType.EMAIL_OTP)
    }

    override suspend fun sendPhoneOtp(phone: String): AuthResult<String> = guarded {
        val normalized = requirePhone(phone)
        goTrue.sendOtp(phone = normalized)
        normalized
    }

    override suspend fun verifyPhoneOtp(phone: String, code: String): AuthResult<UserAccount> = guarded {
        val normalized = requirePhone(phone)
        completeSignIn(goTrue.verifyOtp(type = "sms", token = requireCode(code), phone = normalized), AuthProviderType.PHONE_OTP)
    }

    override suspend fun signUpWithPassword(email: String, password: String, displayName: String?): AuthResult<SignUpResult> = guarded {
        val normalized = requireEmail(email)
        if (!Passwords.isAcceptable(password)) throw AuthFailure(AuthError.WEAK_PASSWORD)
        val result = goTrue.signUp(normalized, password, displayName?.trim())
        if (result == null) {
            SignUpResult.ConfirmationRequired(normalized)
        } else {
            SignUpResult.SignedIn(completeSignIn(result, AuthProviderType.EMAIL_PASSWORD))
        }
    }

    override suspend fun signInWithPassword(email: String, password: String): AuthResult<UserAccount> = guarded {
        val normalized = requireEmail(email)
        if (password.isEmpty()) throw AuthFailure(AuthError.INVALID_CREDENTIALS)
        completeSignIn(goTrue.signInWithPassword(normalized, password), AuthProviderType.EMAIL_PASSWORD)
    }

    override suspend fun sendPasswordReset(email: String): AuthResult<Unit> = guarded {
        goTrue.recover(requireEmail(email))
    }

    override suspend fun signInWithGoogle(activityContext: Context): AuthResult<UserAccount> {
        if (!endpoints.isConfigured) return AuthResult.Failure(AuthError.NOT_CONFIGURED)
        // Credential Manager must run on the caller's (main) context; only the network call moves to IO.
        val token = when (val r = google.requestIdToken(activityContext)) {
            is GoogleSignInClient.Result.Failure -> return AuthResult.Failure(r.error, r.detail)
            is GoogleSignInClient.Result.Success -> r.token
        }
        return guarded { completeSignIn(goTrue.signInWithIdToken("google", token.idToken, token.rawNonce), AuthProviderType.GOOGLE) }
    }

    override suspend fun refreshUser(): AuthResult<UserAccount> = guarded {
        val token = accessToken() ?: throw AuthFailure(if (session == null) AuthError.NOT_SIGNED_IN else AuthError.NETWORK)
        val json = goTrue.getUser(token)
        updateStoredUser(json)
    }

    override suspend fun updateProfile(displayName: String?, avatarUrl: String?): AuthResult<UserAccount> = guarded {
        val token = accessToken() ?: throw AuthFailure(if (session == null) AuthError.NOT_SIGNED_IN else AuthError.NETWORK)
        val metadata = buildMap {
            put("display_name", displayName?.trim()?.takeIf { it.isNotEmpty() })
            if (avatarUrl != null) put("avatar_url", avatarUrl.takeIf { it.isNotBlank() })
        }
        updateStoredUser(goTrue.updateUserMetadata(token, metadata))
    }

    private suspend fun updateStoredUser(json: kotlinx.serialization.json.JsonObject): UserAccount = sessionMutex.withLock {
        val current = session ?: throw AuthFailure(AuthError.NOT_SIGNED_IN)
        val user = json.toUserAccount(current.method)
        persist(current.copy(user = user))
        user
    }

    override suspend fun signOut(wipeLocalData: Boolean) {
        val current = sessionMutex.withLock { session } ?: run {
            if (wipeLocalData) clearLocalSession("", wipeLocalData = true)
            return
        }
        withContext(io) {
            runCatching { goTrue.logout(current.accessToken) }.onFailure { RgLog.w(TAG, "server logout failed (continuing locally)", it) }
        }
        sessionMutex.withLock { clearLocalSession(current.user.id, wipeLocalData) }
    }

    override suspend fun deleteAccount(wipeLocalData: Boolean): AuthResult<Unit> = guarded {
        val token = accessToken() ?: throw AuthFailure(if (session == null) AuthError.NOT_SIGNED_IN else AuthError.NETWORK)
        val userId = session?.user?.id ?: throw AuthFailure(AuthError.NOT_SIGNED_IN)
        listeners.forEach { it.beforeAccountDeletion(userId, token) }
        goTrue.deleteAccount(token)
        sessionMutex.withLock { clearLocalSession(userId, wipeLocalData) }
    }

    private companion object {
        const val TAG = "Auth"
        const val KEY_SESSION = "auth.session.v1"
        const val REFRESH_MARGIN_MS = 5 * 60_000L
        const val MIN_VALIDITY_MS = 60_000L
        const val RETRY_DELAY_MS = 60_000L
    }
}
