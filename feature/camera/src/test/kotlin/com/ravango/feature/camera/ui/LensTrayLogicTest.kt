package com.ravango.feature.camera.ui

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.beauty.looks.LookCatalog
import com.ravango.feature.beauty.looks.LookGating
import com.ravango.feature.beauty.looks.LookTag
import com.ravango.feature.beauty.looks.LooksState
import org.junit.Test

class LensTrayLogicTest {

    private val looks = LooksState(
        activeId = "latte",
        favourites = listOf("look:bold_glam", "lens:cat", "filter:warm", "look:deleted"),
        recents = listOf("lens:sparkles", "look:k_beauty"),
        loaded = true,
    )
    private val tray = LensTrayState(looks = looks, filter = LiveFilter.WARM)

    @Test
    fun categoriesAndDefaults() {
        assertThat(LensTrayLogic.categories(tray)).containsExactlyElementsIn(TrayCategory.entries).inOrder()
        assertThat(LensTrayLogic.defaultCategory(tray)).isEqualTo(TrayCategory.FOR_YOU)
        // Without the looks feature the tray is lenses + filters only.
        assertThat(LensTrayLogic.categories(LensTrayState.LensesOnly)).containsExactly(TrayCategory.FUN, TrayCategory.FILTERS).inOrder()
        assertThat(LensTrayLogic.defaultCategory(LensTrayState.LensesOnly)).isEqualTo(TrayCategory.FUN)
    }

    @Test
    fun itemsPerCategory() {
        val makeup = LensTrayLogic.items(TrayCategory.MAKEUP, tray)
        assertThat(makeup.first()).isEqualTo(TrayItem.None)
        assertThat(makeup.drop(1).map { (it as TrayItem.Look).look.id }).isEqualTo(LookCatalog.tagged(LookTag.MAKEUP).map { it.id })
        val beauty = LensTrayLogic.items(TrayCategory.BEAUTY, tray).drop(1)
        assertThat(beauty.all { it is TrayItem.Look && LookTag.BEAUTY in it.look.tags }).isTrue()
        assertThat(LensTrayLogic.items(TrayCategory.FUN, tray).drop(1).map { (it as TrayItem.Fx).lens }).isEqualTo(Lens.entries)
        // Filters start with Original instead of None.
        assertThat(LensTrayLogic.items(TrayCategory.FILTERS, tray).map { (it as TrayItem.Filter).filter }).isEqualTo(LiveFilter.entries)
        // Favourites keep their order and skip keys that no longer resolve.
        assertThat(LensTrayLogic.items(TrayCategory.FAVORITES, tray).map { it.key })
            .containsExactly("none", "look:bold_glam", "lens:cat", "filter:warm").inOrder()
        assertThat(LensTrayLogic.items(TrayCategory.RECENTS, tray).map { it.key })
            .containsExactly("none", "lens:sparkles", "look:k_beauty").inOrder()
        // «For you» mixes featured looks and lenses, looks first.
        val forYou = LensTrayLogic.items(TrayCategory.FOR_YOU, tray).drop(1)
        assertThat(forYou.first()).isInstanceOf(TrayItem.Look::class.java)
        assertThat(forYou.count { it is TrayItem.Fx }).isAtLeast(2)
        assertThat(forYou.map { it.key }.toSet()).hasSize(forYou.size)
    }

    @Test
    fun opensOnTheAppliedItem() {
        val makeup = LensTrayLogic.items(TrayCategory.MAKEUP, tray)
        assertThat((makeup[LensTrayLogic.appliedIndex(makeup, null, tray)] as TrayItem.Look).look.id).isEqualTo("latte")
        val fun_ = LensTrayLogic.items(TrayCategory.FUN, tray)
        assertThat(fun_[LensTrayLogic.appliedIndex(fun_, Lens.CAT, tray)]).isEqualTo(TrayItem.Fx(Lens.CAT))
        assertThat(LensTrayLogic.appliedIndex(fun_, null, tray)).isEqualTo(0)
        val filters = LensTrayLogic.items(TrayCategory.FILTERS, tray)
        assertThat(filters[LensTrayLogic.appliedIndex(filters, null, tray)]).isEqualTo(TrayItem.Filter(LiveFilter.WARM))
    }

    @Test
    fun noneClearsTheKindsOfItsCategory() {
        assertThat(LensTrayLogic.noneClearsLook(TrayCategory.MAKEUP)).isTrue()
        assertThat(LensTrayLogic.noneClearsLens(TrayCategory.MAKEUP)).isFalse()
        assertThat(LensTrayLogic.noneClearsLook(TrayCategory.FUN)).isFalse()
        assertThat(LensTrayLogic.noneClearsLens(TrayCategory.FUN)).isTrue()
        assertThat(LensTrayLogic.noneClearsLook(TrayCategory.FOR_YOU)).isTrue()
        assertThat(LensTrayLogic.noneClearsLens(TrayCategory.FOR_YOU)).isTrue()
    }

    @Test
    fun gatingPerItemKind() {
        val free = Entitlements()
        val proLook = TrayItem.Look(LookCatalog.looks.first { it.pro })
        val freeLook = TrayItem.Look(LookCatalog.looks.first { !it.pro })
        assertThat(LensTrayLogic.locked(proLook, free)).isTrue()
        assertThat(LensTrayLogic.locked(freeLook, free)).isFalse()
        assertThat(LensTrayLogic.locked(proLook, Entitlements(features = setOf(LookGating.FEATURE)))).isFalse()
        assertThat(LensTrayLogic.locked(TrayItem.Fx(Lens.CROWN), free)).isTrue()
        assertThat(LensTrayLogic.locked(TrayItem.Filter(LiveFilter.CINEMA), free)).isTrue()
        assertThat(LensTrayLogic.locked(TrayItem.None, free)).isFalse()
        assertThat(LensTrayLogic.requiredFeature(proLook)).isEqualTo(ProFeature.MAKEUP)
        assertThat(LensTrayLogic.requiredFeature(TrayItem.Fx(Lens.CROWN))).isEqualTo(ProFeature.ADVANCED_BEAUTY)
    }
}
