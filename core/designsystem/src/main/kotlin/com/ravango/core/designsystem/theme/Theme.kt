package com.ravango.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.ravango.core.model.ThemeMode

val LocalReduceMotion = staticCompositionLocalOf { false }

private fun schemeFrom(c: RgColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.textPrimary,
        secondary = Palette.Rose300,
        onSecondary = Palette.Ink950,
        secondaryContainer = c.pastelRose,
        onSecondaryContainer = c.textPrimary,
        tertiary = Palette.Mint400,
        onTertiary = Palette.Ink950,
        tertiaryContainer = c.pastelMint,
        onTertiaryContainer = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceMuted,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.backgroundElevated,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceMuted,
        surfaceContainerHighest = Palette.Ink700,
        outline = c.outlineStrong,
        outlineVariant = c.outline,
        error = c.danger,
        onError = Palette.Ink950,
        scrim = c.scrim,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = Palette.Lavender700,
        secondary = Palette.Rose500,
        onSecondary = androidx.compose.ui.graphics.Color.White,
        secondaryContainer = c.pastelRose,
        onSecondaryContainer = c.textPrimary,
        tertiary = Palette.Mint500,
        onTertiary = androidx.compose.ui.graphics.Color.White,
        tertiaryContainer = c.pastelMint,
        onTertiaryContainer = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceMuted,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
        surfaceContainerLow = c.backgroundElevated,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceMuted,
        surfaceContainerHighest = Palette.Ink100,
        outline = c.outlineStrong,
        outlineVariant = c.outline,
        error = c.danger,
        onError = androidx.compose.ui.graphics.Color.White,
        scrim = c.scrim,
    )
}

@Composable
fun RavanGoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    hapticsEnabled: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    RavanGoTheme(darkTheme = dark, hapticsEnabled = hapticsEnabled, reduceMotion = reduceMotion, content = content)
}

@Composable
fun RavanGoTheme(
    darkTheme: Boolean,
    hapticsEnabled: Boolean = true,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkRgColors else LightRgColors
    CompositionLocalProvider(
        LocalRgColors provides colors,
        LocalHapticsEnabled provides hapticsEnabled,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = schemeFrom(colors),
            typography = RgTypography,
            shapes = RgShapes,
            content = content,
        )
    }
}

/**
 * Always-dark theme for capture surfaces (camera, teleprompter, editor) regardless of the app theme. Haptics and
 * reduce-motion are inherited from the enclosing [RavanGoTheme] so the user's accessibility settings carry over.
 */
@Composable
fun StudioTheme(
    hapticsEnabled: Boolean = LocalHapticsEnabled.current,
    reduceMotion: Boolean = LocalReduceMotion.current,
    content: @Composable () -> Unit,
) {
    RavanGoTheme(darkTheme = true, hapticsEnabled = hapticsEnabled, reduceMotion = reduceMotion, content = content)
}

/** Accessor: `RgTheme.colors.accent`. */
object RgTheme {
    val colors: RgColors
        @Composable @ReadOnlyComposable get() = LocalRgColors.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}
