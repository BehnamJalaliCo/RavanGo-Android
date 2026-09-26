package com.ravango.engine.beauty.makeup

/**
 * ADDED — the "how" of the makeup layers (the "how much / which colour" lives in
 * [com.ravango.core.model.BeautyState.makeup]). Complete looks (Snapchat-style makeup lenses) combine both.
 *
 * Every variant is pre-baked once into the look atlas ([LookAtlas], UV space, so it tracks the face), and a style
 * only changes shader uniforms: switching looks or dragging the look-intensity slider never re-bakes a texture.
 *
 * Shape parameters ([wing], [lashVolume], [browDefinition], [lipFinish], [skinFinish]) describe the look and are
 * not scaled by look intensity; amount parameters (the rest) are.
 */
data class MakeupStyle(
    /** Eyeliner shape: 0 tight lash-line only · 0.5 classic flick (default) · 1 dramatic extended cat-eye. */
    val wing: Float = 0.5f,
    /** 0 natural lashes · 1 dramatic, longer multi-strand volume lashes (clusters). */
    val lashVolume: Float = 0f,
    /** Kohl along the lower lash line, 0..1 (uses the eyeliner colour). */
    val lowerLiner: Float = 0f,
    /** Second eyeshadow tone in the outer "V" and crease (ARGB) and its amount 0..1. */
    val shadowAccent: Long = 0xFF4E342E,
    val shadowAccentAmount: Float = 0f,
    /** Lid shimmer (pearl/foil with fine glitter) colour and amount 0..1. */
    val shimmerColor: Long = 0xFFFFF1D6,
    val shimmer: Float = 0f,
    /** Lip finish: −1 matte · 0 satin · +1 high-shine gloss. */
    val lipFinish: Float = 0f,
    /** Ombré / gradient lips: centre colour (ARGB) and amount 0..1. */
    val lipCenter: Long = 0xFFB71C1C,
    val lipCenterAmount: Float = 0f,
    /** Brows: 0 soft powder · 1 sculpted (crisp, fuller, defined edges). */
    val browDefinition: Float = 0f,
    /** Skin finish on the foundation area: −1 soft matte (shine controlled) · +1 dewy / glass skin. */
    val skinFinish: Float = 0f,
    /** Painted-on freckles over the nose and cheeks, 0..1. */
    val freckles: Float = 0f,
) {
    /** True when the style needs the extra look atlas (anything beyond the classic defaults). */
    val needsLookAtlas: Boolean
        get() = wing != 0.5f || lashVolume > 0f || lowerLiner > 0f || shadowAccentAmount > 0f || shimmer > 0f ||
            lipFinish != 0f || lipCenterAmount > 0f || browDefinition > 0f || skinFinish != 0f || freckles > 0f

    companion object {
        val Default = MakeupStyle()
    }
}
