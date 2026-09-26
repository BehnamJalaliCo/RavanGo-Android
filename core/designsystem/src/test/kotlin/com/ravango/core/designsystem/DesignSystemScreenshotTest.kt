package com.ravango.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.GradientBackground
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTopBar
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.testing.captureAllVariants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignSystemScreenshotTest {

    @Test
    fun gallery() = captureAllVariants("designsystem-gallery") { Gallery() }
}

@Composable
private fun Gallery() {
    GradientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RgTopBar("روان‌گو RavanGo", subtitle = "Design system", actions = { RgIconButton(Icons.Rounded.Settings, null, {}) })
            Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                RgPrimaryButton("ضبط ویدیو / Record", {}, icon = Icons.Rounded.Videocam, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RgSecondaryButton("ثانویه", {})
                    RgChip("انتخاب", selected = true, onClick = {})
                    RgChip("چیپ", selected = false, onClick = {})
                    ProBadge()
                }
                RgSegmentedControl(listOf("9:16", "16:9", "1:1"), "9:16", {}, { it })
                RgCard(Modifier.fillMaxWidth()) {
                    Text("کارت / Card", style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
                    RgLabeledSlider("صاف کردن پوست", 45f, {})
                }
                GlassSurface(Modifier.fillMaxWidth()) { Text("Glass surface — شیشه‌ای", color = RgTheme.colors.textPrimary) }
            }
            RgGroup(title = "تنظیمات") {
                RgListItem("زیرنویس خودکار", subtitle = "Auto captions", icon = Icons.Rounded.Subtitles, trailing = { RgSwitch(true, {}) })
            }
        }
    }
}
