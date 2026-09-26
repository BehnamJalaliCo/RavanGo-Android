package com.ravango.engine.beauty

import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.quality.QualityProfile

/** Per-frame shader strengths of the Face Retouch composite (0 = pass skipped). */
internal data class BeautyUniforms(
    val smoothK: Float = 0f,
    /** Share of fine texture (pores) kept by Soft Skin's frequency separation. */
    val detailKeep: Float = 1f,
    val retouchK: Float = 0f,
    val blemishK: Float = 0f,
    val brightK: Float = 0f,
    val whitenK: Float = 0f,
    val toneT: Float = 0f,
    val sharpenK: Float = 0f,
    val darkK: Float = 0f,
    val teethK: Float = 0f,
    val eyeWhitenK: Float = 0f,
    val eyeSharpenK: Float = 0f,
    val eyeColorK: Float = 0f,
) {
    /** True when at least one uniform changes pixels. */
    val any: Boolean
        get() = smoothK > 0f || retouchK > 0f || blemishK > 0f || brightK > 0f || whitenK > 0f || toneT != 0f ||
            sharpenK > 0f || darkK > 0f || teethK > 0f || eyeWhitenK > 0f || eyeSharpenK > 0f || eyeColorK > 0f
}

/**
 * Slider → shader mapping, and the "which passes run" gates of the effects layer. Pure, so every control can be
 * proven to reach the GPU in JVM tests.
 *
 * Sliders are perceptual: skin sliders use an ease-out curve so the low values people actually pick (the default
 * Soft Skin is 35) are clearly visible, while 100 stays strong but not plastic (fine texture is always partly
 * kept). Sharpening stays linear (over-sharpening looks bad quickly); Skin Tone is bipolar around 50.
 */
internal object BeautyMapping {

    /** Ease-out: 0→0, 35→0.58, 50→0.75, 100→1. */
    fun curve(value: Int): Float {
        val x = value.coerceIn(0, 100) / 100f
        return 1f - (1f - x) * (1f - x)
    }

    fun linear(value: Int): Float = value.coerceIn(0, 100) / 100f

    /** Soft Skin keeps ~72 % of the fine texture at low strength and ~17 % at full strength. */
    fun detailKeep(smoothK: Float): Float = (0.72f - 0.55f * smoothK).coerceIn(0.1f, 1f)

    fun uniforms(
        state: BeautyState,
        eyeColor: EyeColorSetting,
        beautyOn: Boolean,
        beautyFace: Boolean,
        profile: QualityProfile,
    ): BeautyUniforms {
        if (!beautyOn) return BeautyUniforms()
        fun c(f: BeautyFeature) = curve(state.intensity(f))
        val smoothK = if (profile.smoothing) c(BeautyFeature.SMOOTH_SKIN) else 0f
        val retouchK = if (profile.retouchAndBlemish) c(BeautyFeature.SKIN_RETOUCH) else 0f
        val blemishK = if (profile.retouchAndBlemish) c(BeautyFeature.BLEMISH_REMOVAL) else 0f
        val whitenK = c(BeautyFeature.WHITENING)
        val sharpen = linear(state.intensity(BeautyFeature.SHARPEN))
        val eyeFx = beautyFace && profile.eyeEffects
        return BeautyUniforms(
            smoothK = smoothK,
            detailKeep = detailKeep(smoothK),
            retouchK = retouchK,
            blemishK = blemishK,
            brightK = c(BeautyFeature.SKIN_BRIGHTNESS),
            whitenK = whitenK,
            toneT = ((state.intensity(BeautyFeature.SKIN_TONE) - 50) / 50f).coerceIn(-1f, 1f),
            sharpenK = if (profile.sharpen) sharpen * 0.9f else 0f,
            darkK = if (beautyFace) c(BeautyFeature.DARK_CIRCLES) else 0f,
            teethK = if (beautyFace) c(BeautyFeature.TEETH_WHITENING) else 0f,
            // Eye whitening rides on WHITENING and eye sharpening on SHARPEN (documented on BeautyEngine).
            eyeWhitenK = if (eyeFx) whitenK * 0.85f else 0f,
            eyeSharpenK = if (eyeFx) sharpen * 0.9f else 0f,
            eyeColorK = if (eyeFx && eyeColor.active) linear(eyeColor.intensity) else 0f,
        )
    }

    // ---------------------------------------------------------------------------------------------- effects gates

    /** Anything in the effects layer that must keep the processor from bypassing. */
    fun effectsActive(effects: EffectsState, swipe: FilterSwipe?): Boolean = !effects.isNeutral || swipe != null

    /** The live filter (or a swipe preview) changes pixels this frame. */
    fun filterActive(effects: EffectsState, swipe: FilterSwipe?): Boolean =
        (effects.filter != LiveFilter.NONE && effects.filterIntensity > 0) || swipe != null

    /** A background effect is selected and has what it needs (a photo for [BackgroundEffect.Image]). */
    fun wantsBackground(effects: EffectsState, hasPhoto: Boolean): Boolean =
        effects.background.active && (effects.background !is BackgroundEffect.Image || hasPhoto)

    /** The lens draws only on a tracked face. */
    fun lensNeedsFace(lens: Lens?): Boolean = lens?.needsFace == true

    /** Strength of the incoming filter while swiping: the current intensity, or full when that is 0. */
    fun swipeIntensity(effects: EffectsState): Float =
        if (effects.filterIntensity <= 0) 1f else effects.filterIntensity.coerceIn(0, 100) / 100f
}
