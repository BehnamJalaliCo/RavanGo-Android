package com.ravango.feature.beauty

import androidx.annotation.StringRes
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer

/**
 * Curated one-tap makeup looks (like Snapchat's makeup carousel). A look replaces the makeup layers only — skin,
 * face-shape and eye settings the user tuned stay as they are — and every layer stays editable afterwards.
 */
enum class MakeupLook(@StringRes val label: Int, private val layers: Map<MakeupFeature, MakeupLayer>) {
    NATURAL(
        R.string.beauty_look_natural,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(25, 0xFFE0B99A),
            MakeupFeature.EYEBROW to MakeupLayer(35, 0xFF5D4037),
            MakeupFeature.LIP_COLOR to MakeupLayer(35, 0xFFE57373),
            MakeupFeature.BLUSH to MakeupLayer(22, 0xFFF8A5B8),
            MakeupFeature.EYELASHES to MakeupLayer(30, 0xFF2B1B17),
            MakeupFeature.HIGHLIGHT to MakeupLayer(20, 0xFFFFF3E0),
        ),
    ),
    SOFT_GLAM(
        R.string.beauty_look_soft_glam,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(35, 0xFFE0B99A),
            MakeupFeature.CONTOUR to MakeupLayer(30, 0xFF6D4C41),
            MakeupFeature.BLUSH to MakeupLayer(35, 0xFFF48FB1),
            MakeupFeature.HIGHLIGHT to MakeupLayer(40, 0xFFFFF3E0),
            MakeupFeature.EYESHADOW to MakeupLayer(45, 0xFFB08968),
            MakeupFeature.EYELINER to MakeupLayer(45, 0xFF3E2723),
            MakeupFeature.EYELASHES to MakeupLayer(60, 0xFF111111),
            MakeupFeature.EYEBROW to MakeupLayer(45, 0xFF4E342E),
            MakeupFeature.LIPSTICK to MakeupLayer(55, 0xFFC96F5B),
        ),
    ),
    BOLD_LIPS(
        R.string.beauty_look_bold_lips,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(30, 0xFFE0B99A),
            MakeupFeature.LIPSTICK to MakeupLayer(85, 0xFFD32F2F),
            MakeupFeature.EYELINER to MakeupLayer(50, 0xFF111111),
            MakeupFeature.EYELASHES to MakeupLayer(50, 0xFF111111),
            MakeupFeature.EYEBROW to MakeupLayer(40, 0xFF3E2723),
            MakeupFeature.HIGHLIGHT to MakeupLayer(20, 0xFFFFF8E1),
        ),
    ),
    SMOKEY_EYES(
        R.string.beauty_look_smokey_eyes,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(30, 0xFFE0B99A),
            MakeupFeature.EYESHADOW to MakeupLayer(80, 0xFF5D4037),
            MakeupFeature.EYELINER to MakeupLayer(75, 0xFF111111),
            MakeupFeature.EYELASHES to MakeupLayer(80, 0xFF111111),
            MakeupFeature.EYEBROW to MakeupLayer(50, 0xFF3E2723),
            MakeupFeature.CONTOUR to MakeupLayer(30, 0xFF5D4037),
            MakeupFeature.LIP_COLOR to MakeupLayer(35, 0xFFD7A49A),
        ),
    ),
    K_BEAUTY(
        R.string.beauty_look_k_beauty,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(30, 0xFFF3D5C0),
            MakeupFeature.LIP_COLOR to MakeupLayer(60, 0xFFEF9A9A),
            MakeupFeature.BLUSH to MakeupLayer(35, 0xFFFFAB91),
            MakeupFeature.EYESHADOW to MakeupLayer(25, 0xFFC48B9F),
            MakeupFeature.EYELASHES to MakeupLayer(35, 0xFF2B1B17),
            MakeupFeature.EYEBROW to MakeupLayer(30, 0xFF6D4C41),
            MakeupFeature.HIGHLIGHT to MakeupLayer(45, 0xFFFCE4EC),
        ),
    ),
    BRONZED(
        R.string.beauty_look_bronzed,
        mapOf(
            MakeupFeature.FOUNDATION to MakeupLayer(30, 0xFFD1A17F),
            MakeupFeature.CONTOUR to MakeupLayer(55, 0xFF795548),
            MakeupFeature.BLUSH to MakeupLayer(30, 0xFFC96F5B),
            MakeupFeature.HIGHLIGHT to MakeupLayer(55, 0xFFFFE0B2),
            MakeupFeature.EYESHADOW to MakeupLayer(50, 0xFFD4A373),
            MakeupFeature.EYELINER to MakeupLayer(40, 0xFF3E2723),
            MakeupFeature.EYELASHES to MakeupLayer(45, 0xFF111111),
            MakeupFeature.LIPSTICK to MakeupLayer(45, 0xFFA0524D),
        ),
    ),
    ;

    /** The full makeup map of this look (layers it does not use are switched off, keeping their colours). */
    fun makeup(current: BeautyState): Map<MakeupFeature, MakeupLayer> = MakeupFeature.entries.associateWith { f ->
        layers[f] ?: MakeupLayer(0, current.layer(f).color)
    }

    fun applyTo(state: BeautyState): BeautyState = state.copy(enabled = true, makeup = makeup(state))

    /** True when [state]'s makeup is exactly this look (so the chip shows as selected). */
    fun matches(state: BeautyState): Boolean = MakeupFeature.entries.all { f ->
        val want = layers[f]
        val have = state.layer(f)
        if (want == null) have.intensity == 0 else have.intensity == want.intensity && have.color == want.color
    }

    /** Up to three signature colours for the round preview chip (lips, eyes, cheeks). */
    val swatches: List<Long>
        get() = listOfNotNull(
            (layers[MakeupFeature.LIPSTICK] ?: layers[MakeupFeature.LIP_COLOR])?.color,
            (layers[MakeupFeature.EYESHADOW] ?: layers[MakeupFeature.EYELINER])?.color,
            (layers[MakeupFeature.BLUSH] ?: layers[MakeupFeature.CONTOUR] ?: layers[MakeupFeature.HIGHLIGHT])?.color,
        )

    companion object {
        fun activeIn(state: BeautyState): MakeupLook? = entries.firstOrNull { it.matches(state) }

        /** Makeup switched off entirely (the "None" chip). */
        fun cleared(state: BeautyState): BeautyState =
            state.copy(makeup = MakeupFeature.entries.associateWith { MakeupLayer(0, state.layer(it).color) })

        fun hasMakeup(state: BeautyState): Boolean = MakeupFeature.entries.any { state.layer(it).intensity > 0 }
    }
}
