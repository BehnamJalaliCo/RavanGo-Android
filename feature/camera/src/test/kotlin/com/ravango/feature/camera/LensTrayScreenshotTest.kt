package com.ravango.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.Entitlements
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.effects.SpriteAtlas
import com.ravango.engine.camera.RecordingStatus
import com.ravango.feature.beauty.looks.LookCatalog
import com.ravango.feature.beauty.looks.LooksState
import com.ravango.feature.camera.ui.LensCarousel
import com.ravango.feature.camera.ui.LensTrayActions
import com.ravango.feature.camera.ui.LensTrayState
import com.ravango.feature.camera.ui.RecordButton
import com.ravango.feature.camera.ui.ShutterStyle
import com.ravango.feature.camera.ui.TrayCategory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Snapchat-style lens tray with complete makeup looks (category tabs, look strength, favourites). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LensTrayScreenshotTest {

    private val atlas by lazy { SpriteAtlas.draw().asImageBitmap() }

    private val looks = LooksState(
        activeId = "bold_glam",
        intensity = 85,
        favourites = listOf("look:bold_glam", "look:latte", "lens:sparkles", "look:arabic_glam"),
        recents = listOf("look:bold_glam", "lens:cat", "look:k_beauty", "filter:warm", "look:soft_glam"),
        loaded = true,
    )

    /** The whole studio with the tray open on «For you» and Bold Glam applied under the shutter ring. */
    @Test
    fun studioLooksForYou() = captureAllVariants(name = "camera-studio-looks") {
        WithDeviceLocale {
            StudioFrame {
                CameraStudioContent(
                    state = CameraStudioScreenshotSamples.state().copy(lensTrayOpen = true, effects = EffectsState(lens = Lens.SPARKLES)),
                    chrome = StudioChrome(),
                    actions = remember { StudioActions() },
                    level = remember { mutableStateOf(AudioLevel(peakDbfs = -12f, rmsDbfs = -24f, clipping = false)) },
                    clipping = remember { mutableStateOf(false) },
                    recordingClock = remember { mutableStateOf(RecordingStatus()) },
                    atlas = atlas,
                    snackbar = remember { SnackbarHostState() },
                    surface = { PreviewPlaceholder() },
                    tray = LensTrayState(looks = looks),
                    trayActions = remember { LensTrayActions() },
                )
            }
        }
    }

    @Test
    fun trayMakeupLocked() = captureAllVariants(name = "camera-tray-makeup-pro") {
        Tray(LensTrayState(looks = looks.copy(activeId = "arabic_glam")), TrayCategory.MAKEUP)
    }

    @Test
    fun trayBeauty() = captureAllVariants(name = "camera-tray-beauty") {
        Tray(LensTrayState(looks = looks.copy(activeId = "k_beauty", intensity = 60)), TrayCategory.BEAUTY)
    }

    @Test
    fun trayFavourites() = captureAllVariants(name = "camera-tray-favorites") {
        Tray(LensTrayState(looks = looks.copy(activeId = "latte")), TrayCategory.FAVORITES)
    }

    @Test
    fun trayFilters() = captureAllVariants(name = "camera-tray-filters") {
        Tray(LensTrayState(looks = looks, filter = LiveFilter.WARM, filterIntensity = 70), TrayCategory.FILTERS)
    }

    @Test
    fun trayRecentsEmpty() = captureAllVariants(name = "camera-tray-recents-empty") {
        Tray(LensTrayState(looks = looks.copy(recents = emptyList(), activeId = null)), TrayCategory.RECENTS)
    }

    @Composable
    private fun Tray(tray: LensTrayState, category: TrayCategory, entitlements: Entitlements = Entitlements()) = WithDeviceLocale {
        StudioFrame {
            Box(Modifier.fillMaxSize()) {
                PreviewPlaceholder()
                LensCarousel(
                    applied = null,
                    entitlements = entitlements,
                    atlas = atlas,
                    onFocus = {},
                    tray = tray,
                    initialCategory = category,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                ) {
                    RecordButton(recording = false, busy = false, countingDown = false, style = ShutterStyle.LENS, onClick = {})
                }
            }
        }
    }

    @Suppress("unused")
    private val catalogSize = LookCatalog.looks.size
}

@Composable
private fun WithDeviceLocale(content: @Composable () -> Unit) {
    java.util.Locale.setDefault(LocalConfiguration.current.locales[0])
    content()
}
