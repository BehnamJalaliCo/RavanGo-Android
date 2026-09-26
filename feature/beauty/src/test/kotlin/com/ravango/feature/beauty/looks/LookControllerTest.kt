package com.ravango.feature.beauty.looks

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.AiOperation
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Entitlements
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.beauty.BeautyEngine
import com.ravango.engine.beauty.BeautyStatus
import com.ravango.engine.beauty.EyeColorSetting
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.CameraEffects
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.makeup.MakeupStyle
import com.ravango.engine.render.GlFrameProcessor
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlTextureFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LookControllerTest {

    private class FakeEngine : BeautyEngine {
        override val processor: GlFrameProcessor = object : GlFrameProcessor {
            override fun onAttach(context: GlProcessingContext) = Unit
            override fun process(input: GlTextureFrame) = input
            override fun onDetach() = Unit
        }
        override val status = MutableStateFlow(BeautyStatus())
        override val state = MutableStateFlow(BeautyState())
        override val previewBypass = MutableStateFlow(false)
        override val eyeColor = MutableStateFlow(EyeColorSetting())
        override val makeupStyle = MutableStateFlow(MakeupStyle.Default)
        override fun setState(state: BeautyState) { this.state.value = state }
        override fun setEyeColor(setting: EyeColorSetting) { eyeColor.value = setting }
        override fun setMakeupStyle(style: MakeupStyle) { makeupStyle.value = style }
        override fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean) = Unit
        override fun setRecording(recording: Boolean) = Unit
    }

    private class FakeEffects : CameraEffects {
        override val effects = MutableStateFlow(EffectsState())
        override val effectsStatus: StateFlow<EffectsStatus> = MutableStateFlow(EffectsStatus())
        override val hasBackgroundImage: StateFlow<Boolean> = MutableStateFlow(false)
        override fun setLens(lens: Lens?) { effects.value = effects.value.copy(lens = lens) }
        override fun setFilter(filter: LiveFilter) { effects.value = effects.value.copy(filter = filter) }
        override fun setFilterIntensity(intensity: Int) { effects.value = effects.value.copy(filterIntensity = intensity) }
        override fun setFilterSwipe(swipe: FilterSwipe?) = Unit
        override fun setBackground(effect: BackgroundEffect) = Unit
        override suspend fun setBackgroundImage(uri: Uri) = false
    }

    private class FakeEntitlements(e: Entitlements = Entitlements()) : EntitlementProvider {
        override val entitlements = MutableStateFlow(e)
        override suspend fun tryConsumeAiCredits(operation: AiOperation, units: Int) = true
        override suspend fun refundAiCredits(operation: AiOperation, units: Int) = Unit
    }

    private class MemoryStore(initial: LookPrefs = LookPrefs()) : LookStore {
        val data = MutableStateFlow(initial)
        override val prefs: Flow<LookPrefs> = data
        override suspend fun update(transform: (LookPrefs) -> LookPrefs) { data.value = transform(data.value) }
    }

    private val userState = BeautyState(
        beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 25, BeautyFeature.SHARPEN to 10),
        makeup = mapOf(MakeupFeature.BLUSH to MakeupLayer(30, 0xFFF48FB1)),
    )

    private class Env(val scope: TestScope, val engine: FakeEngine, val effects: FakeEffects, val ent: FakeEntitlements, val store: MemoryStore, val controller: LookController)

    private fun TestScope.env(prefs: LookPrefs = LookPrefs(), e: Entitlements = Entitlements()): Env {
        val engine = FakeEngine().apply { setState(userState) }
        val effects = FakeEffects().apply { setFilter(LiveFilter.COOL); setFilterIntensity(55) }
        val ent = FakeEntitlements(e)
        val store = MemoryStore(prefs)
        val controller = LookController(engine, effects, ent, store, backgroundScope)
        runCurrent()
        return Env(this, engine, effects, ent, store, controller)
    }

    @Test
    fun applyScaleAndClearRestoreTheUsersSettings() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val latte = LookCatalog.find("latte")!!
        assertThat(env.controller.apply("latte")).isEqualTo(LookResult.Applied)
        val full = LookResolver.resolve(latte.recipe, 100, userState)
        assertThat(env.engine.state.value).isEqualTo(full.state)
        assertThat(env.engine.makeupStyle.value).isEqualTo(full.style)
        assertThat(env.effects.effects.value.filter).isEqualTo(LiveFilter.WARM)
        assertThat(env.controller.state.value.activeId).isEqualTo("latte")

        env.controller.setIntensity(40)
        assertThat(env.engine.state.value).isEqualTo(LookResolver.resolve(latte.recipe, 40, userState).state)
        assertThat(env.effects.effects.value.filterIntensity).isEqualTo(12)
        advanceTimeBy(1_000)
        assertThat(env.store.data.value.activeId).isEqualTo("latte")
        assertThat(env.store.data.value.intensity).isEqualTo(40)
        assertThat(env.store.data.value.base).isEqualTo(userState)

        // Switching looks keeps the original base.
        env.controller.apply("k_beauty")
        assertThat(env.engine.state.value).isEqualTo(LookResolver.resolve(LookCatalog.find("k_beauty")!!.recipe, 40, userState).state)

        env.controller.clear()
        assertThat(env.engine.state.value).isEqualTo(userState)
        assertThat(env.engine.makeupStyle.value).isEqualTo(MakeupStyle.Default)
        assertThat(env.effects.effects.value.filter).isEqualTo(LiveFilter.COOL)
        assertThat(env.effects.effects.value.filterIntensity).isEqualTo(55)
        assertThat(env.controller.state.value.activeId).isNull()
        advanceTimeBy(1_000)
        assertThat(env.store.data.value.activeId).isNull()
    }

    @Test
    fun proLooksAreLockedWithoutTheEntitlement() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val pro = LookCatalog.looks.first { it.pro }
        assertThat(env.controller.apply(pro.id)).isEqualTo(LookResult.Locked(LookGating.FEATURE))
        assertThat(env.engine.state.value).isEqualTo(userState)
        assertThat(env.controller.apply("nope")).isEqualTo(LookResult.NotFound)

        // With Pro it applies; a downgrade removes it again.
        env.ent.entitlements.value = Entitlements(features = setOf(LookGating.FEATURE))
        assertThat(env.controller.apply(pro.id)).isEqualTo(LookResult.Applied)
        env.ent.entitlements.value = Entitlements()
        runCurrent()
        assertThat(env.controller.state.value.activeId).isNull()
        assertThat(env.engine.state.value).isEqualTo(userState)
    }

    @Test
    fun fineTuningMarksTheLookCustomisedAndResetDropsIt() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.controller.apply("soft_glam")
        advanceTimeBy(500)
        assertThat(env.controller.state.value.customised).isFalse()
        val s = env.engine.state.value
        env.engine.setState(s.copy(makeup = s.makeup + (MakeupFeature.LIPSTICK to MakeupLayer(20, 0xFFC4122F))))
        advanceTimeBy(500)
        assertThat(env.controller.state.value.customised).isTrue()
        // "Reset all" in the Beauty panel removes the makeup: the look is gone and so is its style.
        env.engine.setState(BeautyState())
        advanceTimeBy(500)
        assertThat(env.controller.state.value.activeId).isNull()
        assertThat(env.engine.makeupStyle.value).isEqualTo(MakeupStyle.Default)
    }

    @Test
    fun favouritesAndRecentsPersist() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.controller.toggleFavourite("look:latte")
        env.controller.toggleFavourite("lens:cat")
        env.controller.toggleFavourite("look:latte")
        env.controller.toggleFavourite("look:goth")
        repeat(15) { env.controller.recordRecent("look:r$it") }
        env.controller.recordRecent("look:r3")
        runCurrent()
        assertThat(env.store.data.value.favourites).containsExactly("look:goth", "lens:cat").inOrder()
        val recents = env.store.data.value.recents
        assertThat(recents).hasSize(LookPrefs.MAX_RECENTS)
        assertThat(recents.first()).isEqualTo("look:r3")
        assertThat(recents.count { it == "look:r3" }).isEqualTo(1)

        // A new controller (next launch) restores them, and the active look's style.
        val restored = env(
            LookPrefs(favourites = listOf("look:goth"), recents = listOf("look:latte"), activeId = "bold_glam", intensity = 70, base = userState),
        )
        assertThat(restored.controller.state.value.favourites).containsExactly("look:goth")
        assertThat(restored.controller.state.value.recents).containsExactly("look:latte")
        assertThat(restored.controller.state.value.activeId).isEqualTo("bold_glam")
        assertThat(restored.controller.state.value.intensity).isEqualTo(70)
        assertThat(restored.engine.makeupStyle.value).isEqualTo(LookCatalog.find("bold_glam")!!.recipe.style.toEngine(0.7f))
    }

    @Test
    fun savedLooksAppearFirstAndCanBeDeleted() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.controller.apply("bold_glam")
        val saved = env.controller.saveCurrent("  My glam  ")!!
        assertThat(saved.customName).isEqualTo("My glam")
        assertThat(env.controller.state.value.looks.first()).isEqualTo(saved)
        assertThat(env.controller.state.value.activeId).isEqualTo(saved.id)
        assertThat(env.store.data.value.custom.single().basedOn).isEqualTo("bold_glam")
        assertThat(env.controller.saveCurrent("   ")).isNull()

        env.controller.deleteCustom(saved.id)
        runCurrent()
        assertThat(env.controller.state.value.looks.none { it.id == saved.id }).isTrue()
        assertThat(env.store.data.value.custom).isEmpty()
        assertThat(env.controller.state.value.activeId).isNull()
    }
}
