package com.ravango.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.ravango.core.designsystem.theme.RavanGoTheme
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** Screenshot variants every screen is reviewed in. */
enum class ScreenVariant(val suffix: String, val dark: Boolean, val persian: Boolean) {
    FA_LIGHT("fa-light", dark = false, persian = true),
    FA_DARK("fa-dark", dark = true, persian = true),
    EN_LIGHT("en-light", dark = false, persian = false),
}

/** Pixel 7-class phone: 412×915dp @ 420dpi (xxhdpi bucket). */
const val PHONE_QUALIFIERS = "w412dp-h915dp"
private const val DENSITY = "xxhdpi"

/**
 * Renders [content] inside the RavanGo theme and saves `build/outputs/roborazzi/<name>-<variant>.png`.
 * Call from a Robolectric test annotated with `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
 */
fun captureScreen(
    name: String,
    variant: ScreenVariant = ScreenVariant.FA_LIGHT,
    qualifiers: String = PHONE_QUALIFIERS,
    content: @Composable () -> Unit,
) {
    val locale = if (variant.persian) "fa" else "en"
    val night = if (variant.dark) "night" else "notnight"
    // Qualifier order per Android rules: locale, size, night mode, density.
    RuntimeEnvironment.setQualifiers("$locale-$qualifiers-$night-$DENSITY")
    // Formatters (Persian digits, dates, "٫") read Locale.getDefault(), which resource qualifiers don't touch.
    Locale.setDefault(Locale.forLanguageTag(locale))
    captureRoboImage(filePath = "build/outputs/roborazzi/$name-${variant.suffix}.png") {
        // reduceMotion stops infinite animations (gradient blobs, shimmer) so Compose goes idle and capture is fast.
        RavanGoTheme(darkTheme = variant.dark, reduceMotion = true) {
            CompositionLocalProvider(LocalLayoutDirection provides if (variant.persian) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                content()
            }
        }
    }
}

/** Captures [content] in every [ScreenVariant]. */
fun captureAllVariants(name: String, qualifiers: String = PHONE_QUALIFIERS, content: @Composable () -> Unit) {
    ScreenVariant.entries.forEach { captureScreen(name, it, qualifiers, content) }
}
