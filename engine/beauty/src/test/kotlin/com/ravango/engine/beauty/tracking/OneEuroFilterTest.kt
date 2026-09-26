package com.ravango.engine.beauty.tracking

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.beauty.TestFaces
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class OneEuroFilterTest {

    @Test
    fun firstSampleIsReturnedUnchanged() {
        val f = OneEuroFilter()
        assertThat(f.filter(0.42f, 0.0)).isEqualTo(0.42f)
        assertThat(f.velocity).isEqualTo(0f)
    }

    @Test
    fun constantSignalStaysConstant() {
        val f = OneEuroFilter()
        var out = 0f
        for (i in 0 until 60) out = f.filter(0.3f, i / 30.0)
        assertThat(out).isWithin(1e-6f).of(0.3f)
        assertThat(abs(f.velocity)).isLessThan(1e-4f)
    }

    @Test
    fun reducesJitterOfStationarySignal() {
        val rnd = Random(7)
        val f = OneEuroFilter(minCutoff = 1.5f, beta = 2f)
        var inVar = 0.0
        var outVar = 0.0
        for (i in 0 until 300) {
            val noise = (rnd.nextGaussian() * 0.004).toFloat()
            val out = f.filter(0.5f + noise, i / 30.0)
            if (i > 30) {
                inVar += noise * noise.toDouble()
                outVar += (out - 0.5f).toDouble() * (out - 0.5f)
            }
        }
        assertThat(sqrt(outVar)).isLessThan(sqrt(inVar) * 0.6)
    }

    @Test
    fun speedCoefficientReducesLagWhenMoving() {
        val slow = OneEuroFilter(minCutoff = 1f, beta = 0f)
        val fast = OneEuroFilter(minCutoff = 1f, beta = 20f)
        var slowErr = 0f
        var fastErr = 0f
        for (i in 0 until 60) {
            val t = i / 30.0
            val x = (t * 0.8).toFloat() // steady motion: 0.8 units per second
            slowErr = abs(slow.filter(x, t) - x)
            fastErr = abs(fast.filter(x, t) - x)
        }
        assertThat(fastErr).isLessThan(slowErr * 0.5f)
    }

    @Test
    fun nonIncreasingTimestampKeepsEstimate() {
        val f = OneEuroFilter()
        f.filter(0.1f, 1.0)
        val v = f.filter(0.2f, 1.1)
        assertThat(f.filter(0.9f, 1.1)).isEqualTo(v)
        assertThat(f.filter(0.9f, 1.05)).isEqualTo(v)
    }

    @Test
    fun stabilizerExtrapolatesWithVelocity() {
        val s = LandmarkStabilizer(predictionGain = 1f, maxLeadSec = 0.1f)
        val out = FaceLandmarks()
        // Face moving right at 0.3 units/s.
        for (i in 0 until 40) {
            val t = i / 30.0
            s.onMeasurement(TestFaces.frontal(cx = 0.5f + (0.3 * t).toFloat()), t, 0.16f)
        }
        val lastT = 39 / 30.0
        s.predict(lastT, out)
        val atMeasurement = out.x(Contour.NOSE_BRIDGE, 0)
        s.predict(lastT + 0.05, out)
        val ahead = out.x(Contour.NOSE_BRIDGE, 0)
        assertThat(ahead - atMeasurement).isWithin(0.004f).of(0.3f * 0.05f)
        // Lead is capped at maxLeadSec.
        s.predict(lastT + 1.0, out)
        assertThat(out.x(Contour.NOSE_BRIDGE, 0) - atMeasurement).isLessThan(0.3f * 0.11f)
    }

    @Test
    fun stabilizerResetsOnLargeJump() {
        val s = LandmarkStabilizer()
        val out = FaceLandmarks()
        for (i in 0 until 10) s.onMeasurement(TestFaces.frontal(cx = 0.3f), i / 30.0, 0.16f)
        s.onMeasurement(TestFaces.frontal(cx = 0.9f), 10 / 30.0, 0.16f)
        s.predict(10 / 30.0, out)
        // A new face snaps immediately instead of sliding across the frame.
        assertThat(out.x(Contour.NOSE_BRIDGE, 0)).isWithin(1e-4f).of(0.9f)
    }

    @Test
    fun presenceFadesOverRampDuration() {
        val fader = PresenceFader(0.15f)
        var v = 0f
        repeat(4) { v = fader.update(true, 1 / 30f) }
        assertThat(v).isWithin(1e-4f).of(4f / 30f / 0.15f)
        repeat(10) { v = fader.update(true, 1 / 30f) }
        assertThat(v).isEqualTo(1f)
        repeat(5) { v = fader.update(false, 1 / 30f) }
        assertThat(v).isLessThan(0.0001f)
    }
}
