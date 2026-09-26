package com.ravango.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.designsystem.component.GradientBackground
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
class PermissionCardScreenshotTest {

    @Test
    fun rationale() = captureAllVariants("permission-rationale") {
        val context = LocalContext.current
        val requester = remember { PermissionRequester(listOf(AppPermission.CAMERA, AppPermission.MICROPHONE), context) {} }
        val fa = LocalLayoutDirection.current == LayoutDirection.Rtl
        GradientBackground {
            Box(Modifier.fillMaxSize().padding(Spacing.gutter), contentAlignment = Alignment.Center) {
                PermissionRationaleCard(
                    requester = requester,
                    title = if (fa) "دسترسی به دوربین و میکروفون" else "Camera & microphone access",
                    message = if (fa) {
                        "برای ضبط ویدیو به دوربین و میکروفون نیاز داریم. همه‌چیز روی گوشی خودتان می‌ماند."
                    } else {
                        "RavanGo needs the camera and microphone to record. Everything stays on your device."
                    },
                    icon = Icons.Rounded.Videocam,
                )
            }
        }
    }
}
