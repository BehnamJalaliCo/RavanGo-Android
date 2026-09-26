package com.ravango.feature.teleprompter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.RavanGoTheme
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.testing.PHONE_QUALIFIERS
import com.ravango.core.testing.captureAllVariants
import com.ravango.core.testing.ScreenVariant
import com.ravango.core.testing.captureScreen
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.teleprompter.floating.FloatingCommand
import com.ravango.feature.teleprompter.floating.FloatingHost
import com.ravango.feature.teleprompter.floating.FloatingPrompterWindow
import com.ravango.feature.teleprompter.input.VolumeKeyMode
import com.ravango.feature.teleprompter.player.PrompterStage
import com.ravango.feature.teleprompter.settings.PrompterSettingsActions
import com.ravango.feature.teleprompter.settings.PrompterSettingsContent
import com.ravango.feature.teleprompter.settings.PrompterSettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TeleprompterScreenshotTest {

    private val persianBody = """
        ## مقدمه
        سلام دوستان! امروز می‌خواهم دربارهٔ ==سه عادت ساده== صحبت کنم که بهره‌وری‌ام را دو برابر کرد. [مکث]
        اولین عادت، **برنامه‌ریزی شب قبل** است. فقط پنج دقیقه قبل از خواب، سه کار مهم فردا را روی کاغذ بنویسید. [[لبخند بزن]]
        ## عادت دوم
        دومین عادت این است که صبح‌ها قبل از چک کردن گوشی، ده دقیقه پیاده‌روی کنم. این کار ذهنم را آرام می‌کند و انرژی‌ام را بالا می‌برد.
        ## عادت سوم
        و اما سومین عادت: هر نود دقیقه یک استراحت کوتاه. باور کنید تمرکزتان چند برابر می‌شود.
    """.trimIndent()

    private val englishBody = """
        ## Hook
        Most people film their videos three times before they get one good take. Here's how I fixed that. [pause]
        **Step one**: write the script like you talk, not like you write. Read it out loud once before you record.
        ## Step two
        Put the ==key sentence== at the eye line, look into the lens and just talk to one person.
    """.trimIndent()

    private val script = Script(id = "s1", title = "سه عادت ساده برای بهره‌وری بیشتر", body = persianBody, createdAt = 0, updatedAt = 0)
    private val englishScript = Script(id = "s2", title = "How I film in one take", body = englishBody, createdAt = 0, updatedAt = 0)

    @Test fun prompterControls() = prompter("prompter-controls", script, controls = true)
    @Test fun prompterReading() = prompter("prompter-reading", script, controls = false)
    @Test fun prompterEnglish() = prompter("prompter-english", englishScript, controls = true)
    @Test fun prompterResume() = prompter("prompter-resume", script, controls = true, resume = true)
    @Test fun prompterSmallPhone() = prompter("prompter-small", script, controls = true, qualifiers = SMALL_PHONE)

    @Test fun settings() = captureAllVariants("prompter-settings") {
        WithDeviceLocale { PrompterSettingsContent(settingsState, NoActions, onBack = {}, livePreview = false) }
    }

    /** Tall canvas so every settings group is reviewed, not just the first screen. */
    @Test fun settingsFull() = captureAllVariants("prompter-settings-full", "w412dp-h3000dp") {
        WithDeviceLocale { PrompterSettingsContent(settingsState, NoActions, onBack = {}, livePreview = false) }
    }

    @Test fun settingsSmall() = captureAllVariants("prompter-settings-small", SMALL_PHONE) {
        WithDeviceLocale { PrompterSettingsContent(settingsState, NoActions, onBack = {}, livePreview = false) }
    }

    @Test fun floating() = studio("prompter-floating") {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF3C4A5E), Color(0xFF7A6A5A), Color(0xFF2B2B33)))),
        ) {
            Studio {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 64.dp).height(330.dp)) {
                    FloatingPrompterWindow(FakeHost(script))
                }
            }
        }
    }

    private val settingsState = PrompterSettingsUiState(
        loading = false,
        settings = TeleprompterSettings(),
        scriptTitle = "سه عادت ساده برای بهره‌وری بیشتر",
        scriptBody = persianBody,
        scriptOnly = true,
        presets = listOf(
            TeleprompterPreset(id = "p1", name = "preset_prompter_standard", settings = TeleprompterSettings(), builtIn = true),
            TeleprompterPreset(id = "p2", name = "preset_prompter_large", settings = TeleprompterSettings(fontSizeSp = 48f), builtIn = true),
            TeleprompterPreset(id = "p3", name = "preset_prompter_camera", settings = TeleprompterSettings(fontSizeSp = 28f), builtIn = true),
            TeleprompterPreset(id = "p4", name = "استودیوی خانه", settings = TeleprompterSettings(fontSizeSp = 40f)),
        ),
        volumeKeyMode = VolumeKeyMode.SPEED,
    )

    private fun prompter(name: String, s: Script, controls: Boolean, resume: Boolean = false, qualifiers: String = PHONE_QUALIFIERS) =
        studio(name, qualifiers) {
            Studio {
                val settings = TeleprompterSettings()
                val offset = if (controls) 0 else 60
                val controller = rememberPrompterController(s.body, settings, offset)
                val snapshot by controller.snapshot.collectAsState()
                PrompterStage(
                    title = s.title, body = s.body, settings = settings, startOffset = offset, controller = controller,
                    snapshot = snapshot.copy(progress = 0.34f, elapsedMs = 28_000, remainingMs = 54_000), hasSections = true,
                    controlsVisible = controls, rotationLocked = false, maxBrightness = true, floatingIsPro = false, showResume = resume,
                    onPoke = {}, onFontSizeChange = {}, onBack = {}, onOpenSettings = {}, onSettings = {}, onToggleRotationLock = {},
                    onToggleBrightness = {}, onSections = {}, onStartPoint = {}, onFloating = {}, onRecord = {}, onResume = {}, onFromStart = {},
                )
            }
        }

    /** Studio screens are always dark: capture the Persian (RTL) and English (LTR) variants only. */
    private fun studio(name: String, qualifiers: String = PHONE_QUALIFIERS, content: @Composable () -> Unit) {
        listOf(ScreenVariant.FA_DARK, ScreenVariant.EN_LIGHT).forEach { v -> captureScreen(name, v, qualifiers, content) }
    }

    /** Always-dark studio surface, keeping reduce-motion on for deterministic captures. */
    @Composable
    private fun Studio(content: @Composable () -> Unit) = RavanGoTheme(darkTheme = true, reduceMotion = true) { WithDeviceLocale(content) }

    private object NoActions : PrompterSettingsActions {
        override fun update(transform: (TeleprompterSettings) -> TeleprompterSettings) = Unit
        override fun setScriptOnly(only: Boolean) = Unit
        override fun applyPreset(preset: TeleprompterPreset) = Unit
        override fun resetToDefaults() = Unit
        override fun savePreset(name: String) = Unit
        override fun deletePreset(preset: TeleprompterPreset) = Unit
        override fun setVolumeKeyMode(mode: VolumeKeyMode) = Unit
    }

    private class FakeHost(s: Script) : FloatingHost {
        override val script = MutableStateFlow<Script?>(s)
        override val settings = MutableStateFlow<TeleprompterSettings?>(TeleprompterSettings())
        override val commands = emptyFlow<FloatingCommand>()
        override fun moveBy(dx: Float, dy: Float) = Unit
        override fun resizeBy(dw: Float, dh: Float) = Unit
        override fun onPlayingChanged(playing: Boolean) = Unit
        override fun saveReadingPosition(charOffset: Int) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val SMALL_PHONE = "w360dp-h740dp"
    }
}
