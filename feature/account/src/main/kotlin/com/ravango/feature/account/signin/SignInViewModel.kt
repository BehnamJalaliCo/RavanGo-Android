package com.ravango.feature.account.signin

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.model.Clock
import com.ravango.platform.auth.AuthError
import com.ravango.platform.auth.AuthRepository
import com.ravango.platform.auth.AuthResult
import com.ravango.platform.auth.EmailAddresses
import com.ravango.platform.auth.OtpCodes
import com.ravango.platform.auth.Passwords
import com.ravango.platform.auth.PhoneNumbers
import com.ravango.platform.auth.SignUpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInMethod { EMAIL_CODE, PASSWORD, PHONE }

data class SignInUiState(
    val configured: Boolean = false,
    val googleConfigured: Boolean = false,
    val method: SignInMethod = SignInMethod.EMAIL_CODE,
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val displayName: String = "",
    val creatingAccount: Boolean = false,
    /** Where the one-time code was sent (email or E.164 phone); non-null = code entry step. */
    val codeSentTo: String? = null,
    val code: String = "",
    val resendInSeconds: Int = 0,
    val loading: Boolean = false,
    val error: AuthError? = null,
    val info: SignInInfo? = null,
) {
    val emailValid: Boolean get() = EmailAddresses.normalize(email) != null
    val phoneValid: Boolean get() = PhoneNumbers.normalize(phone) != null
    val passwordValid: Boolean get() = if (creatingAccount) Passwords.isAcceptable(password) else password.isNotEmpty()
}

enum class SignInInfo { CONFIRMATION_EMAIL_SENT, RESET_EMAIL_SENT }

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SignInUiState(
            configured = auth.isConfigured,
            googleConfigured = auth.isGoogleConfigured,
            method = savedState.get<String>(KEY_METHOD)?.let { runCatching { SignInMethod.valueOf(it) }.getOrNull() } ?: SignInMethod.EMAIL_CODE,
            email = savedState[KEY_EMAIL] ?: "",
            phone = savedState[KEY_PHONE] ?: "",
            codeSentTo = savedState[KEY_SENT_TO],
        ),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private val _signedIn = Channel<Unit>(Channel.CONFLATED)
    /** Emits once the user is signed in; the screen then closes. */
    val signedIn = _signedIn.receiveAsFlow()

    private var timerJob: Job? = null

    init {
        savedState.get<Long>(KEY_RESEND_AT)?.let(::startTimer)
    }

    fun setMethod(method: SignInMethod) {
        savedState[KEY_METHOD] = method.name
        _state.update { it.copy(method = method, codeSentTo = null, code = "", error = null, info = null) }
        savedState[KEY_SENT_TO] = null
    }

    fun setEmail(value: String) {
        savedState[KEY_EMAIL] = value
        _state.update { it.copy(email = value.take(254), error = null) }
    }

    fun setPhone(value: String) {
        savedState[KEY_PHONE] = value
        _state.update { it.copy(phone = value.take(24), error = null) }
    }

    fun setPassword(value: String) = _state.update { it.copy(password = value.take(128), error = null) }
    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value.take(60)) }
    fun toggleCreateAccount() = _state.update { it.copy(creatingAccount = !it.creatingAccount, error = null, info = null) }

    fun setCode(value: String) {
        val digits = OtpCodes.normalize(value).take(CODE_LENGTH)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == CODE_LENGTH) verifyCode()
    }

    fun editDestination() {
        savedState[KEY_SENT_TO] = null
        _state.update { it.copy(codeSentTo = null, code = "", error = null) }
    }

    fun sendCode() = launch {
        val s = _state.value
        val result = if (s.method == SignInMethod.PHONE) auth.sendPhoneOtp(s.phone) else auth.sendEmailOtp(s.email)
        when (result) {
            is AuthResult.Success -> {
                savedState[KEY_SENT_TO] = result.value
                _state.update { it.copy(codeSentTo = result.value, code = "") }
                val resendAt = clock.now() + RESEND_SECONDS * 1000L
                savedState[KEY_RESEND_AT] = resendAt
                startTimer(resendAt)
            }
            is AuthResult.Failure -> _state.update { it.copy(error = result.error) }
        }
    }

    private fun verifyCode() = launch {
        val s = _state.value
        val destination = s.codeSentTo ?: return@launch
        val result = if (s.method == SignInMethod.PHONE) auth.verifyPhoneOtp(destination, s.code) else auth.verifyEmailOtp(destination, s.code)
        when (result) {
            is AuthResult.Success -> finish()
            is AuthResult.Failure -> _state.update { it.copy(error = result.error, code = "") }
        }
    }

    fun submitPassword() = launch {
        val s = _state.value
        if (s.creatingAccount) {
            when (val r = auth.signUpWithPassword(s.email, s.password, s.displayName.takeIf { it.isNotBlank() })) {
                is AuthResult.Success -> when (r.value) {
                    is SignUpResult.SignedIn -> finish()
                    is SignUpResult.ConfirmationRequired -> _state.update { it.copy(info = SignInInfo.CONFIRMATION_EMAIL_SENT, creatingAccount = false, password = "") }
                }
                is AuthResult.Failure -> _state.update { it.copy(error = r.error) }
            }
        } else {
            when (val r = auth.signInWithPassword(s.email, s.password)) {
                is AuthResult.Success -> finish()
                is AuthResult.Failure -> _state.update { it.copy(error = r.error) }
            }
        }
    }

    fun resetPassword() = launch {
        when (val r = auth.sendPasswordReset(_state.value.email)) {
            is AuthResult.Success -> _state.update { it.copy(info = SignInInfo.RESET_EMAIL_SENT) }
            is AuthResult.Failure -> _state.update { it.copy(error = r.error) }
        }
    }

    /** [activityContext] must be an Activity: Credential Manager shows the Google account picker. */
    fun signInWithGoogle(activityContext: Context) = launch {
        when (val r = auth.signInWithGoogle(activityContext)) {
            is AuthResult.Success -> finish()
            is AuthResult.Failure -> if (r.error != AuthError.CANCELLED) _state.update { it.copy(error = r.error) }
        }
    }

    private suspend fun finish() {
        _state.update { it.copy(password = "", code = "") }
        _signedIn.send(Unit)
    }

    private fun startTimer(resendAt: Long) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val left = ((resendAt - clock.now() + 999) / 1000).toInt().coerceAtLeast(0)
                _state.update { it.copy(resendInSeconds = left) }
                if (left == 0) break
                delay(1_000)
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, info = null) }
            try {
                block()
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
        private const val RESEND_SECONDS = 60
        private const val KEY_METHOD = "method"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
        private const val KEY_SENT_TO = "sentTo"
        private const val KEY_RESEND_AT = "resendAt"
    }
}
