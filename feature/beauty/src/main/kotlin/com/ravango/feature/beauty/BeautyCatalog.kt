package com.ravango.feature.beauty

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.ravango.core.model.BeautyCategory
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.ProFeature
import kotlin.math.roundToInt

/** One adjustable control in the panel: a beauty slider or a makeup layer. */
sealed interface BeautyItem {
    val key: String
    val requiresFace: Boolean
    val bipolar: Boolean

    data class Beauty(val feature: BeautyFeature) : BeautyItem {
        override val key: String get() = "b:${feature.name}"
        override val requiresFace: Boolean get() = feature.requiresFace
        override val bipolar: Boolean get() = feature.bipolar
    }

    data class Makeup(val feature: MakeupFeature) : BeautyItem {
        override val key: String get() = "m:${feature.name}"
        override val requiresFace: Boolean get() = true
        override val bipolar: Boolean get() = false
    }
}

enum class BeautyTab(@StringRes val label: Int) {
    SKIN(R.string.beauty_tab_skin),
    FACE(R.string.beauty_tab_face),
    EYES(R.string.beauty_tab_eyes),
    MOUTH(R.string.beauty_tab_mouth),
    MAKEUP(R.string.beauty_tab_makeup),
    PRESETS(R.string.beauty_tab_presets),
}

internal object BeautyCatalog {

    fun items(tab: BeautyTab): List<BeautyItem> = when (tab) {
        BeautyTab.SKIN -> beautyIn(BeautyCategory.SKIN)
        BeautyTab.FACE -> beautyIn(BeautyCategory.FACE_SHAPE)
        BeautyTab.EYES -> beautyIn(BeautyCategory.EYES)
        BeautyTab.MOUTH -> beautyIn(BeautyCategory.MOUTH) +
            listOf(BeautyItem.Makeup(MakeupFeature.LIPSTICK), BeautyItem.Makeup(MakeupFeature.LIP_COLOR))
        BeautyTab.MAKEUP -> MakeupFeature.entries.map { BeautyItem.Makeup(it) }
        BeautyTab.PRESETS -> emptyList()
    }

    private fun beautyIn(category: BeautyCategory) = BeautyFeature.entries.filter { it.category == category }.map { BeautyItem.Beauty(it) }

    /** The entitlement required to use [item], or null when it is free. Plan → feature mapping stays remote. */
    fun requiredPro(item: BeautyItem): ProFeature? = when (item) {
        is BeautyItem.Makeup -> ProFeature.MAKEUP
        is BeautyItem.Beauty -> requiredPro(item.feature)
    }

    fun requiredPro(feature: BeautyFeature): ProFeature? = when (feature) {
        BeautyFeature.FACE_SLIM, BeautyFeature.JAW, BeautyFeature.CHIN, BeautyFeature.CHEEKBONE,
        BeautyFeature.FOREHEAD, BeautyFeature.NOSE, BeautyFeature.EYE_SIZE, BeautyFeature.EYE_SHAPE -> ProFeature.FACE_RESHAPE
        BeautyFeature.SKIN_RETOUCH, BeautyFeature.BLEMISH_REMOVAL, BeautyFeature.DARK_CIRCLES,
        BeautyFeature.TEETH_WHITENING -> ProFeature.ADVANCED_BEAUTY
        else -> if (feature.pro) ProFeature.ADVANCED_BEAUTY else null
    }

    /** First entitlement a whole state needs that [has] does not grant (for applying presets). */
    fun missingEntitlement(state: BeautyState, has: (ProFeature) -> Boolean): ProFeature? {
        for (f in BeautyFeature.entries) {
            if (state.intensity(f) == neutral(BeautyItem.Beauty(f))) continue
            val req = requiredPro(f) ?: continue
            if (!has(req)) return req
        }
        if (MakeupFeature.entries.any { state.layer(it).intensity > 0 } && !has(ProFeature.MAKEUP)) return ProFeature.MAKEUP
        return null
    }

    fun neutral(item: BeautyItem): Int = if (item.bipolar) 50 else 0

    fun value(state: BeautyState, item: BeautyItem): Int = when (item) {
        is BeautyItem.Beauty -> state.intensity(item.feature)
        is BeautyItem.Makeup -> state.layer(item.feature).intensity
    }

    fun withValue(state: BeautyState, item: BeautyItem, value: Int): BeautyState {
        val v = value.coerceIn(0, 100)
        return when (item) {
            is BeautyItem.Beauty -> state.copy(beauty = state.beauty + (item.feature to v))
            is BeautyItem.Makeup -> state.copy(makeup = state.makeup + (item.feature to state.layer(item.feature).copy(intensity = v)))
        }
    }

    fun withColor(state: BeautyState, feature: MakeupFeature, color: Long, defaultIntensity: Int = 50): BeautyState {
        val layer = state.layer(feature)
        val intensity = if (layer.intensity == 0) defaultIntensity else layer.intensity
        return state.copy(makeup = state.makeup + (feature to MakeupLayer(intensity, color)))
    }

    /** Number of non-neutral adjustments in [state]. */
    fun activeCount(state: BeautyState): Int =
        BeautyFeature.entries.count { state.intensity(it) != neutral(BeautyItem.Beauty(it)) } +
            MakeupFeature.entries.count { state.layer(it).intensity > 0 }

    /** Interpolates every value (and makeup colour) between two states for animated preset transitions. */
    fun lerp(from: BeautyState, to: BeautyState, t: Float): BeautyState {
        val beauty = BeautyFeature.entries.associateWith { f ->
            (from.intensity(f) + (to.intensity(f) - from.intensity(f)) * t).roundToInt()
        }
        val makeup = MakeupFeature.entries.associateWith { f ->
            val a = from.layer(f)
            val b = to.layer(f)
            val startColor = if (a.intensity == 0) b.color else a.color
            MakeupLayer(
                intensity = (a.intensity + (b.intensity - a.intensity) * t).roundToInt(),
                color = lerpColor(startColor, b.color, t),
            )
        }
        return BeautyState(enabled = to.enabled, beauty = beauty, makeup = makeup)
    }

    private fun lerpColor(a: Long, b: Long, t: Float): Long {
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toInt()
        fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).roundToInt().coerceIn(0, 255).toLong()
        return (0xFFL shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    @StringRes
    fun label(item: BeautyItem): Int = when (item) {
        is BeautyItem.Beauty -> when (item.feature) {
            BeautyFeature.SMOOTH_SKIN -> R.string.beauty_feature_smooth_skin
            BeautyFeature.SKIN_RETOUCH -> R.string.beauty_feature_skin_retouch
            BeautyFeature.BLEMISH_REMOVAL -> R.string.beauty_feature_blemish_removal
            BeautyFeature.SKIN_BRIGHTNESS -> R.string.beauty_feature_skin_brightness
            BeautyFeature.SKIN_TONE -> R.string.beauty_feature_skin_tone
            BeautyFeature.SHARPEN -> R.string.beauty_feature_sharpen
            BeautyFeature.WHITENING -> R.string.beauty_feature_whitening
            BeautyFeature.DARK_CIRCLES -> R.string.beauty_feature_dark_circles
            BeautyFeature.FACE_SLIM -> R.string.beauty_feature_face_slim
            BeautyFeature.JAW -> R.string.beauty_feature_jaw
            BeautyFeature.CHIN -> R.string.beauty_feature_chin
            BeautyFeature.CHEEKBONE -> R.string.beauty_feature_cheekbone
            BeautyFeature.FOREHEAD -> R.string.beauty_feature_forehead
            BeautyFeature.NOSE -> R.string.beauty_feature_nose
            BeautyFeature.EYE_SIZE -> R.string.beauty_feature_eye_size
            BeautyFeature.EYE_SHAPE -> R.string.beauty_feature_eye_shape
            BeautyFeature.TEETH_WHITENING -> R.string.beauty_feature_teeth_whitening
        }
        is BeautyItem.Makeup -> when (item.feature) {
            MakeupFeature.LIPSTICK -> R.string.beauty_makeup_lipstick
            MakeupFeature.LIP_COLOR -> R.string.beauty_makeup_lip_color
            MakeupFeature.EYEBROW -> R.string.beauty_makeup_eyebrow
            MakeupFeature.EYELASHES -> R.string.beauty_makeup_eyelashes
            MakeupFeature.EYELINER -> R.string.beauty_makeup_eyeliner
            MakeupFeature.EYESHADOW -> R.string.beauty_makeup_eyeshadow
            MakeupFeature.BLUSH -> R.string.beauty_makeup_blush
            MakeupFeature.CONTOUR -> R.string.beauty_makeup_contour
            MakeupFeature.HIGHLIGHT -> R.string.beauty_makeup_highlight
            MakeupFeature.FOUNDATION -> R.string.beauty_makeup_foundation
        }
    }

    /** Labels for the two ends of a bipolar slider (start = 0, end = 100). */
    fun bipolarEnds(item: BeautyItem): Pair<Int, Int> = when ((item as? BeautyItem.Beauty)?.feature) {
        BeautyFeature.SKIN_TONE -> R.string.beauty_tone_cooler to R.string.beauty_tone_warmer
        BeautyFeature.EYE_SHAPE -> R.string.beauty_eye_rounder to R.string.beauty_eye_lifted
        BeautyFeature.JAW -> R.string.beauty_narrower to R.string.beauty_wider
        BeautyFeature.CHIN, BeautyFeature.FOREHEAD -> R.string.beauty_shorter to R.string.beauty_taller
        else -> R.string.beauty_less to R.string.beauty_more
    }

    fun icon(item: BeautyItem): ImageVector = when (item) {
        is BeautyItem.Beauty -> when (item.feature) {
            BeautyFeature.SMOOTH_SKIN -> Icons.Rounded.FaceRetouchingNatural
            BeautyFeature.SKIN_RETOUCH -> Icons.Rounded.AutoFixHigh
            BeautyFeature.BLEMISH_REMOVAL -> Icons.Rounded.Healing
            BeautyFeature.SKIN_BRIGHTNESS -> Icons.Rounded.LightMode
            BeautyFeature.SKIN_TONE -> Icons.Rounded.Thermostat
            BeautyFeature.SHARPEN -> Icons.Rounded.Details
            BeautyFeature.WHITENING -> Icons.Rounded.Flare
            BeautyFeature.DARK_CIRCLES -> Icons.Rounded.Tonality
            BeautyFeature.FACE_SLIM -> Icons.Rounded.Compress
            BeautyFeature.JAW -> Icons.Rounded.SwapHoriz
            BeautyFeature.CHIN -> Icons.Rounded.South
            BeautyFeature.CHEEKBONE -> Icons.Rounded.UnfoldLess
            BeautyFeature.FOREHEAD -> Icons.Rounded.North
            BeautyFeature.NOSE -> Icons.Rounded.CloseFullscreen
            BeautyFeature.EYE_SIZE -> Icons.Rounded.ZoomIn
            BeautyFeature.EYE_SHAPE -> Icons.Rounded.RemoveRedEye
            BeautyFeature.TEETH_WHITENING -> Icons.Rounded.SentimentSatisfied
        }
        is BeautyItem.Makeup -> when (item.feature) {
            MakeupFeature.LIPSTICK -> Icons.Rounded.Brush
            MakeupFeature.LIP_COLOR -> Icons.Rounded.WaterDrop
            MakeupFeature.EYEBROW -> Icons.Rounded.Draw
            MakeupFeature.EYELASHES -> Icons.Rounded.Visibility
            MakeupFeature.EYELINER -> Icons.Rounded.BorderColor
            MakeupFeature.EYESHADOW -> Icons.Rounded.Palette
            MakeupFeature.BLUSH -> Icons.Rounded.Gradient
            MakeupFeature.CONTOUR -> Icons.Rounded.Contrast
            MakeupFeature.HIGHLIGHT -> Icons.Rounded.AutoAwesome
            MakeupFeature.FOUNDATION -> Icons.Rounded.Opacity
        }
    }

    /** Curated shades per makeup layer (ARGB). */
    fun shades(feature: MakeupFeature): List<Long> = when (feature) {
        MakeupFeature.LIPSTICK -> listOf(0xFFC2185B, 0xFFB0204E, 0xFFD32F2F, 0xFF8E2433, 0xFFE0607E, 0xFFC96F5B, 0xFFA0524D, 0xFF7B1F3A)
        MakeupFeature.LIP_COLOR -> listOf(0xFFE57373, 0xFFF48FB1, 0xFFEF9A9A, 0xFFFFAB91, 0xFFCE93D8, 0xFFD7A49A)
        MakeupFeature.EYEBROW -> listOf(0xFF4E342E, 0xFF3E2723, 0xFF5D4037, 0xFF6D4C41, 0xFF8D6E63, 0xFFA1887F, 0xFF212121)
        MakeupFeature.EYELASHES -> listOf(0xFF111111, 0xFF2B1B17, 0xFF3E2723, 0xFF1A237E)
        MakeupFeature.EYELINER -> listOf(0xFF111111, 0xFF3E2723, 0xFF1A237E, 0xFF004D40, 0xFF4A148C, 0xFF6D4C41)
        MakeupFeature.EYESHADOW -> listOf(0xFF8D6E63, 0xFF9C6B5E, 0xFFB08968, 0xFFD4A373, 0xFFC48B9F, 0xFF7E57C2, 0xFF6A8CAF, 0xFF5D4037)
        MakeupFeature.BLUSH -> listOf(0xFFF48FB1, 0xFFF8A5B8, 0xFFFF8A80, 0xFFE57373, 0xFFFFAB91, 0xFFD81B60)
        MakeupFeature.CONTOUR -> listOf(0xFF6D4C41, 0xFF5D4037, 0xFF795548, 0xFF8D6E63, 0xFF4E342E)
        MakeupFeature.HIGHLIGHT -> listOf(0xFFFFF3E0, 0xFFFFF8E1, 0xFFFFE0B2, 0xFFFCE4EC, 0xFFFFFFFF)
        MakeupFeature.FOUNDATION -> listOf(0xFFF3D5C0, 0xFFE8C1A0, 0xFFE0B99A, 0xFFD1A17F, 0xFFB98563, 0xFF9C6B4E, 0xFF7A4E36)
    }
}
