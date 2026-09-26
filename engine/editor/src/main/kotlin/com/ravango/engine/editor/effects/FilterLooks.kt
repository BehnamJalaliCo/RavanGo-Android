package com.ravango.engine.editor.effects

import com.ravango.core.model.ColorAdjustments
import com.ravango.core.model.FilterPreset

/**
 * Fully resolved color grade for one clip: user adjustments plus a filter look blended by intensity.
 *
 * Tonal values follow [ColorAdjustments] (-1..1, 0 neutral; sharpen/blur/vignette/grain 0..1). [fade] lifts blacks,
 * [shadowTint]/[highlightTint] are additive RGB split-toning offsets.
 */
data class GradeParams(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpen: Float = 0f,
    val blur: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val fade: Float = 0f,
    val shadowTint: Triple<Float, Float, Float> = ZERO3,
    val highlightTint: Triple<Float, Float, Float> = ZERO3,
) {
    val isNeutral: Boolean get() = this == Neutral

    operator fun plus(o: GradeParams) = GradeParams(
        exposure + o.exposure, brightness + o.brightness, contrast + o.contrast, highlights + o.highlights, shadows + o.shadows,
        saturation + o.saturation, vibrance + o.vibrance, temperature + o.temperature, tint + o.tint,
        sharpen + o.sharpen, blur + o.blur, vignette + o.vignette, grain + o.grain, fade + o.fade,
        Triple(shadowTint.first + o.shadowTint.first, shadowTint.second + o.shadowTint.second, shadowTint.third + o.shadowTint.third),
        Triple(highlightTint.first + o.highlightTint.first, highlightTint.second + o.highlightTint.second, highlightTint.third + o.highlightTint.third),
    )

    operator fun times(k: Float) = GradeParams(
        exposure * k, brightness * k, contrast * k, highlights * k, shadows * k, saturation * k, vibrance * k, temperature * k, tint * k,
        sharpen * k, blur * k, vignette * k, grain * k, fade * k,
        Triple(shadowTint.first * k, shadowTint.second * k, shadowTint.third * k),
        Triple(highlightTint.first * k, highlightTint.second * k, highlightTint.third * k),
    )

    /** Clamps every value to its legal range after combining look + adjustments. */
    fun clamped() = copy(
        exposure = exposure.coerceIn(-1f, 1f), brightness = brightness.coerceIn(-1f, 1f), contrast = contrast.coerceIn(-1f, 1f),
        highlights = highlights.coerceIn(-1f, 1f), shadows = shadows.coerceIn(-1f, 1f), saturation = saturation.coerceIn(-1f, 1f),
        vibrance = vibrance.coerceIn(-1f, 1f), temperature = temperature.coerceIn(-1f, 1f), tint = tint.coerceIn(-1f, 1f),
        sharpen = sharpen.coerceIn(0f, 1f), blur = blur.coerceIn(0f, 1f), vignette = vignette.coerceIn(0f, 1f),
        grain = grain.coerceIn(0f, 1f), fade = fade.coerceIn(0f, 1f),
    )

    companion object {
        val ZERO3 = Triple(0f, 0f, 0f)
        val Neutral = GradeParams()

        fun from(a: ColorAdjustments) = GradeParams(
            exposure = a.exposure, brightness = a.brightness, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows,
            saturation = a.saturation, vibrance = a.vibrance, temperature = a.temperature, tint = a.tint,
            sharpen = a.sharpen, blur = a.blur, vignette = a.vignette, grain = a.grain,
        )

        /** Look at full intensity blended by [intensity] (0..1), plus the user's manual adjustments. */
        fun resolve(preset: FilterPreset, intensity: Float, adjustments: ColorAdjustments): GradeParams =
            (FilterLooks.look(preset) * intensity.coerceIn(0f, 1f) + from(adjustments)).clamped()
    }
}

/** The built-in looks, defined as parameter sets over the same grading pipeline. */
object FilterLooks {
    fun look(preset: FilterPreset): GradeParams = when (preset) {
        FilterPreset.NONE -> GradeParams.Neutral
        FilterPreset.VIVID -> GradeParams(saturation = 0.3f, vibrance = 0.35f, contrast = 0.15f, highlights = -0.05f)
        FilterPreset.WARM -> GradeParams(temperature = 0.35f, tint = 0.06f, saturation = 0.06f, highlightTint = Triple(0.03f, 0.015f, 0f))
        FilterPreset.COOL -> GradeParams(temperature = -0.35f, tint = -0.03f, saturation = -0.04f, shadowTint = Triple(0f, 0.01f, 0.03f))
        FilterPreset.CINEMA -> GradeParams(contrast = 0.22f, saturation = -0.18f, shadows = -0.08f, fade = 0.08f, vignette = 0.3f, shadowTint = Triple(-0.01f, 0.015f, 0.03f))
        FilterPreset.TEAL_ORANGE -> GradeParams(contrast = 0.15f, vibrance = 0.2f, shadowTint = Triple(-0.03f, 0.03f, 0.06f), highlightTint = Triple(0.07f, 0.025f, -0.04f))
        FilterPreset.FILM -> GradeParams(fade = 0.14f, grain = 0.3f, saturation = -0.12f, contrast = -0.06f, temperature = 0.08f, vignette = 0.2f)
        FilterPreset.FADE -> GradeParams(fade = 0.28f, contrast = -0.2f, saturation = -0.22f, brightness = 0.04f)
        FilterPreset.MONO -> GradeParams(saturation = -1f, contrast = 0.1f)
        FilterPreset.NOIR -> GradeParams(saturation = -1f, contrast = 0.45f, shadows = -0.2f, vignette = 0.55f, grain = 0.12f)
        FilterPreset.PASTEL -> GradeParams(saturation = -0.25f, brightness = 0.1f, fade = 0.16f, highlights = -0.12f, tint = 0.05f, highlightTint = Triple(0.02f, 0f, 0.025f))
        FilterPreset.SUNSET -> GradeParams(temperature = 0.5f, tint = 0.15f, saturation = 0.15f, highlightTint = Triple(0.06f, 0.01f, -0.02f), shadowTint = Triple(0.02f, -0.01f, 0.03f))
    }
}
