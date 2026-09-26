package com.ravango.feature.onboarding

import androidx.compose.runtime.Composable
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ThemeMode
import com.ravango.core.testing.captureAllVariants
import org.junit.Test
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Composable
private fun Onboarding(state: OnboardingUiState) = OnboardingContent(
    state = state,
    onPageSettled = {},
    onLanguage = {},
    onTheme = {},
    onAnalytics = {},
    onCrashReports = {},
    onToggleFocus = {},
    onFinish = {},
)

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
class OnboardingScreenshotTest {

    @Test
    fun language() = captureAllVariants("onboarding-1-language") { SyncLocale(); Onboarding(OnboardingUiState(page = 0, language = AppLanguage.PERSIAN)) }

    @Test
    fun prompter() = captureAllVariants("onboarding-2-prompter") { SyncLocale(); Onboarding(OnboardingUiState(page = 1)) }

    @Test
    fun beauty() = captureAllVariants("onboarding-3-beauty") { SyncLocale(); Onboarding(OnboardingUiState(page = 2)) }

    @Test
    fun privacy() = captureAllVariants("onboarding-4-privacy") { SyncLocale();
        Onboarding(
            OnboardingUiState(
                page = 3,
                themeMode = ThemeMode.SYSTEM,
                analyticsConsent = true,
                focus = setOf(CreatorFocus.REELS, CreatorFocus.PODCAST),
            ),
        )
    }

    @Test
    fun privacyFull() = captureAllVariants("onboarding-4-privacy-full", qualifiers = "w412dp-h1500dp") { SyncLocale();
        Onboarding(OnboardingUiState(page = 3, focus = setOf(CreatorFocus.YOUTUBE)))
    }

    @Test
    fun languageSmall() = captureAllVariants("onboarding-1-language-small", qualifiers = "w360dp-h740dp") { SyncLocale(); Onboarding(OnboardingUiState(page = 0)) }
}
