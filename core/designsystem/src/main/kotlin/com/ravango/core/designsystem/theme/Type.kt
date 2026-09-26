package com.ravango.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ravango.core.designsystem.R
import com.ravango.core.model.PrompterFont

/**
 * RavanGo brand font: Ravagh (licensed from fontiran.com). Resolved at build time to `R.font.brand_*`; builds without
 * access to the licensed files fall back to Vazirmatn with the same resource names (see core/designsystem/build.gradle.kts).
 */
val BrandFont = FontFamily(
    Font(R.font.brand_light, FontWeight.Light),
    Font(R.font.brand_regular, FontWeight.Normal),
    Font(R.font.brand_medium, FontWeight.Medium),
    Font(R.font.brand_semibold, FontWeight.SemiBold),
    Font(R.font.brand_bold, FontWeight.Bold),
    Font(R.font.brand_extrabold, FontWeight.ExtraBold),
    Font(R.font.brand_black, FontWeight.Black),
)

/** Vazirmatn covers Persian and Latin with matching metrics, so mixed-language UI stays balanced. */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_light, FontWeight.Light),
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semi_bold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extra_bold, FontWeight.ExtraBold),
)

val Sahel = FontFamily(
    Font(R.font.sahel_regular, FontWeight.Normal),
    Font(R.font.sahel_bold, FontWeight.Bold),
)

val Samim = FontFamily(
    Font(R.font.samim_regular, FontWeight.Normal),
    Font(R.font.samim_bold, FontWeight.Bold),
)

/** Maps the persisted font choice to a Compose [FontFamily]. Shared by teleprompter, subtitles and text overlays. */
fun PrompterFont.toFontFamily(): FontFamily = when (this) {
    PrompterFont.RAVAGH -> BrandFont
    PrompterFont.VAZIRMATN -> Vazirmatn
    PrompterFont.SAHEL -> Sahel
    PrompterFont.SAMIM -> Samim
    PrompterFont.SYSTEM_SANS -> FontFamily.SansSerif
    PrompterFont.SYSTEM_SERIF -> FontFamily.Serif
    PrompterFont.SYSTEM_MONO -> FontFamily.Monospace
}

/** Font resource ids for code paths that render text outside Compose (e.g. export overlays with android.graphics). */
fun PrompterFont.fontResIds(): Pair<Int?, Int?> = when (this) {
    PrompterFont.RAVAGH -> R.font.brand_regular to R.font.brand_bold
    PrompterFont.VAZIRMATN -> R.font.vazirmatn_regular to R.font.vazirmatn_bold
    PrompterFont.SAHEL -> R.font.sahel_regular to R.font.sahel_bold
    PrompterFont.SAMIM -> R.font.samim_regular to R.font.samim_bold
    else -> null to null
}

private val base = TextStyle(
    fontFamily = BrandFont,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/*
 * Type scale. Persian (Arabic script) has tall ascenders (ک، گ، ل) and deep descenders (ی، ع، ج), so line heights are
 * ~1.5× for body text and ≥1.35× for single-line titles — slightly looser than Latin defaults, which also keeps
 * English comfortable. Everything sits on a 2sp grid.
 */
val RgTypography = Typography(
    displayLarge = base.copy(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displayMedium = base.copy(fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em),
    displaySmall = base.copy(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    headlineLarge = base.copy(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    headlineMedium = base.copy(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em),
)

/** Label styles for [com.ravango.core.designsystem.component.RgButtonSize]: larger buttons get larger, bolder labels. */
object ButtonText {
    val small = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val medium = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val large = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val hero = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
}

/** Tabular (fixed-width) digits for counters, timers and prices so numbers don't jitter as they change. */
val TabularNumbers: TextStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * Balanced line breaking for short, prominent paragraphs (hero copy, empty states): lines come out of similar length,
 * so a single word never dangles on the last line. Falls back to greedy breaking below Android 13.
 */
val BalancedLines: TextStyle = TextStyle(
    lineBreak = androidx.compose.ui.text.style.LineBreak(
        strategy = androidx.compose.ui.text.style.LineBreak.Strategy.Balanced,
        strictness = androidx.compose.ui.text.style.LineBreak.Strictness.Normal,
        wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase,
    ),
)
