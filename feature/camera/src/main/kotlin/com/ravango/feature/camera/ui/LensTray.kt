package com.ravango.feature.camera.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.beauty.looks.LookCatalog
import com.ravango.feature.beauty.looks.LookDef
import com.ravango.feature.beauty.looks.LookGating
import com.ravango.feature.beauty.looks.LookTag
import com.ravango.feature.beauty.looks.LooksState
import com.ravango.feature.camera.EffectsGating
import com.ravango.feature.camera.R

/**
 * Categories under the lens carousel (Snapchat-style tabs), in reading order.
 *
 * How the layers combine: a **look** (complete makeup + beauty) lives in the beauty layer, a **lens** (fun AR effect)
 * and a **filter** in the effects layer — one of each can be active at once (e.g. Bold Glam + Sparkles + Warm).
 * Snapping an item replaces only the layer of its own kind; the leading "None" item clears the kinds that category
 * shows (Makeup/Beauty: the look · Fun: the lens · For you / Recents / Favorites: look and lens); Filters start
 * with Original.
 */
enum class TrayCategory(@StringRes val label: Int) {
    RECENTS(R.string.camera_tray_recents),
    FAVORITES(R.string.camera_tray_favorites),
    FOR_YOU(R.string.camera_tray_for_you),
    MAKEUP(R.string.camera_tray_makeup),
    BEAUTY(R.string.camera_tray_beauty),
    FUN(R.string.camera_tray_fun),
    FILTERS(R.string.camera_tray_filters),
}

/** One round item in the carousel. */
@Immutable
sealed interface TrayItem {
    val key: String

    /** Clears the layers of the kinds shown in the category. */
    data object None : TrayItem {
        override val key: String get() = "none"
    }

    data class Look(val look: LookDef) : TrayItem {
        override val key: String get() = look.key
    }

    data class Fx(val lens: Lens) : TrayItem {
        override val key: String get() = keyOf(lens)
    }

    data class Filter(val filter: LiveFilter) : TrayItem {
        override val key: String get() = keyOf(filter)
    }

    companion object {
        fun keyOf(lens: Lens) = "lens:" + lens.id
        fun keyOf(filter: LiveFilter) = "filter:" + filter.id
    }
}

/** What the tray shows and what is applied right now (stateless input of [LensCarousel]). */
@Immutable
data class LensTrayState(
    /** Looks feature state; null hides the look categories (lenses only, e.g. in previews). */
    val looks: LooksState? = null,
    val filter: LiveFilter = LiveFilter.NONE,
    val filterIntensity: Int = 100,
) {
    companion object {
        val LensesOnly = LensTrayState()
    }
}

/** Tray intents (defaults are no-ops for previews and screenshot tests). */
@Immutable
class LensTrayActions(
    val onLook: (LookDef) -> Unit = {},
    val onClearLook: () -> Unit = {},
    val onLookIntensity: (Int) -> Unit = {},
    val onFilter: (LiveFilter) -> Unit = {},
    val onFilterIntensity: (Int) -> Unit = {},
    val onToggleFavourite: (String) -> Unit = {},
    val onRecent: (String) -> Unit = {},
    val onRequirePro: (ProFeature) -> Unit = {},
)

internal object LensTrayLogic {

    /** Lenses mixed into «For you» between the featured looks. */
    private val featuredLenses = listOf(Lens.SPARKLES, Lens.SUNGLASSES, Lens.CAT, Lens.BIG_EYES)

    fun categories(state: LensTrayState): List<TrayCategory> =
        if (state.looks == null) listOf(TrayCategory.FUN, TrayCategory.FILTERS) else TrayCategory.entries

    fun defaultCategory(state: LensTrayState): TrayCategory = if (state.looks == null) TrayCategory.FUN else TrayCategory.FOR_YOU

    /** Items of [category] (None first, except Filters which start with Original). */
    fun items(category: TrayCategory, state: LensTrayState): List<TrayItem> {
        val looks = state.looks
        val all = looks?.looks ?: emptyList()
        val body: List<TrayItem> = when (category) {
            TrayCategory.RECENTS -> looks?.recents.orEmpty().mapNotNull { resolve(it, all) }
            TrayCategory.FAVORITES -> looks?.favourites.orEmpty().mapNotNull { resolve(it, all) }
            TrayCategory.FOR_YOU -> {
                val featured = all.filter { it.isCustom }.map { TrayItem.Look(it) } +
                    LookCatalog.featured.mapNotNull { id -> all.firstOrNull { it.id == id } }.map { TrayItem.Look(it) }
                interleave(featured, featuredLenses.map { TrayItem.Fx(it) })
            }
            TrayCategory.MAKEUP -> all.filter { LookTag.MAKEUP in it.tags }.map { TrayItem.Look(it) }
            TrayCategory.BEAUTY -> all.filter { LookTag.BEAUTY in it.tags }.map { TrayItem.Look(it) }
            TrayCategory.FUN -> Lens.entries.map { TrayItem.Fx(it) }
            TrayCategory.FILTERS -> return LiveFilter.entries.map { TrayItem.Filter(it) }
        }
        return listOf(TrayItem.None) + body
    }

    /** Every third slot is a lens: look, look, lens, look, look, lens… */
    private fun interleave(looks: List<TrayItem>, lenses: List<TrayItem>): List<TrayItem> {
        val out = ArrayList<TrayItem>(looks.size + lenses.size)
        var l = 0
        looks.forEachIndexed { i, item ->
            out += item
            if (i % 2 == 1 && l < lenses.size) out += lenses[l++]
        }
        while (l < lenses.size) out += lenses[l++]
        return out
    }

    fun resolve(key: String, looks: List<LookDef>): TrayItem? = when {
        key.startsWith(LookDef.PREFIX) -> looks.firstOrNull { it.key == key }?.let { TrayItem.Look(it) }
        key.startsWith("lens:") -> Lens.fromId(key.removePrefix("lens:"))?.let { TrayItem.Fx(it) }
        key.startsWith("filter:") -> LiveFilter.entries.firstOrNull { TrayItem.keyOf(it) == key }?.let { TrayItem.Filter(it) }
        else -> null
    }

    /** Index of the item that is applied now (the carousel opens on it), or 0. Looks win over lenses. */
    fun appliedIndex(items: List<TrayItem>, lens: Lens?, state: LensTrayState): Int {
        val activeLook = state.looks?.activeId
        items.indexOfFirst { it is TrayItem.Look && it.look.id == activeLook }.takeIf { activeLook != null && it >= 0 }?.let { return it }
        items.indexOfFirst { it is TrayItem.Fx && it.lens == lens }.takeIf { lens != null && it >= 0 }?.let { return it }
        items.indexOfFirst { it is TrayItem.Filter && it.filter == state.filter }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    fun locked(item: TrayItem, e: Entitlements): Boolean = when (item) {
        TrayItem.None -> false
        is TrayItem.Look -> !LookGating.allowed(item.look, e)
        is TrayItem.Fx -> !EffectsGating.lensAllowed(item.lens, e)
        is TrayItem.Filter -> !EffectsGating.filterAllowed(item.filter, e)
    }

    fun requiredFeature(item: TrayItem): ProFeature = when (item) {
        is TrayItem.Look -> LookGating.FEATURE
        else -> EffectsGating.FEATURE
    }

    /** Whether "None" in [category] clears the look / the lens. */
    fun noneClearsLook(category: TrayCategory) = category != TrayCategory.FUN && category != TrayCategory.FILTERS
    fun noneClearsLens(category: TrayCategory) = category != TrayCategory.MAKEUP && category != TrayCategory.BEAUTY && category != TrayCategory.FILTERS
}
