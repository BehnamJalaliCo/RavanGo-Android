package com.ravango.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.R
import com.ravango.core.designsystem.theme.RgTheme

/** Official RavanGo logo lockups (vector, traced from the brand artwork in `docs/brand`). */
enum class RavanGoLogoStyle {
    /** The R-with-play mark only. */
    MARK,

    /** Mark above the «روان‌گو» wordmark (splash, onboarding, About). */
    STACKED,

    /** Mark beside the wordmark (top bars). */
    HORIZONTAL,
}

private const val MARK_ASPECT = 400f / 456f
private const val WORD_ASPECT = 660f / 262f

/** Near-black brand ink by day; white on dark surfaces so the R never disappears. */
val RavanGoLogoInkDay = Color(0xFF0B0F1B)

/**
 * RavanGo logo. [height] is the height of the mark; the wordmark scales with it. The play triangle and «گو» keep their
 * brand-blue gradient; the ink layers follow [ink] (defaults to the theme: near-black in light mode, white in dark).
 */
@Composable
fun RavanGoLogo(
    modifier: Modifier = Modifier,
    style: RavanGoLogoStyle = RavanGoLogoStyle.MARK,
    height: Dp = 40.dp,
    ink: Color = if (RgTheme.colors.isDark) Color.White else RavanGoLogoInkDay,
    contentDescription: String? = "RavanGo",
) {
    val described = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    when (style) {
        RavanGoLogoStyle.MARK -> LogoMark(described, height, ink)
        RavanGoLogoStyle.STACKED -> Column(described, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(height * 0.1f)) {
            LogoMark(Modifier, height, ink)
            LogoWord(Modifier, height * 0.575f, ink)
        }
        // The lockup reads right-to-left in Persian (mark first on the right) and left-to-right in English.
        RavanGoLogoStyle.HORIZONTAL -> Row(described, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(height * 0.22f)) {
            LogoMark(Modifier, height, ink)
            LogoWord(Modifier, height * 0.575f, ink)
        }
    }
}

@Composable
private fun LogoMark(modifier: Modifier, height: Dp, ink: Color) {
    Box(modifier.height(height).aspectRatio(MARK_ASPECT)) {
        Image(painterResource(R.drawable.rg_logo_mark_ink), null, Modifier.matchParentSize(), colorFilter = ColorFilter.tint(ink))
        Image(painterResource(R.drawable.rg_logo_mark_accent), null, Modifier.matchParentSize())
    }
}

@Composable
private fun LogoWord(modifier: Modifier, height: Dp, ink: Color) {
    // Persian artwork: vector drawables are not auto-mirrored, so it reads correctly in both layout directions.
    Box(modifier.height(height).aspectRatio(WORD_ASPECT)) {
        Image(painterResource(R.drawable.rg_logo_word_ink), null, Modifier.matchParentSize(), colorFilter = ColorFilter.tint(ink))
        Image(painterResource(R.drawable.rg_logo_word_accent), null, Modifier.matchParentSize())
    }
}
