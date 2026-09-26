package com.ravango.feature.beauty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Entitlements
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.beauty.BeautyQuality
import com.ravango.engine.beauty.BeautyStatus
import com.ravango.engine.beauty.EyeColorSetting
import com.ravango.feature.beauty.looks.LookAvatar
import com.ravango.feature.beauty.looks.LookCatalog
import com.ravango.feature.beauty.looks.LookResolver
import com.ravango.feature.beauty.looks.LooksState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LooksScreenshotTest {

    private fun ui(activeId: String?, favourites: List<String> = emptyList(), customised: Boolean = false, filter: LookFilter = LookFilter.ALL): BeautyUiState {
        val look = LookCatalog.find(activeId)
        val state = look?.let { LookResolver.resolve(it.recipe, 80, BeautyState(), EyeColorSetting()).state } ?: BeautyState()
        return BeautyUiState(
            state = state,
            status = BeautyStatus(faceDetected = true, faceCount = 1, quality = BeautyQuality.FULL, tracking = true),
            entitlements = Entitlements(),
            tab = BeautyTab.LOOKS,
            looks = LooksState(activeId = activeId, intensity = 80, customised = customised, favourites = favourites, loaded = true),
            lookFilter = filter,
        )
    }

    @Test
    fun panelLooksActive() = captureAllVariants(name = "beauty-panel-looks-active") {
        Panel(ui("bold_glam", favourites = listOf("look:soft_glam", "look:latte")))
    }

    @Test
    fun panelLooksFavourites() = captureAllVariants(name = "beauty-panel-looks-favourites") {
        Panel(ui("latte", favourites = listOf("look:latte", "look:k_beauty", "look:arabic_glam"), customised = true, filter = LookFilter.FAVOURITES))
    }

    /** Every look's thumbnail at carousel size (72 dp) with its name and Pro badge — the thumbnail review sheet. */
    @Test
    fun thumbnailsGrid() = captureAllVariants(name = "looks-thumbnails") {
        WithDeviceLocale {
            val names = rememberLookNamer()
            Column(
                Modifier.fillMaxSize().background(RgTheme.colors.background).padding(top = 28.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LookCatalog.looks.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { look ->
                            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    LookAvatar(look, Modifier.size(72.dp).clip(CircleShape))
                                    if (look.pro) ProBadge(Modifier.align(Alignment.BottomCenter).offset(y = 8.dp), text = "PRO")
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(names(look), style = MaterialTheme.typography.labelMedium, color = RgTheme.colors.textPrimary, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                // Small size (56 dp) row to check legibility.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LookCatalog.looks.take(6).forEach { LookAvatar(it, Modifier.size(56.dp).clip(CircleShape)) }
                }
            }
        }
    }

    /** One avatar at a large size, to review the illustration itself. */
    @Test
    fun avatarLarge() = captureAllVariants(name = "looks-avatar-large") {
        Column(Modifier.fillMaxSize().background(Color(0xFF121019)), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            LookAvatar(LookCatalog.find("bold_glam")!!, Modifier.size(360.dp).clip(CircleShape))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LookAvatar(LookCatalog.find("arabic_glam")!!, Modifier.size(190.dp).clip(CircleShape))
                LookAvatar(LookCatalog.find("k_beauty")!!, Modifier.size(190.dp).clip(CircleShape))
            }
        }
    }

    @Composable
    private fun Panel(state: BeautyUiState) = WithDeviceLocale {
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

/** The harness switches resource qualifiers but not Locale.getDefault(), which the digit formatters read. */
@Composable
private fun WithDeviceLocale(content: @Composable () -> Unit) {
    java.util.Locale.setDefault(LocalConfiguration.current.locales[0])
    content()
}
