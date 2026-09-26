package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class BeautyCategory { SKIN, FACE_SHAPE, EYES, MOUTH }

/**
 * Real-time beauty adjustments. Each has an independent 0..100 intensity.
 * [requiresFace] features need face landmarks; they are skipped (never faked) when no face is tracked.
 * [bipolar] features are centered at 50 (50 = neutral, <50 = reduce, >50 = increase).
 */
@Serializable
enum class BeautyFeature(val category: BeautyCategory, val requiresFace: Boolean, val bipolar: Boolean = false, val pro: Boolean = false) {
    SMOOTH_SKIN(BeautyCategory.SKIN, requiresFace = false),
    SKIN_RETOUCH(BeautyCategory.SKIN, requiresFace = false, pro = true),
    BLEMISH_REMOVAL(BeautyCategory.SKIN, requiresFace = false, pro = true),
    SKIN_BRIGHTNESS(BeautyCategory.SKIN, requiresFace = false),
    SKIN_TONE(BeautyCategory.SKIN, requiresFace = false, bipolar = true),
    SHARPEN(BeautyCategory.SKIN, requiresFace = false),
    WHITENING(BeautyCategory.SKIN, requiresFace = false),
    DARK_CIRCLES(BeautyCategory.EYES, requiresFace = true, pro = true),
    FACE_SLIM(BeautyCategory.FACE_SHAPE, requiresFace = true, pro = true),
    JAW(BeautyCategory.FACE_SHAPE, requiresFace = true, bipolar = true, pro = true),
    CHIN(BeautyCategory.FACE_SHAPE, requiresFace = true, bipolar = true, pro = true),
    CHEEKBONE(BeautyCategory.FACE_SHAPE, requiresFace = true, pro = true),
    FOREHEAD(BeautyCategory.FACE_SHAPE, requiresFace = true, bipolar = true, pro = true),
    NOSE(BeautyCategory.FACE_SHAPE, requiresFace = true, pro = true),
    EYE_SIZE(BeautyCategory.EYES, requiresFace = true, pro = true),
    EYE_SHAPE(BeautyCategory.EYES, requiresFace = true, bipolar = true, pro = true),
    TEETH_WHITENING(BeautyCategory.MOUTH, requiresFace = true, pro = true),
}

/** Makeup layers. All require face landmarks and carry a user-selectable color. */
@Serializable
enum class MakeupFeature(val defaultColor: ArgbColor) {
    LIPSTICK(0xFFC2185B),
    LIP_COLOR(0xFFE57373),
    EYEBROW(0xFF4E342E),
    EYELASHES(0xFF111111),
    EYELINER(0xFF111111),
    EYESHADOW(0xFF8D6E63),
    BLUSH(0xFFF48FB1),
    CONTOUR(0xFF6D4C41),
    HIGHLIGHT(0xFFFFF3E0),
    FOUNDATION(0xFFE0B99A),
}

@Serializable
data class MakeupLayer(val intensity: Int = 0, val color: ArgbColor)

/** Complete beauty + makeup state applied by the beauty engine. */
@Serializable
data class BeautyState(
    val enabled: Boolean = true,
    val beauty: Map<BeautyFeature, Int> = DefaultBeauty,
    val makeup: Map<MakeupFeature, MakeupLayer> = emptyMap(),
) {
    fun intensity(feature: BeautyFeature): Int = beauty[feature] ?: if (feature.bipolar) 50 else 0
    fun layer(feature: MakeupFeature): MakeupLayer = makeup[feature] ?: MakeupLayer(0, feature.defaultColor)

    /** True when every value is neutral, letting the engine bypass all GPU passes. */
    val isNeutral: Boolean
        get() = !enabled || (
            BeautyFeature.entries.all { f -> intensity(f) == if (f.bipolar) 50 else 0 } &&
                MakeupFeature.entries.all { layer(it).intensity == 0 }
            )

    val needsFaceTracking: Boolean
        get() = enabled && (
            BeautyFeature.entries.any { f -> f.requiresFace && intensity(f) != if (f.bipolar) 50 else 0 } ||
                MakeupFeature.entries.any { layer(it).intensity > 0 }
            )

    companion object {
        val DefaultBeauty: Map<BeautyFeature, Int> = mapOf(
            BeautyFeature.SMOOTH_SKIN to 35,
            BeautyFeature.SKIN_BRIGHTNESS to 15,
            BeautyFeature.SHARPEN to 10,
        )
        val Off = BeautyState(enabled = false, beauty = emptyMap())
    }
}

@Serializable
data class BeautyPreset(
    val id: String = newId(),
    val name: String,
    val state: BeautyState,
    val builtIn: Boolean = false,
    val updatedAt: Long = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)
