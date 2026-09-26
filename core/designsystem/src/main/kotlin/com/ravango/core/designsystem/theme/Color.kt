package com.ravango.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Screens should use semantic tokens ([RgColors] / MaterialTheme.colorScheme), not these.
 *
 * The brand is built around the logo: a near-black geometric R ([Ink]) whose counter is a sky → electric-blue play
 * triangle ([LogoSky] → [LogoBlue]). Blue is the one accent; soft pastel companions (periwinkle, lilac, mint, peach,
 * blush, butter) colour category tiles, blobs and illustrations. Pro keeps its own warm gold so it never reads as a
 * regular action.
 */
object Palette {
    // Logo
    /** Logo ink: primary text in light mode. */
    val Ink = Color(0xFF0B0F1B)
    val LogoSky = Color(0xFF00A3FF)
    val LogoBlue = Color(0xFF006DFD)

    // Brand blue
    val Blue50 = Color(0xFFEEF5FF)
    val Blue100 = Color(0xFFDDEBFF)
    val Blue200 = Color(0xFFB9D6FF)
    val Blue300 = Color(0xFF86B9FF)
    val Blue400 = Color(0xFF5AA5FF)
    val Blue500 = Color(0xFF1A7FFF)
    val Blue600 = Color(0xFF0062F0)
    val Blue700 = Color(0xFF0052CC)
    val Blue800 = Color(0xFF0A3F99)

    // Pastel companions
    val Periwinkle100 = Color(0xFFE8ECFF)
    val Periwinkle200 = Color(0xFFD3DAFF)
    val Periwinkle300 = Color(0xFFB3BEFF)
    val Periwinkle400 = Color(0xFF8F9CFF)
    val Periwinkle500 = Color(0xFF5160F2)
    val Periwinkle600 = Color(0xFF4A57D6)

    val Lilac100 = Color(0xFFF1E9FF)
    val Lilac200 = Color(0xFFE2D2FF)
    val Lilac300 = Color(0xFFCDB2FF)
    val Lilac400 = Color(0xFFB08BF7)
    val Lilac600 = Color(0xFF7F45D1)

    val Blush100 = Color(0xFFFFE7EF)
    val Blush200 = Color(0xFFFFD0DF)
    val Blush300 = Color(0xFFFFB0C8)
    val Blush600 = Color(0xFFC8325F)

    // Kept for existing callers; tuned to sit next to the blue brand (lavender leans periwinkle).
    val Lavender50 = Color(0xFFF4F5FF)
    val Lavender100 = Color(0xFFE9EBFF)
    val Lavender200 = Color(0xFFD5D9FF)
    val Lavender300 = Color(0xFFB9BFFF)
    val Lavender400 = Color(0xFF979FFB)
    val Lavender500 = Color(0xFF7780F2)
    val Lavender600 = Color(0xFF5A62E0)
    val Lavender700 = Color(0xFF444BC4)

    val Rose200 = Color(0xFFFFD3DF)
    val Rose300 = Color(0xFFFFB3C7)
    val Rose400 = Color(0xFFFF93AF)
    val Rose500 = Color(0xFFF7718F)

    val Peach200 = Color(0xFFFFE1CC)
    val Peach300 = Color(0xFFFFC9A3)
    val Peach400 = Color(0xFFFFAE7A)
    val Peach600 = Color(0xFFC0521A)

    val Mint200 = Color(0xFFCDF5EC)
    val Mint300 = Color(0xFFA3EAD9)
    val Mint400 = Color(0xFF6FD9C0)
    val Mint500 = Color(0xFF3CC4A4)
    val Mint700 = Color(0xFF0B7F5B)

    val Sky200 = Color(0xFFD3E8FF)
    val Sky300 = Color(0xFFABD2FF)
    val Sky400 = Color(0xFF7DB8FF)

    val Butter300 = Color(0xFFFFE7A3)
    val Butter400 = Color(0xFFFFD166)
    val Butter700 = Color(0xFF946400)

    // Pro gold
    val Gold300 = Color(0xFFFFD66B)
    val Gold400 = Color(0xFFFFB547)
    val Gold500 = Color(0xFFFF9B5E)
    /** Dark ink for labels on the gold Pro gradient (≥8:1 across it). */
    val GoldInk = Color(0xFF2A1800)

    // Cool blue-black neutrals (dark theme + studio surfaces)
    val Ink950 = Color(0xFF070A13)
    val Ink900 = Color(0xFF0C101C)
    val Ink850 = Color(0xFF111726)
    val Ink800 = Color(0xFF182033)
    val Ink700 = Color(0xFF243049)
    val Ink600 = Color(0xFF3A4663)
    val Ink400 = Color(0xFF6E7A96)
    val Ink300 = Color(0xFF9AA5BF)
    val Ink200 = Color(0xFFCBD3E4)
    val Ink100 = Color(0xFFE8ECF5)
    val Ink50 = Color(0xFFF5F7FD)

    val Record = Color(0xFFFF4D6D)
    val Success = Color(0xFF0B7F5B)
    val Warning = Color(0xFFA35F00)
    val Danger = Color(0xFFD0294F)
}

/** A pastel container with a readable content color (icon/text ≥ 4:1 on it). Used for category tiles and bubbles. */
@Immutable
data class RgTone(val container: Color, val content: Color)

/** The pastel companion set. Pick by meaning (sky = scripts, blush = AI, mint = projects…) or cycle by index. */
@Immutable
data class RgTones(
    val sky: RgTone,
    val periwinkle: RgTone,
    val lilac: RgTone,
    val mint: RgTone,
    val peach: RgTone,
    val blush: RgTone,
    val butter: RgTone,
) {
    val all: List<RgTone> get() = listOf(sky, periwinkle, lilac, mint, peach, blush, butter)

    /** Stable tone for a seed (e.g. a project id), so placeholders keep their color across launches. */
    fun forSeed(seed: String): RgTone = all[(seed.hashCode() and Int.MAX_VALUE) % 7]
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
    /** Pastel companions with readable content colors (category tiles, icon bubbles, illustrations). */
    val tones: RgTones = RgTones(
        sky = RgTone(pastelSky, accent),
        periwinkle = RgTone(pastelLavender, accent),
        lilac = RgTone(pastelLavender, accent),
        mint = RgTone(pastelMint, success),
        peach = RgTone(pastelPeach, warning),
        blush = RgTone(pastelRose, danger),
        butter = RgTone(pastelButter, warning),
    ),
    /** Label/icon color on the gold [proGradient]. */
    val onPro: Color = Palette.GoldInk,
    /** Glow used under the primary CTA and hero cards. */
    val accentGlow: Color = accent,
) {
    /** Logo gradient (sky → electric blue). Decorative: progress fills, indicators, logo-adjacent accents. */
    val brandGradient: Brush = Brush.linearGradient(listOf(Palette.LogoSky, Palette.LogoBlue))

    /**
     * Primary call-to-action gradient: sky-electric → brand blue → a touch of periwinkle. Tuned so white labels measure
     * ≥4.5:1 across the label band (30–100 % of the run) and ≥3.8:1 at the very start.
     */
    val ctaGradient: Brush = Brush.linearGradient(listOf(Color(0xFF1A7FFF), Color(0xFF0066F2), Color(0xFF5160F2)))

    /** Soft pastel wash (sky → periwinkle → lilac) for avatars, empty states and badges. */
    val brandGradientSoft: Brush = Brush.linearGradient(listOf(pastelSky, pastelLavender, tones.lilac.container))
    val coolGradient: Brush = Brush.linearGradient(listOf(Palette.Sky400, Palette.Mint400))

    /** Pro: warm gold, deliberately distinct from the blue brand. Pair with [onPro] labels. */
    val proGradient: Brush = Brush.linearGradient(listOf(Palette.Gold300, Palette.Gold400, Palette.Gold500))
    val recordGradient: Brush = Brush.linearGradient(listOf(Color(0xFFFF6B8B), Palette.Record))
}

/*
 * Contrast (WCAG 2.x, computed; see docs in the report):
 *   light  textPrimary #0B0F1B on background 17.9, on surface 19.1
 *          textSecondary #4B5471: 7.0 / 7.5 / 6.6 (bg / surface / surfaceMuted)
 *          textTertiary #646D8C: 4.8 / 5.1 / 4.5
 *          accent #0062F0: 4.9 / 5.2 / 4.6; on accentSoft 4.5 — onAccentSoft #0052CC on accentSoft 5.9
 *          danger #D0294F 4.8 / 5.1, success #0B7F5B 4.7 / 5.0, warning #A35F00 4.8 / 5.2
 *          white on CTA gradient ≥4.5 over the label band (5.0 at the centre)
 *   dark   textPrimary #F3F6FF on background 18.3, surface 16.6
 *          textSecondary #A8B2CA 9.3 / 8.4 / 7.6, textTertiary #8691AB 6.3 / 5.7 / 5.1
 *          accent #5AA5FF 7.8 / 7.0 / 6.4; onAccent #041022 on accent 7.5
 *   tones  every tone.content on its container ≥4.1:1 (light) / ≥7.8:1 (dark)
 */
val LightRgColors = RgColors(
    isDark = false,
    background = Color(0xFFF5F7FD),
    backgroundElevated = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFEDF1FA),
    textPrimary = Palette.Ink,
    textSecondary = Color(0xFF4B5471),
    textTertiary = Color(0xFF646D8C),
    outline = Color(0xFFE2E7F3),
    outlineStrong = Color(0xFFC5CEE3),
    accent = Palette.Blue600,
    onAccent = Color.White,
    accentSoft = Color(0xFFE5EFFF),
    glassFill = Color(0xC7FFFFFF),
    glassStroke = Color(0x99FFFFFF),
    glassHighlight = Color(0x59FFFFFF),
    scrim = Color(0x730B0F1B),
    record = Palette.Record,
    success = Palette.Success,
    warning = Palette.Warning,
    danger = Palette.Danger,
    pastelLavender = Palette.Periwinkle100,
    pastelRose = Palette.Blush100,
    pastelPeach = Color(0xFFFFEDE1),
    pastelMint = Color(0xFFDDF6EC),
    pastelSky = Color(0xFFE2F0FF),
    pastelButter = Color(0xFFFFF4D4),
    blobs = listOf(Color(0xFFCFE4FF), Color(0xFFDCDFFF), Color(0xFFEBDDFF), Color(0xFFD4F3E8)),
    onAccentSoft = Palette.Blue700,
    surfaceRaised = Color.White,
    divider = Color(0xFFEBEFF7),
    shadowTint = Color(0xFF1B3A8C),
    tones = RgTones(
        sky = RgTone(Color(0xFFE2F0FF), Palette.Blue600),
        periwinkle = RgTone(Palette.Periwinkle100, Palette.Periwinkle600),
        lilac = RgTone(Palette.Lilac100, Palette.Lilac600),
        mint = RgTone(Color(0xFFDDF6EC), Palette.Mint700),
        peach = RgTone(Color(0xFFFFEDE1), Palette.Peach600),
        blush = RgTone(Palette.Blush100, Palette.Blush600),
        butter = RgTone(Color(0xFFFFF4D4), Palette.Butter700),
    ),
    onPro = Palette.GoldInk,
    accentGlow = Color(0xFF1A7FFF),
)

val DarkRgColors = RgColors(
    isDark = true,
    background = Palette.Ink950,
    backgroundElevated = Palette.Ink900,
    surface = Palette.Ink850,
    surfaceMuted = Palette.Ink800,
    textPrimary = Color(0xFFF3F6FF),
    textSecondary = Color(0xFFA8B2CA),
    textTertiary = Color(0xFF8691AB),
    outline = Color(0xFF1F2739),
    outlineStrong = Color(0xFF313C55),
    accent = Palette.Blue400,
    onAccent = Color(0xFF041022),
    accentSoft = Color(0xFF12284A),
    glassFill = Color(0xB3111726),
    glassStroke = Color(0x29FFFFFF),
    glassHighlight = Color(0x14FFFFFF),
    scrim = Color(0x99000000),
    record = Palette.Record,
    success = Color(0xFF45D6A4),
    warning = Color(0xFFFFB84D),
    danger = Color(0xFFFF6B86),
    pastelLavender = Color(0xFF1C2350),
    pastelRose = Color(0xFF3A1C2B),
    pastelPeach = Color(0xFF3A2618),
    pastelMint = Color(0xFF0F3129),
    pastelSky = Color(0xFF0F2847),
    pastelButter = Color(0xFF352C12),
    blobs = listOf(Color(0xFF0E3470), Color(0xFF232A78), Color(0xFF34215E), Color(0xFF0C3D3A)),
    onAccentSoft = Color(0xFFA6CCFF),
    surfaceRaised = Palette.Ink700,
    divider = Color(0xFF1B2233),
    shadowTint = Color.Black,
    tones = RgTones(
        sky = RgTone(Color(0xFF0F2847), Color(0xFF8CC4FF)),
        periwinkle = RgTone(Color(0xFF1C2350), Color(0xFFAEB7FF)),
        lilac = RgTone(Color(0xFF2A1F48), Color(0xFFD2B6FF)),
        mint = RgTone(Color(0xFF0F3129), Color(0xFF6FE0BD)),
        peach = RgTone(Color(0xFF3A2618), Color(0xFFFFB48A)),
        blush = RgTone(Color(0xFF3A1C2B), Color(0xFFFF9EBB)),
        butter = RgTone(Color(0xFF352C12), Color(0xFFFFD36B)),
    ),
    onPro = Palette.GoldInk,
    accentGlow = Color(0xFF0066F2),
)

val LocalRgColors = staticCompositionLocalOf { LightRgColors }
