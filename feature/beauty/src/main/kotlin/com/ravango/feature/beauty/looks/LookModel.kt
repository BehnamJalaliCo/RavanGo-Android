package com.ravango.feature.beauty.looks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.engine.beauty.makeup.MakeupStyle
import kotlinx.serialization.Serializable

/** Where a look shows up (camera carousel categories and the Beauty panel filters). */
@Serializable
enum class LookTag {
    /** Full makeup looks (the «آرایش» category). */
    MAKEUP,

    /** Skin-first, little or no visible makeup (the «زیبایی» category). */
    BEAUTY,
}

/**
 * Serializable mirror of the engine's [MakeupStyle] (the engine module has no serialization), so custom looks can be
 * persisted. [toEngine] scales the amount parameters by the look intensity; shape parameters stay as designed.
 */
@Serializable
data class LookStyle(
    val wing: Float = 0.5f,
    val lashVolume: Float = 0f,
    val lowerLiner: Float = 0f,
    val shadowAccent: Long = 0xFF4E342E,
    val shadowAccentAmount: Float = 0f,
    val shimmerColor: Long = 0xFFFFF1D6,
    val shimmer: Float = 0f,
    val lipFinish: Float = 0f,
    val lipCenter: Long = 0xFFB71C1C,
    val lipCenterAmount: Float = 0f,
    val browDefinition: Float = 0f,
    val skinFinish: Float = 0f,
    val freckles: Float = 0f,
) {
    fun toEngine(scale: Float = 1f): MakeupStyle {
        val s = scale.coerceIn(0f, 1f)
        return MakeupStyle(
            wing = wing.coerceIn(0f, 1f),
            lashVolume = lashVolume.coerceIn(0f, 1f),
            lowerLiner = (lowerLiner * s).coerceIn(0f, 1f),
            shadowAccent = shadowAccent,
            shadowAccentAmount = (shadowAccentAmount * s).coerceIn(0f, 1f),
            shimmerColor = shimmerColor,
            shimmer = (shimmer * s).coerceIn(0f, 1f),
            lipFinish = lipFinish.coerceIn(-1f, 1f),
            lipCenter = lipCenter,
            lipCenterAmount = (lipCenterAmount * s).coerceIn(0f, 1f),
            browDefinition = browDefinition.coerceIn(0f, 1f),
            skinFinish = (skinFinish * s).coerceIn(-1f, 1f),
            freckles = (freckles * s).coerceIn(0f, 1f),
        )
    }

    companion object {
        fun from(style: MakeupStyle) = LookStyle(
            wing = style.wing, lashVolume = style.lashVolume, lowerLiner = style.lowerLiner,
            shadowAccent = style.shadowAccent, shadowAccentAmount = style.shadowAccentAmount,
            shimmerColor = style.shimmerColor, shimmer = style.shimmer, lipFinish = style.lipFinish,
            lipCenter = style.lipCenter, lipCenterAmount = style.lipCenterAmount,
            browDefinition = style.browDefinition, skinFinish = style.skinFinish, freckles = style.freckles,
        )
    }
}

/**
 * Everything a look sets, at 100 % intensity: beauty values (features left out keep the user's own value), makeup
 * layers (layers left out are switched off), the style, optional coloured lenses and an optional live filter.
 */
@Serializable
data class LookRecipe(
    val beauty: Map<BeautyFeature, Int> = emptyMap(),
    val makeup: Map<MakeupFeature, MakeupLayer> = emptyMap(),
    val style: LookStyle = LookStyle(),
    /** Iris colour (ARGB) of coloured contact lenses, or null for none. */
    val eyeColor: Long? = null,
    val eyeIntensity: Int = 0,
    /** Live filter id (`LiveFilter.id`) or null. */
    val filterId: String? = null,
    val filterIntensity: Int = 0,
)

@Serializable
enum class HairStyle { LONG, WAVY, BOB, BUN }

@Serializable
enum class Accessory { NONE, HOOPS, PEARLS, TIARA }

/** How a look's illustrated avatar is dressed (skin tone, hair, backdrop), independent of the makeup itself. */
@Serializable
data class AvatarTraits(
    val skin: Long,
    val hair: Long,
    val hairStyle: HairStyle,
    val backdropTop: Long,
    val backdropBottom: Long,
    val accessory: Accessory = Accessory.NONE,
)

/** A complete, one-tap look: a built-in catalogue entry or a look the user saved. */
@Immutable
data class LookDef(
    val id: String,
    /** Localized name of built-in looks (0 for custom looks, which use [customName]). */
    @StringRes val nameRes: Int,
    val pro: Boolean,
    val tags: Set<LookTag>,
    val recipe: LookRecipe,
    val avatar: AvatarTraits,
    val customName: String? = null,
) {
    val isCustom: Boolean get() = customName != null

    /** Stable key used for favourites / recents («look:<id>»). */
    val key: String get() = keyOf(id)

    companion object {
        const val PREFIX = "look:"
        fun keyOf(id: String) = PREFIX + id
    }
}

/** A look the user saved from the Beauty panel ("customise this look" → save). */
@Serializable
data class CustomLook(
    val id: String,
    val name: String,
    val basedOn: String? = null,
    val recipe: LookRecipe,
    val avatar: AvatarTraits,
    val createdAt: Long = 0,
) {
    fun toDef(): LookDef = LookDef(id = id, nameRes = 0, pro = false, tags = setOf(LookTag.MAKEUP), recipe = recipe, avatar = avatar, customName = name)
}
