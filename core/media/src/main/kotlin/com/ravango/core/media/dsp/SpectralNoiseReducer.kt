package com.ravango.core.media.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Streaming spectral noise reduction for interleaved float PCM.
 *
 * - STFT: [fftSize] frame (512 at 44.1/48 kHz), 50 % overlap, √Hann analysis and synthesis windows (their product
 *   is a periodic Hann window, which overlap-adds to exactly 1 at 50 % overlap → perfect reconstruction when all
 *   gains are 1).
 * - Noise profile: per-bin minimum statistics on the recursively smoothed periodogram (sliding ~1.5 s window
 *   split into sub-windows), bias-compensated. Tracks slowly varying noise continuously, no "learn noise" step.
 * - Gain: Wiener gain from a decision-directed a-priori SNR; over-subtraction and the gain floor scale with the
 *   strength; gains are smoothed in time (fast rise, slower fall) to avoid musical noise.
 * - Multichannel: one gain per bin computed from the mean channel power and applied to every channel, so the
 *   stereo image never wanders.
 *
 * The STFT always runs (also at strength 0, where it is an exact delay), so the latency is constant:
 * [latencySamples] frames per channel. Toggling or changing the strength is glitch-free (strength and bypass
 * are ramped per hop).
 */
class SpectralNoiseReducer(val sampleRate: Int, val channels: Int) {
    val fftSize: Int = fftSizeFor(sampleRate)
    val hop: Int = fftSize / 2
    private val bins = fftSize / 2 + 1

    /** Added delay in frames (per channel). */
    val latencySamples: Int = fftSize

    private val fft = RealFft(fftSize)
    private val window = FloatArray(fftSize) { n -> sqrt(0.5 - 0.5 * cos(2.0 * PI * n / fftSize)).toFloat() }

    private val inFrame = Array(channels) { FloatArray(fftSize) }
    private val ola = Array(channels) { FloatArray(fftSize) }
    private val outQueue = Array(channels) { FloatArray(hop) }
    private val specRe = Array(channels) { FloatArray(bins) }
    private val specIm = Array(channels) { FloatArray(bins) }
    private val work = FloatArray(fftSize)
    private var fill = 0

    // Noise tracking (per bin).
    private val power = FloatArray(bins)
    private val smoothed = FloatArray(bins)
    private val currentMin = FloatArray(bins)
    private val storedMin = FloatArray(bins)
    private val subWindows = 6
    private val subWindowFrames = max(4, (0.25 * sampleRate / hop).toInt())
    private val subMins = FloatArray(subWindows * bins)
    private var subIndex = 0
    private var subCount = 0
    private var subFramesFilled = 0
    private var frames = 0L

    // Gain state.
    private val prevCleanPower = FloatArray(bins)
    private val smoothedGain = FloatArray(bins) { 1f }

    @Volatile private var targetStrength = 0f
    private var strength = 0f
    private var mix = 0f

    fun setStrength(value: Float) {
        targetStrength = value.coerceIn(0f, 1f)
    }

    fun reset() {
        for (c in 0 until channels) {
            inFrame[c].fill(0f); ola[c].fill(0f); outQueue[c].fill(0f)
        }
        fill = 0
        smoothed.fill(0f); currentMin.fill(0f); storedMin.fill(Float.MAX_VALUE); subMins.fill(Float.MAX_VALUE)
        subIndex = 0; subCount = 0; subFramesFilled = 0; frames = 0
        prevCleanPower.fill(0f); smoothedGain.fill(1f)
        strength = targetStrength
        mix = if (strength > 0f) 1f else 0f
    }

    init { reset() }

    /** Processes [frameCount] interleaved frames in place starting at [offset]. Output is delayed by [latencySamples]. */
    fun process(buf: FloatArray, offset: Int, frameCount: Int) {
        val ch = channels
        val base = hop // write position of the newest hop inside the frame (fftSize − hop)
        var idx = offset
        for (i in 0 until frameCount) {
            val f = fill
            for (c in 0 until ch) {
                inFrame[c][base + f] = buf[idx + c]
                buf[idx + c] = outQueue[c][f]
            }
            idx += ch
            fill = f + 1
            if (fill == hop) {
                processHop()
                fill = 0
            }
        }
    }

    private fun processHop() {
        val n = fftSize
        // Analysis.
        for (c in 0 until channels) {
            val frame = inFrame[c]
            for (i in 0 until n) work[i] = frame[i] * window[i]
            fft.forward(work, specRe[c], specIm[c])
            // Slide the input frame by one hop.
            System.arraycopy(frame, hop, frame, 0, n - hop)
        }
        for (k in 0 until bins) {
            var p = 0f
            for (c in 0 until channels) {
                val re = specRe[c][k]; val im = specIm[c][k]
                p += re * re + im * im
            }
            power[k] = p / channels
        }

        // Parameter ramps (per hop ≈ 5 ms).
        val target = targetStrength
        strength += (target - strength).coerceIn(-STRENGTH_STEP, STRENGTH_STEP)
        val mixTarget = if (target > 0f) 1f else 0f
        mix += (mixTarget - mix).coerceIn(-MIX_STEP, MIX_STEP)

        updateNoiseEstimate()
        computeGains()

        // Apply gains + synthesis.
        val m = mix
        for (c in 0 until channels) {
            val re = specRe[c]; val im = specIm[c]
            for (k in 0 until bins) {
                val g = 1f - m + m * smoothedGain[k]
                re[k] *= g; im[k] *= g
            }
            fft.inverse(re, im, work)
            val acc = ola[c]
            for (i in 0 until n) acc[i] += work[i] * window[i]
            System.arraycopy(acc, 0, outQueue[c], 0, hop)
            System.arraycopy(acc, hop, acc, 0, n - hop)
            java.util.Arrays.fill(acc, n - hop, n, 0f)
        }
    }

    private fun updateNoiseEstimate() {
        val first = frames == 0L
        frames++
        for (k in 0 until bins) {
            val p = power[k] + EPS
            val s = if (first) p else SMOOTH * smoothed[k] + (1f - SMOOTH) * p
            smoothed[k] = s
            if (first || s < currentMin[k]) currentMin[k] = s
        }
        subFramesFilled++
        if (subFramesFilled >= subWindowFrames) {
            // Close the current sub-window.
            val o = subIndex * bins
            System.arraycopy(currentMin, 0, subMins, o, bins)
            subIndex = (subIndex + 1) % subWindows
            if (subCount < subWindows) subCount++
            for (k in 0 until bins) {
                var mn = Float.MAX_VALUE
                var u = 0
                while (u < subWindows) {
                    val v = subMins[u * bins + k]
                    if (v < mn) mn = v
                    u++
                }
                storedMin[k] = mn
                currentMin[k] = smoothed[k]
            }
            subFramesFilled = 0
        }
    }

    private fun computeGains() {
        val s = strength
        val overSub = 1f + 1.5f * s
        val floor = 10f.pow(-(6f + 18f * s) / 20f)
        for (k in 0 until bins) {
            val noiseMin = min(storedMin[k], currentMin[k])
            val noise = (noiseMin * BIAS + EPS) * overSub
            val p = power[k]
            val gamma = p / noise
            val prior = DD_ALPHA * (prevCleanPower[k] / noise) + (1f - DD_ALPHA) * max(gamma - 1f, 0f)
            val xi = max(prior, XI_MIN)
            val wiener = xi / (1f + xi)
            prevCleanPower[k] = wiener * wiener * p
            val g = max(wiener, floor)
            val prev = smoothedGain[k]
            smoothedGain[k] = if (g > prev) prev + GAIN_RISE * (g - prev) else prev + GAIN_FALL * (g - prev)
        }
    }

    companion object {
        private const val EPS = 1e-12f
        private const val SMOOTH = 0.8f
        /** Compensates the downward bias of taking a minimum of smoothed periodograms. */
        internal const val BIAS = 2.2f
        private const val DD_ALPHA = 0.96f
        private const val XI_MIN = 0.003f // −25 dB
        private const val GAIN_RISE = 0.6f
        private const val GAIN_FALL = 0.3f
        private const val STRENGTH_STEP = 0.02f
        private const val MIX_STEP = 0.05f

        /** Frame size ≈ 10.7 ms rounded up to a power of two: 512 at 44.1/48 kHz, 256 at 16–22 kHz, 1024 at 96 kHz. */
        fun fftSizeFor(sampleRate: Int): Int {
            val target = (sampleRate * 0.0106).toInt().coerceAtLeast(64)
            var n = 64
            while (n < target) n = n shl 1
            return n
        }
    }
}
