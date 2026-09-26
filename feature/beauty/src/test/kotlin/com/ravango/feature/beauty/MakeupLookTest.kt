package com.ravango.feature.beauty

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.MakeupLayer
import com.ravango.core.model.ProFeature
import org.junit.Test

class MakeupLookTest {

    @Test
    fun lookReplacesMakeupAndKeepsBeauty() {
        val start = BeautyState(
            beauty = mapOf(BeautyFeature.SMOOTH_SKIN to 60, BeautyFeature.FACE_SLIM to 30),
            makeup = mapOf(MakeupFeature.BLUSH to MakeupLayer(80, 0xFFD81B60)),
        )
        val bold = MakeupLook.BOLD_LIPS.applyTo(start)
        assertThat(bold.intensity(BeautyFeature.SMOOTH_SKIN)).isEqualTo(60)
        assertThat(bold.intensity(BeautyFeature.FACE_SLIM)).isEqualTo(30)
        assertThat(bold.layer(MakeupFeature.LIPSTICK).intensity).isEqualTo(85)
        // Layers the look does not use are switched off but keep the user's colour.
        assertThat(bold.layer(MakeupFeature.BLUSH)).isEqualTo(MakeupLayer(0, 0xFFD81B60))
        assertThat(MakeupLook.activeIn(bold)).isEqualTo(MakeupLook.BOLD_LIPS)
        assertThat(MakeupLook.activeIn(start)).isNull()
    }

    @Test
    fun everyLookIsDistinctAndRecognized() {
        for (look in MakeupLook.entries) {
            val s = look.applyTo(BeautyState())
            assertThat(MakeupLook.activeIn(s)).isEqualTo(look)
            assertThat(MakeupLook.hasMakeup(s)).isTrue()
            assertThat(look.swatches).isNotEmpty()
            // Looks only need the makeup entitlement.
            assertThat(BeautyCatalog.missingEntitlement(s) { it != ProFeature.MAKEUP }).isEqualTo(ProFeature.MAKEUP)
        }
        val cleared = MakeupLook.cleared(MakeupLook.SOFT_GLAM.applyTo(BeautyState()))
        assertThat(MakeupLook.hasMakeup(cleared)).isFalse()
        assertThat(MakeupLook.activeIn(cleared)).isNull()
    }

    @Test
    fun eyeColorItemIsGatedAndLivesInEyesTab() {
        assertThat(BeautyCatalog.items(BeautyTab.EYES)).contains(BeautyItem.EyeColor)
        assertThat(BeautyCatalog.requiredPro(BeautyItem.EyeColor)).isEqualTo(ProFeature.MAKEUP)
        // Not part of BeautyState: editing it through the catalog leaves the state untouched.
        val s = BeautyState()
        assertThat(BeautyCatalog.withValue(s, BeautyItem.EyeColor, 70)).isEqualTo(s)
        assertThat(BeautyCatalog.items(BeautyTab.LOOKS)).isEmpty()
    }

    @Test
    fun sliderDetentsSnapToNeutralAndSweetSpot() {
        assertThat(snapDetent(49, neutral = 50, sweetSpot = 50)).isEqualTo(50)
        assertThat(snapDetent(2, neutral = 0, sweetSpot = 35)).isEqualTo(0)
        assertThat(snapDetent(33, neutral = 0, sweetSpot = 35)).isEqualTo(35)
        assertThat(snapDetent(40, neutral = 0, sweetSpot = 35)).isEqualTo(40)
        assertThat(snapDetent(104, neutral = 0, sweetSpot = 35)).isEqualTo(100)
    }
}
