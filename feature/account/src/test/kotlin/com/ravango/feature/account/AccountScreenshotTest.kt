package com.ravango.feature.account

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.Entitlements
import com.ravango.core.model.Plan
import com.ravango.core.model.UserAccount
import com.ravango.core.model.UserPreferences
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.SyncPhase
import com.ravango.core.testing.captureAllVariants
import com.ravango.feature.account.about.AboutContent
import com.ravango.feature.account.about.TermsContent
import com.ravango.feature.account.account.AccountContent
import com.ravango.feature.account.account.AccountUiState
import com.ravango.feature.account.cloud.CloudContent
import com.ravango.feature.account.privacy.PrivacyContent
import com.ravango.feature.account.privacy.PrivacyUiState
import com.ravango.feature.account.settings.ExportQuality
import com.ravango.feature.account.settings.SettingsActions
import com.ravango.feature.account.settings.SettingsContent
import com.ravango.feature.account.settings.SettingsUiState
import com.ravango.feature.account.signin.SignInActions
import com.ravango.feature.account.signin.SignInContent
import com.ravango.feature.account.signin.SignInMethod
import com.ravango.feature.account.signin.SignInUiState
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.SpeechProviderId
import com.ravango.core.model.ThemeMode
import com.ravango.platform.cloud.CloudStatus
import org.junit.Test
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NOW = System.currentTimeMillis()

@Composable
private fun t(fa: String, en: String) = if (LocalLayoutDirection.current == LayoutDirection.Rtl) fa else en

@Composable
private fun Account(state: AccountUiState) = AccountContent(
    state = state, snackbar = null,
    onBack = {}, onSignIn = {}, onPaywall = {}, onCloud = {}, onSettings = {}, onPrivacy = {}, onTerms = {}, onAbout = {},
    onSubscription = {}, onContactSupport = {}, onEditProfile = {}, onSignOut = {}, onDelete = {},
)

private val cloudOn = CloudStatus(
    configured = true, signedIn = true, syncEnabled = true, wifiOnly = true, backupMedia = true, canSyncProjects = true, canBackupMedia = true,
    phase = SyncPhase.IDLE, lastSyncedAt = NOW - 5 * 60_000, backedUpBytes = 3_400_000_000L, quotaBytes = 10_000_000_000L,
)

private object NoSettings : SettingsActions {
    override fun setLanguage(language: AppLanguage) = Unit
    override fun setTheme(mode: ThemeMode) = Unit
    override fun setHaptics(enabled: Boolean) = Unit
    override fun setReduceMotion(enabled: Boolean) = Unit
    override fun setSaveToGallery(enabled: Boolean) = Unit
    override fun setKeepScreenOn(enabled: Boolean) = Unit
    override fun setExportQuality(quality: ExportQuality) = Unit
    override fun setHevc(enabled: Boolean) = Unit
    override fun clearCache() = Unit
    override fun setTextProvider(provider: AiProviderId) = Unit
    override fun setSpeechProvider(provider: SpeechProviderId) = Unit
    override fun setConsent(enabled: Boolean) = Unit
    override fun setCustomEndpoint(baseUrl: String, model: String) = Unit
    override fun saveKey(provider: AiProviderId, key: String) = Unit
    override fun removeKey(provider: AiProviderId) = Unit
    override fun saveSpeechKey(key: String) = Unit
    override fun removeSpeechKey() = Unit
}

private object NoSignIn : SignInActions {
    override fun setMethod(method: SignInMethod) = Unit
    override fun setEmail(value: String) = Unit
    override fun setPhone(value: String) = Unit
    override fun setPassword(value: String) = Unit
    override fun setDisplayName(value: String) = Unit
    override fun toggleCreateAccount() = Unit
    override fun setCode(value: String) = Unit
    override fun editDestination() = Unit
    override fun sendCode() = Unit
    override fun submitPassword() = Unit
    override fun resetPassword() = Unit
}

/**
 * Robolectric changes the resource locale per variant but not [Locale.getDefault], which the app's number/byte
 * formatters use (on a device the per-app language updates both). Keep them in sync so digits render as on device.
 */
@Composable
private fun SyncLocale() {
    Locale.setDefault(LocalConfiguration.current.locales[0])
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AccountScreenshotTest {

    @Test
    fun accountSignedInPro() = captureAllVariants("account-signed-in", qualifiers = "w412dp-h1400dp") { SyncLocale();
        Account(
            AccountUiState(
                auth = AuthState.SignedIn(UserAccount(id = "u1", email = "sara.ahmadi@example.com", displayName = t("سارا احمدی", "Sara Ahmadi"), provider = AuthProviderType.EMAIL_OTP)),
                cloudConfigured = true,
                entitlements = Entitlements(plan = Plan.PRO, aiCreditsPerMonth = 500, aiCreditsRemaining = 312, cloudQuotaBytes = 10_000_000_000L),
                cloud = cloudOn,
                localMediaBytes = 2_300_000_000L,
                supportEmail = "support@ravango.app",
            ),
        )
    }

    @Test
    fun accountGuestFree() = captureAllVariants("account-guest") { SyncLocale();
        Account(AccountUiState(auth = AuthState.Guest, cloudConfigured = true, entitlements = Entitlements(aiCreditsRemaining = 7), localMediaBytes = 640_000_000L))
    }

    @Test
    fun settings() = captureAllVariants("settings", qualifiers = "w412dp-h2600dp") { SyncLocale();
        SettingsContent(
            SettingsUiState(prefs = UserPreferences(language = AppLanguage.PERSIAN, themeMode = ThemeMode.SYSTEM), mediaBytes = 2_300_000_000L, cacheBytes = 184_000_000L),
            snackbar = null,
            onBack = {},
            actions = NoSettings,
        )
    }

    @Test
    fun settingsTop() = captureAllVariants("settings-top") { SyncLocale();
        SettingsContent(SettingsUiState(mediaBytes = 2_300_000_000L, cacheBytes = 184_000_000L), snackbar = null, onBack = {}, actions = NoSettings)
    }

    @Test
    fun signInEmail() = captureAllVariants("signin-email") { SyncLocale();
        SignInContent(SignInUiState(configured = true, googleConfigured = true, email = "sara@example.com"), NoSignIn, onBack = {}, onGoogle = {})
    }

    @Test
    fun signInCode() = captureAllVariants("signin-code") { SyncLocale();
        SignInContent(SignInUiState(configured = true, codeSentTo = "sara@example.com", code = "482", resendInSeconds = 42), NoSignIn, onBack = {}, onGoogle = {})
    }

    @Test
    fun signInPassword() = captureAllVariants("signin-password") { SyncLocale();
        SignInContent(
            SignInUiState(configured = true, method = SignInMethod.PASSWORD, creatingAccount = true, email = "sara@", password = "secret"),
            NoSignIn, onBack = {}, onGoogle = {},
        )
    }

    @Test
    fun signInNotConfigured() = captureAllVariants("signin-unconfigured") { SyncLocale();
        SignInContent(SignInUiState(configured = false), NoSignIn, onBack = {}, onGoogle = {})
    }

    @Test
    fun cloud() = captureAllVariants("cloud", qualifiers = "w412dp-h1200dp") { SyncLocale();
        CloudContent(cloudOn, onBack = {}, onSignIn = {}, onRequirePro = {}, onSyncEnabled = {}, onWifiOnly = {}, onBackupMedia = {}, onSyncNow = {})
    }

    @Test
    fun cloudSignedOut() = captureAllVariants("cloud-signed-out") { SyncLocale();
        CloudContent(CloudStatus(configured = true), onBack = {}, onSignIn = {}, onRequirePro = {}, onSyncEnabled = {}, onWifiOnly = {}, onBackupMedia = {}, onSyncNow = {})
    }

    @Test
    fun privacy() = captureAllVariants("privacy") { SyncLocale();
        PrivacyContent(
            PrivacyUiState(analyticsConsent = false, crashReportsConsent = true, signedIn = true, privacyUrl = "https://ravango.app/privacy"),
            snackbar = null, onBack = {}, onAnalytics = {}, onCrashReports = {}, onExport = {}, onDeleteLocal = {}, onDeleteAccount = {}, onOpenOnline = {},
        )
    }

    @Test
    fun about() = captureAllVariants("about", qualifiers = "w412dp-h1400dp") { SyncLocale();
        AboutContent(
            versionName = "1.1.0", versionCode = "2", supportEmail = "support@ravango.app", testerActive = false,
            onBack = {}, onVersionTap = {}, onLicenses = {}, onPrivacy = {}, onTerms = {}, onContactSupport = {}, onDisableTester = {},
            crashReportCount = 2,
        )
    }

    private val sampleCrash = com.ravango.core.common.diagnostics.CrashReport(
        id = "crash-1", kind = com.ravango.core.common.diagnostics.CrashKind.NATIVE, timeMillis = 1_790_000_000_000L,
        summary = "Native crash · SIGSEGV · in libmediapipe_tasks_jni.so",
        body = "RavanGo crash report\nKind: native crash\n\n== Device & app ==\nApp: RavanGo 1.1.0 (2)\nDevice: samsung SM-A546E\n" +
            "Android: 14 (API 34)\n\n== Details ==\nreason: CRASH_NATIVE\n-- tombstone (readable strings) --\nSIGSEGV\n" +
            "/data/app/~~x/com.ravango.app/lib/arm64/libmediapipe_tasks_jni.so\n",
    )

    @Test
    fun crashPrompt() = captureAllVariants("crash_prompt") {
        SyncLocale()
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            com.ravango.feature.account.diagnostics.CrashReportPromptCard(sampleCrash, earlier = 1, onView = {}, onShare = {}, onDismiss = {})
        }
    }

    @Test
    fun crashViewer() = captureAllVariants("crash_viewer") {
        SyncLocale()
        com.ravango.feature.account.diagnostics.CrashReportViewerContent(sampleCrash, onShare = {}, onCopy = {})
    }

    @Test
    fun terms() = captureAllVariants("terms") { SyncLocale(); TermsContent(url = "https://ravango.app/terms", onBack = {}, onOpenOnline = {}) }

    @Test
    fun smallPhoneAccount() = captureAllVariants("account-small", qualifiers = "w360dp-h740dp") { SyncLocale();
        Account(AccountUiState(auth = AuthState.Guest, cloudConfigured = true, entitlements = Entitlements(aiCreditsRemaining = 7)))
    }
}
