package com.ravango.core.media.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class BiquadTest {
    private val sr = 48_000

    @Test
    fun highPassResponse() {
        val hp = BiquadCoefficients.highPass(sr, 80.0, 0.7071)
        assertThat(hp.magnitudeDb(80.0, sr)).isWithin(0.1).of(-3.01)
        assertThat(hp.magnitudeDb(20.0, sr)).isLessThan(-22.0)
        assertThat(hp.magnitudeDb(1000.0, sr)).isWithin(0.05).of(0.0)
        assertThat(hp.magnitudeDb(10000.0, sr)).isWithin(0.01).of(0.0)
    }

    @Test
    fun presencePeakAndDeMudShelf() {
        val peak = BiquadCoefficients.peaking(sr, 3000.0, 3.0, 1.0)
        assertThat(peak.magnitudeDb(3000.0, sr)).isWithin(0.01).of(3.0)
        assertThat(peak.magnitudeDb(100.0, sr)).isWithin(0.1).of(0.0)
        val shelf = BiquadCoefficients.lowShelf(sr, 250.0, -2.0)
        assertThat(shelf.magnitudeDb(30.0, sr)).isWithin(0.1).of(-2.0)
        assertThat(shelf.magnitudeDb(250.0, sr)).isWithin(0.1).of(-1.0)
        assertThat(shelf.magnitudeDb(5000.0, sr)).isWithin(0.1).of(0.0)
    }

    @Test
    fun timeDomainFilteringMatchesResponse() {
        val filter = Biquad(BiquadCoefficients.highPass(sr, 80.0), channels = 2)
        fun gainAt(freq: Double): Double {
            filter.reset()
            var sumIn = 0.0
            var sumOut = 0.0
            val n = sr // 1 s
            for (i in 0 until n) {
                val x = (0.5 * sin(2 * PI * freq * i / sr)).toFloat()
                val yL = filter.process(x, 0)
                val yR = filter.process(x, 1)
                assertThat(yL).isEqualTo(yR) // independent but identical per-channel state
                if (i > n / 2) { sumIn += x * x; sumOut += yL * yL }
            }
            return 10 * log10(sumOut / sumIn)
        }
        assertThat(gainAt(1000.0)).isWithin(0.1).of(0.0)
        assertThat(gainAt(30.0)).isLessThan(-15.0)
    }
}
