package com.ravango.feature.beauty

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.BeautyState
import com.ravango.core.model.ProFeature
import org.junit.Test

class BeautyTabsTest {

    @Test
    fun looksIsTheFirstTab() {
        assertThat(BeautyTab.entries.first()).isEqualTo(BeautyTab.LOOKS)
        assertThat(BeautyUiState().tab).isEqualTo(BeautyTab.LOOKS)
        assertThat(BeautyCatalog.items(BeautyTab.LOOKS)).isEmpty()
    }

    @Test
    fun eyeColorItemIsGatedAndLivesInEyesTab() {
        assertThat(BeautyCatalog.items(BeautyTab.EYES)).contains(BeautyItem.EyeColor)
        assertThat(BeautyCatalog.requiredPro(BeautyItem.EyeColor)).isEqualTo(ProFeature.MAKEUP)
        val s = BeautyState()
        assertThat(BeautyCatalog.withValue(s, BeautyItem.EyeColor, 70)).isEqualTo(s)
    }

    @Test
    fun lookFiltersSplitTheCatalogue() {
        val ui = BeautyUiState()
        val light = ui.filteredLooks(LookFilter.LIGHT)
        val full = ui.filteredLooks(LookFilter.FULL)
        assertThat(light).isNotEmpty()
        assertThat(full).isNotEmpty()
        assertThat(light.intersect(full.toSet())).isEmpty()
        assertThat(light.size + full.size).isEqualTo(ui.filteredLooks(LookFilter.ALL).size)
        assertThat(ui.filteredLooks(LookFilter.MINE)).isEmpty()
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
