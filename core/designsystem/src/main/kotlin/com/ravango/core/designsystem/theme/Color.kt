package com.ravango.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Raw pastel palette. Screens should use semantic tokens ([RgColors] / MaterialTheme.colorScheme), not these. */
object Palette {
    val Lavender50 = Color(0xFFF6F4FF)
    val Lavender100 = Color(0xFFECE8FF)
    val Lavender200 = Color(0xFFD9D1FF)
    val Lavender300 = Color(0xFFBFB2FF)
    val Lavender400 = Color(0xFFA394FB)
    val Lavender500 = Color(0xFF8B7CF6)
    val Lavender600 = Color(0xFF7160E8)
    val Lavender700 = Color(0xFF5A48CC)

    val Rose200 = Color(0xFFFFD3DF)
    val Rose300 = Color(0xFFFFB3C7)
    val Rose400 = Color(0xFFFF93AF)
    val Rose500 = Color(0xFFF7718F)

    val Peach200 = Color(0xFFFFE1CC)
    val Peach300 = Color(0xFFFFC9A3)
    val Peach400 = Color(0xFFFFAE7A)

    val Mint200 = Color(0xFFCDF5EC)
    val Mint300 = Color(0xFFA3EAD9)
    val Mint400 = Color(0xFF6FD9C0)
    val Mint500 = Color(0xFF3CC4A4)

    val Sky200 = Color(0xFFD3E8FF)
    val Sky300 = Color(0xFFABD2FF)
    val Sky400 = Color(0xFF7DB8FF)

    val Butter300 = Color(0xFFFFE7A3)
    val Butter400 = Color(0xFFFFD166)

    val Ink950 = Color(0xFF0E0C16)
    val Ink900 = Color(0xFF15131F)
    val Ink850 = Color(0xFF1B1928)
    val Ink800 = Color(0xFF232033)
    val Ink700 = Color(0xFF2F2B44)
    val Ink600 = Color(0xFF45405F)
    val Ink400 = Color(0xFF7C779A)
    val Ink300 = Color(0xFFA6A2C0)
    val Ink200 = Color(0xFFD3D0E4)
    val Ink100 = Color(0xFFEDEBF6)
    val Ink50 = Color(0xFFF8F7FC)

    val Record = Color(0xFFFF4D6D)
    val Success = Color(0xFF2FBF8F)
    val Warning = Color(0xFFF5A524)
    val Danger = Color(0xFFEF4E6E)
}

/** Semantic colors beyond Material's scheme: gradients, glass, status and accents. */
@Immutable
data class RgColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val outline: Color,
    val outlineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val scrim: Color,
    val record: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val pastelLavender: Color,
    val pastelRose: Color,
    val pastelPeach: Color,
    val pastelMint: Color,
    val pastelSky: Color,
    val pastelButter: Color,
    val blobs: List<Color>,
    /** Readable text/icon color on [accentSoft] (WCAG AA). */
    val onAccentSoft: Color = accent,
    /** A surface that sits above [surfaceMuted] (selected segment, raised thumb). Lighter than [surface] in dark mode. */
    val surfaceRaised: Color = surface,
    /** Hairline separators inside cards and groups. */
    val divider: Color = outline,
    /** Tint for soft, colored drop shadows. */
    val shadowTint: Color = Color.Black,
) {
    /** Signature brand gradient used for primary actions. */
    val brandGradient: Brush get() = Brush.linearGradient(listOf(Palette.Lavender500, Palette.Rose400, Palette.Peach400))
    /**
     * Gradient for primary calls to action: the brand hues deepened so white labels stay legible (≥3.5:1 across the
     * label area) while keeping the lavender → rose identity.
     */
    val ctaGradient: Brush get() = Brush.linearGradient(listOf(Color(0xFF6F5CEB), Color(0xFF9E5BE0), Color(0xFFD95E97)))
    val brandGradientSoft: Brush get() = Brush.linearGradient(listOf(pastelLavender, pastelRose, pastelPeach))
    val coolGradient: Brush get() = Brush.linearGradient(listOf(Palette.Sky400, Palette.Mint400))
    val proGradient: Brush get() = Brush.linearGradient(listOf(Color(0xFFFFC857), Color(0xFFFF8FAB), Color(0xFFA394FB)))
    val recordGradient: Brush get() = Brush.linearGradient(listOf(Color(0xFFFF6B8B), Palette.Record))
}

val LightRgColors = RgColors(
    isDark = false,
    background = Color(0xFFF7F5FD),
    backgroundElevated = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF0EDF9),
    textPrimary = Color(0xFF1B1830),
    textSecondary = Color(0xFF5F5A7A),
    textTertiary = Color(0xFF77728F),
    outline = Color(0xFFE6E2F4),
    outlineStrong = Color(0xFFCFC9E6),
    accent = Palette.Lavender600,
    onAccent = Color.White,
    accentSoft = Palette.Lavender100,
    glassFill = Color(0xB3FFFFFF),
    glassStroke = Color(0x66FFFFFF),
    glassHighlight = Color(0x40FFFFFF),
    scrim = Color(0x661B1830),
    record = Palette.Record,
    success = Palette.Success,
    warning = Palette.Warning,
    danger = Palette.Danger,
    pastelLavender = Palette.Lavender100,
    pastelRose = Color(0xFFFFE6ED),
    pastelPeach = Color(0xFFFFEEE2),
    pastelMint = Color(0xFFE2F8F2),
    pastelSky = Color(0xFFE5F1FF),
    pastelButter = Color(0xFFFFF5D6),
    blobs = listOf(Palette.Lavender200, Palette.Rose200, Palette.Peach200, Palette.Mint200),
    onAccentSoft = Palette.Lavender700,
    surfaceRaised = Color.White,
    divider = Color(0xFFEEEBF7),
    shadowTint = Color(0xFF4A3AB8),
)

val DarkRgColors = RgColors(
    isDark = true,
    background = Palette.Ink950,
    backgroundElevated = Palette.Ink900,
    surface = Palette.Ink850,
    surfaceMuted = Palette.Ink800,
    textPrimary = Color(0xFFF4F2FF),
    textSecondary = Color(0xFFB4B0CE),
    textTertiary = Color(0xFF8C87A8),
    outline = Color(0xFF2A2640),
    outlineStrong = Color(0xFF3D3858),
    accent = Palette.Lavender400,
    onAccent = Color(0xFF14102A),
    accentSoft = Color(0xFF2B2550),
    glassFill = Color(0xA61B1928),
    glassStroke = Color(0x29FFFFFF),
    glassHighlight = Color(0x12FFFFFF),
    scrim = Color(0x99000000),
    record = Palette.Record,
    success = Color(0xFF45D6A4),
    warning = Color(0xFFFFB84D),
    danger = Color(0xFFFF6B86),
    pastelLavender = Color(0xFF2A2447),
    pastelRose = Color(0xFF3A2433),
    pastelPeach = Color(0xFF3A2C24),
    pastelMint = Color(0xFF1C3530),
    pastelSky = Color(0xFF1E2C42),
    pastelButter = Color(0xFF3A3322),
    blobs = listOf(Color(0xFF3B2F7A), Color(0xFF5A2A4A), Color(0xFF5A3A2A), Color(0xFF1E4A42)),
    onAccentSoft = Color(0xFFC9C0FF),
    surfaceRaised = Color(0xFF3A3556),
    divider = Color(0xFF28243B),
    shadowTint = Color.Black,
)

val LocalRgColors = staticCompositionLocalOf { LightRgColors }
