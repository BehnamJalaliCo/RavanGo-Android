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
    PrompterFont.VAZIRMATN -> Vazirmatn
    PrompterFont.SAHEL -> Sahel
    PrompterFont.SAMIM -> Samim
    PrompterFont.SYSTEM_SANS -> FontFamily.SansSerif
    PrompterFont.SYSTEM_SERIF -> FontFamily.Serif
    PrompterFont.SYSTEM_MONO -> FontFamily.Monospace
}

/** Font resource ids for code paths that render text outside Compose (e.g. export overlays with android.graphics). */
fun PrompterFont.fontResIds(): Pair<Int?, Int?> = when (this) {
    PrompterFont.VAZIRMATN -> R.font.vazirmatn_regular to R.font.vazirmatn_bold
    PrompterFont.SAHEL -> R.font.sahel_regular to R.font.sahel_bold
    PrompterFont.SAMIM -> R.font.samim_regular to R.font.samim_bold
    else -> null to null
}

private val base = TextStyle(
    fontFamily = Vazirmatn,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

val RgTypography = Typography(
    displayLarge = base.copy(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displayMedium = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em),
    displaySmall = base.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineLarge = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
)
