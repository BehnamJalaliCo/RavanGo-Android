package com.ravango.feature.camera

import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter

/**
 * Pro gating of camera effects. The free base set is generous (like Snapchat, the fun is free):
 * - Lenses: Big Eyes, Puffy Cheeks, Soft Glow, Freckles & Blush, Sparkles, Shades and Kitty are free; Tiny Face, Alien,
 *   Swirl, Crown and the mouth-triggered Rainbow need [ProFeature.ADVANCED_BEAUTY].
 * - Live filters: Original, Vivid, Warm, Cool, Mono, Pastel and Fade are free; Film, Noir, Sunset, Teal & Orange,
 *   Vintage and Cinema need [ProFeature.ADVANCED_BEAUTY].
 * - Background: portrait blur, colours and gradients are free; replacing the background with a photo needs
 *   [ProFeature.ADVANCED_BEAUTY].
 * The plan → feature mapping itself stays remote (Entitlements); this only names the feature per effect.
 */
object EffectsGating {
    val FEATURE: ProFeature = ProFeature.ADVANCED_BEAUTY

    /**
     * Entitlements must be stable this long before a downgrade switches Pro effects off (the provider starts with
     * its default free plan for a moment after process start).
     */
    const val SETTLE_MS = 1_500L

    /** The effects to switch off for [e] (a downgrade), or null when everything selected is allowed. */
    fun downgrade(fx: com.ravango.engine.beauty.effects.EffectsState, e: Entitlements): com.ravango.engine.beauty.effects.EffectsState? {
        val lens = fx.lens?.takeIf { lensAllowed(it, e) }
        val filter = fx.filter.takeIf { filterAllowed(it, e) } ?: LiveFilter.NONE
        val background = fx.background.takeIf { backgroundAllowed(it, e) } ?: BackgroundEffect.None
        val next = fx.copy(lens = lens, filter = filter, background = background)
        return if (next == fx) null else next
    }

    fun lensAllowed(lens: Lens, e: Entitlements): Boolean = !lens.pro || e.has(FEATURE)

    fun filterAllowed(filter: LiveFilter, e: Entitlements): Boolean = !filter.pro || e.has(FEATURE)

    fun backgroundAllowed(effect: BackgroundEffect, e: Entitlements): Boolean = effect !is BackgroundEffect.Image || e.has(FEATURE)

    /** Filters a preview swipe cycles through (Original first), locked ones left out. */
    fun swipeable(e: Entitlements): List<LiveFilter> = LiveFilter.entries.filter { filterAllowed(it, e) }

    /**
     * The filter [steps] positions away from [current] in [list] (wrapping). A filter not in the list (e.g. one that
     * was just locked) is treated as Original.
     */
    fun neighbor(list: List<LiveFilter>, current: LiveFilter, steps: Int): LiveFilter {
        if (list.isEmpty()) return LiveFilter.NONE
        val i = list.indexOf(current).coerceAtLeast(0)
        return list[((i + steps) % list.size + list.size) % list.size]
    }
}
