package com.ravango.feature.beauty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Entitlements
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.beauty.BeautyQuality
import com.ravango.engine.beauty.BeautyStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BeautyScreenshotTest {

    private val look = BeautyState(
        beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 45, BeautyFeature.SKIN_BRIGHTNESS to 20, BeautyFeature.EYE_SIZE to 30),
        makeup = mapOf(MakeupFeature.LIPSTICK to MakeupLayer(60, 0xFFC2185B), MakeupFeature.BLUSH to MakeupLayer(40, 0xFFF48FB1)),
    )

    private val presets = BeautyPresets(
        builtIn = listOf(
            BeautyPreset(id = "b1", name = "preset_beauty_natural", state = BeautyState(), builtIn = true),
            BeautyPreset(id = "b2", name = "preset_beauty_glam", state = look, builtIn = true),
        ),
        mine = listOf(BeautyPreset(id = "m1", name = "Studio — morning", state = look)),
        loaded = true,
    )

    private fun ui(tab: BeautyTab, entitled: Boolean = false) = BeautyUiState(
        state = look,
        status = BeautyStatus(faceDetected = true, faceCount = 1, quality = BeautyQuality.FULL, tracking = true),
        entitlements = if (entitled) Entitlements(plan = Plan.entries.last(), features = ProFeature.entries.toSet()) else Entitlements(),
        tab = tab,
        presets = presets,
        activePresetId = "m1",
    )

    @Test
    fun panelSkin() = captureAllVariants("beauty-panel-skin") { Panel(ui(BeautyTab.SKIN)) }

    @Test
    fun panelMakeup() = captureAllVariants("beauty-panel-makeup") {
        Panel(ui(BeautyTab.MAKEUP, entitled = true).copy(selected = BeautyItem.Makeup(MakeupFeature.LIPSTICK)))
    }

    @Test
    fun panelLooksLocked() = captureAllVariants("beauty-panel-looks") { Panel(ui(BeautyTab.LOOKS)) }

    @Test
    fun presetsScreen() = captureAllVariants("beauty-presets") {
        val namer = rememberPresetNamer()
        BeautyPresetsContent(
            ui = ui(BeautyTab.PRESETS),
            namer = namer,
            onBack = {},
            snackbar = remember { SnackbarHostState() },
            onRequirePro = {},
            onApply = {},
            onSaveCurrent = {},
            onRename = { _, _ -> },
            onDuplicate = { _, _ -> },
            onDelete = {},
        )
    }

    /** The panel as it appears in the camera: a dark sheet over a photo-like preview. */
    @Composable
    private fun Panel(state: BeautyUiState) {
        StudioTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF6D7A86), Color(0xFFB9A48E), Color(0xFF5E4B45)))),
            ) {
                BeautyPanelContent(ui = state, toast = null, actions = remember { BeautyPanelActions() }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}
