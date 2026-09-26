package com.ravango.engine.camera.session

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.camera.capability.SensorArea
import org.junit.Test

class MeteringAndWhiteBalanceTest {

    private val active = SensorArea(0, 0, 4000, 3000)

    @Test
    fun `zoom crop region is centered`() {
        assertThat(MeteringMath.cropRegionForZoom(active, 2f)).isEqualTo(SensorRect(1000, 750, 3000, 2250))
        assertThat(MeteringMath.cropRegionForZoom(active, 0.5f)).isEqualTo(SensorRect(0, 0, 4000, 3000))
    }

    @Test
    fun `stream field of view is the aspect crop of the zoom region`() {
        assertThat(MeteringMath.streamFieldOfView(active, null, 16f / 9f)).isEqualTo(SensorRect(0, 375, 4000, 2625))
        val crop = MeteringMath.cropRegionForZoom(active, 2f)
        assertThat(MeteringMath.streamFieldOfView(active, crop, 16f / 9f)).isEqualTo(SensorRect(1000, 937, 3000, 2062))
        assertThat(MeteringMath.streamFieldOfView(active, null, 1f)).isEqualTo(SensorRect(500, 0, 3500, 3000))
    }

    @Test
    fun `metering rect is centered on the point and stays inside the array`() {
        val fov = MeteringMath.streamFieldOfView(active, null, 16f / 9f)
        val center = MeteringMath.meteringRect(0.5f, 0.5f, fov, active)
        assertThat((center.left + center.right) / 2).isEqualTo(2000)
        assertThat((center.top + center.bottom) / 2).isEqualTo(1500)
        val corner = MeteringMath.meteringRect(0f, 0f, fov, active)
        assertThat(corner.left).isEqualTo(0)
        assertThat(corner.width).isEqualTo(center.width)
        val far = MeteringMath.meteringRect(1f, 1f, SensorRect(0, 0, 4000, 3000), active)
        assertThat(far.right).isEqualTo(3999)
        assertThat(far.bottom).isEqualTo(2999)
    }

    @Test
    fun `daylight gains are neutral and tungsten boosts blue`() {
        val daylight = WhiteBalanceMath.rggbGains(6600)
        assertThat(daylight[0]).isWithin(0.05f).of(2f)
        assertThat(daylight[1]).isEqualTo(1f)
        assertThat(daylight[3]).isWithin(0.05f).of(2f)
        val tungsten = WhiteBalanceMath.rggbGains(3000)
        assertThat(tungsten[3]).isGreaterThan(tungsten[0])
        val shade = WhiteBalanceMath.rggbGains(8000)
        assertThat(shade[0]).isGreaterThan(shade[3])
    }

    @Test
    fun `blue gain decreases as temperature rises`() {
        var last = Float.MAX_VALUE
        for (k in 2500..9500 step 500) {
            val blue = WhiteBalanceMath.rggbGains(k)[3]
            assertThat(blue).isAtMost(last)
            last = blue
        }
    }
}
