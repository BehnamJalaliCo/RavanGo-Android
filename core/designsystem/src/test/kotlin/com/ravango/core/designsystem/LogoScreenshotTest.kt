package com.ravango.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.RavanGoLogo
import com.ravango.core.designsystem.component.RavanGoLogoStyle
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.testing.captureAllVariants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LogoScreenshotTest {
    @Test
    fun lockups() = captureAllVariants("designsystem-logo") {
        Column(
            Modifier.fillMaxSize().background(RgTheme.colors.background).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
        ) {
            RavanGoLogo(style = RavanGoLogoStyle.STACKED, height = 120.dp)
            RavanGoLogo(style = RavanGoLogoStyle.HORIZONTAL, height = 40.dp)
            RavanGoLogo(height = 24.dp)
        }
    }
}
