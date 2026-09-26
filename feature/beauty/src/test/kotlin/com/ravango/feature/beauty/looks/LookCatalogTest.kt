package com.ravango.feature.beauty.looks

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.Entitlements
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.effects.LiveFilter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LookCatalogTest {

    private val looks = LookCatalog.looks

    @Test
    fun catalogueSizeAndIds() {
        assertThat(looks.size).isAtLeast(16)
        assertThat(looks.size).isAtMost(20)
        assertThat(looks.map { it.id }.toSet()).hasSize(looks.size)
        assertThat(looks.map { it.recipe }.toSet()).hasSize(looks.size) // every look is different
        LookCatalog.featured.forEach { assertThat(LookCatalog.find(it)).isNotNull() }
        assertThat(LookCatalog.tagged(LookTag.BEAUTY)).isNotEmpty()
        assertThat(looks.all { LookTag.MAKEUP in it.tags }).isTrue()
    }

    @Test
    fun everyLookHasValidParameters() {
        for (look in looks) {
            val r = look.recipe
            r.beauty.forEach { (f, v) ->
                assertThat(v).isIn(0..100)
                if (f.bipolar) assertThat(v).isIn(30..70) // shaping stays moderate
            }
            assertThat(r.beauty[BeautyFeature.FACE_SLIM] ?: 0).isAtMost(30)
            assertThat(r.beauty[BeautyFeature.EYE_SIZE] ?: 0).isAtMost(35)
            assertThat(r.beauty[BeautyFeature.NOSE] ?: 0).isAtMost(40)
            r.makeup.values.forEach { layer ->
                assertThat(layer.intensity).isIn(1..100)
                assertThat(layer.color ushr 24).isEqualTo(0xFFL) // opaque colours
            }
            // A complete look: brows or lashes, lips and skin.
            assertThat(r.makeup.keys.any { it == MakeupFeature.LIPSTICK || it == MakeupFeature.LIP_COLOR }).isTrue()
            assertThat(r.makeup.keys).containsAnyOf(MakeupFeature.EYEBROW, MakeupFeature.EYELASHES)
            assertThat(r.makeup.keys).contains(MakeupFeature.FOUNDATION)
            val s = r.style
            assertThat(s.wing).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(s.lashVolume).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(s.lipFinish).isIn(com.google.common.collect.Range.closed(-1f, 1f))
            assertThat(s.skinFinish).isIn(com.google.common.collect.Range.closed(-1f, 1f))
            listOf(s.lowerLiner, s.shadowAccentAmount, s.shimmer, s.lipCenterAmount, s.browDefinition, s.freckles).forEach {
                assertThat(it).isIn(com.google.common.collect.Range.closed(0f, 1f))
            }
            if (r.eyeColor != null) assertThat(r.eyeIntensity).isIn(1..60)
            if (r.filterId != null) {
                assertThat(LiveFilter.fromId(r.filterId)).isNotEqualTo(LiveFilter.NONE)
                assertThat(r.filterIntensity).isIn(1..50) // a hint of grade, never a filter takeover
            }
        }
    }

    @Test
    fun gatingKeepsAboutEightFreeLooks() {
        val free = looks.filter { !it.pro }
        assertThat(free.size).isIn(7..9)
        for (look in looks) {
            assertThat(LookGating.allowed(look, Entitlements())).isEqualTo(!look.pro)
            assertThat(LookGating.allowed(look, Entitlements(features = setOf(LookGating.FEATURE)))).isTrue()
        }
        // Free looks only use free filters, so they apply completely on the free plan.
        free.mapNotNull { it.recipe.filterId }.forEach { id ->
            assertThat(LookGating.filterAllowed(LiveFilter.fromId(id), Entitlements())).isTrue()
        }
        assertThat(LookGating.filterAllowed(LiveFilter.FILM, Entitlements())).isFalse()
        assertThat(LookGating.filterAllowed(LiveFilter.FILM, Entitlements(features = setOf(ProFeature.ADVANCED_BEAUTY)))).isTrue()
    }

    @Test
    fun namesExistInEnglishAndPersian() {
        val app = RuntimeEnvironment.getApplication()
        for (look in looks) {
            assertThat(app.resources.getResourceEntryName(look.nameRes)).isEqualTo("beauty_look_${look.id}")
        }
        RuntimeEnvironment.setQualifiers("en")
        val en = looks.map { RuntimeEnvironment.getApplication().getString(it.nameRes) }
        RuntimeEnvironment.setQualifiers("fa")
        val fa = looks.map { RuntimeEnvironment.getApplication().getString(it.nameRes) }
        en.forEach { assertThat(it).matches("[A-Za-z][A-Za-z &-]+") }
        fa.forEach { name -> assertThat(name.any { it in '؀'..'ۿ' }).isTrue() }
        assertThat(en.toSet()).hasSize(looks.size)
        assertThat(fa.toSet()).hasSize(looks.size)
    }
}
