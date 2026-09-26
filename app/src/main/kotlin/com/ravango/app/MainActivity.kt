package com.ravango.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.theme.RavanGoTheme
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ThemeMode
import com.ravango.core.ui.HardwareKeys
import com.ravango.app.navigation.RavanGoApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Text shared into the app (ACTION_SEND) — opened as a new script. */
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        handleIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? MainUiState.Ready ?: return@setContent
            val prefs = ready.preferences
            applyLanguage(prefs.language)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            )
            RavanGoTheme(darkTheme = dark, hapticsEnabled = prefs.hapticsEnabled, reduceMotion = prefs.reduceMotion) {
                RavanGoApp(
                    onboardingCompleted = prefs.onboardingCompleted,
                    sharedText = sharedText,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    /** Applies the per-app locale; AppCompat persists it and recreates the activity when it changes. */
    private fun applyLanguage(language: AppLanguage) {
        val desired = language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Teleprompter / camera screens can consume volume keys and Bluetooth remote keys.
        if (HardwareKeys.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
