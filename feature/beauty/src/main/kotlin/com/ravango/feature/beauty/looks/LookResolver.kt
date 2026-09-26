package com.ravango.feature.beauty.looks

import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Entitlements
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.EyeColorSetting
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.makeup.MakeupStyle
import kotlin.math.roundToInt

/** Everything the engines receive for one look at one intensity. */
data class ResolvedLook(
    val state: BeautyState,
    val style: MakeupStyle,
    val eyeColor: EyeColorSetting,
    /** Live filter of the look (null = the look does not touch the filter). */
    val filter: LiveFilter?,
    val filterIntensity: Int,
)

/**
 * Pure look math: a look at intensity 0..100 on top of the user's own [base] settings.
 * - beauty values the look sets move from the base value towards the look's value (0 % = base, 100 % = look);
 *   values it does not set keep the base value;
 * - makeup layers are the look's layers scaled by intensity; all other layers are off (keeping their colours);
 * - style amounts, coloured-lens strength and filter strength scale; style shapes (wing, lash volume…) do not.
 */
object LookResolver {

    fun resolve(recipe: LookRecipe, intensity: Int, base: BeautyState, baseEye: EyeColorSetting = EyeColorSetting()): ResolvedLook {
        val s = intensity.coerceIn(0, 100) / 100f
        val beauty = HashMap<BeautyFeature, Int>(base.beauty)
        for ((f, target) in recipe.beauty) {
            val from = base.intensity(f)
            beauty[f] = (from + (target.coerceIn(0, 100) - from) * s).roundToInt().coerceIn(0, 100)
        }
        val makeup = MakeupFeature.entries.associateWith { f ->
            val layer = recipe.makeup[f]
            if (layer == null) MakeupLayer(0, base.layer(f).color) else MakeupLayer((layer.intensity.coerceIn(0, 100) * s).roundToInt(), layer.color)
        }
        val eye = if (recipe.eyeColor != null) {
            EyeColorSetting(intensity = (recipe.eyeIntensity.coerceIn(0, 100) * s).roundToInt(), color = recipe.eyeColor)
        } else {
            baseEye.copy(intensity = 0)
        }
        val filter = recipe.filterId?.let { LiveFilter.fromId(it) }?.takeIf { it != LiveFilter.NONE }
        return ResolvedLook(
            state = BeautyState(enabled = true, beauty = beauty, makeup = makeup),
            style = recipe.style.toEngine(s),
            eyeColor = eye,
            filter = filter,
            filterIntensity = if (filter == null) 0 else (recipe.filterIntensity.coerceIn(0, 100) * s).roundToInt(),
        )
    }

    /** The full current settings as a recipe (for "save as my look"). */
    fun capture(state: BeautyState, style: MakeupStyle, eye: EyeColorSetting, filter: LiveFilter?, filterIntensity: Int): LookRecipe =
        LookRecipe(
            beauty = BeautyFeature.entries.associateWith { state.intensity(it) },
            makeup = MakeupFeature.entries.mapNotNull { f -> state.layer(f).takeIf { it.intensity > 0 }?.let { f to it } }.toMap(),
            style = LookStyle.from(style),
            eyeColor = if (eye.active) eye.color else null,
            eyeIntensity = if (eye.active) eye.intensity else 0,
            filterId = filter?.takeIf { it != LiveFilter.NONE && filterIntensity > 0 }?.id,
            filterIntensity = if (filter != null && filter != LiveFilter.NONE) filterIntensity else 0,
        )
}

/**
 * Pro gating of looks (the plan → feature mapping stays remote). About 8 built-in looks are free — a free look
 * applies completely (including its face shaping and makeup) — the rest need [FEATURE]. A look's Pro live filter
 * additionally needs the camera effects entitlement, else the look applies without it.
 */
object LookGating {
    val FEATURE: ProFeature = ProFeature.MAKEUP
    private val FILTER_FEATURE: ProFeature = ProFeature.ADVANCED_BEAUTY

    fun allowed(look: LookDef, e: Entitlements): Boolean = !look.pro || e.has(FEATURE)

    fun filterAllowed(filter: LiveFilter, e: Entitlements): Boolean = !filter.pro || e.has(FILTER_FEATURE)
}
