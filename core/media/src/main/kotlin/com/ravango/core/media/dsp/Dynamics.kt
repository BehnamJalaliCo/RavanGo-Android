package com.ravango.core.media.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** One-pole coefficient reaching ~63 % of a step after [timeMs]. */
internal fun onePole(timeMs: Float, sampleRate: Int): Float =
    (1.0 - exp(-1.0 / (timeMs.toDouble() * 0.001 * sampleRate))).toFloat()

internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

/**
 * Stereo-linked feed-forward compressor with an RMS detector and a soft knee.
 * Defaults are the voice-enhancement settings: −18 dBFS threshold, 3:1, 10 ms attack, 120 ms release, +4 dB makeup.
 */
class Compressor(
    sampleRate: Int,
    private val channels: Int,
    private val thresholdDb: Float = -18f,
    ratio: Float = 3f,
    attackMs: Float = 10f,
    releaseMs: Float = 120f,
    private val kneeDb: Float = 6f,
    makeupDb: Float = 4f,
    rmsWindowMs: Float = 5f,
) {
    private val slope = 1f / ratio - 1f
    private val attack = onePole(attackMs, sampleRate)
    private val release = onePole(releaseMs, sampleRate)
    private val rmsCoef = onePole(rmsWindowMs, sampleRate)
    private val makeup = makeupDb
    private var meanSquare = 0f
    private var reductionDb = 0f

    /** Current gain reduction in dB (≤ 0), for diagnostics/tests. */
    val gainReductionDb: Float get() = reductionDb

    fun reset() {
        meanSquare = 0f
        reductionDb = 0f
    }

    /** Processes one interleaved frame starting at [index] of [buf]; writes the compressed samples back. */
    fun processFrame(buf: FloatArray, index: Int) {
        var sq = 0f
        for (c in 0 until channels) { val x = buf[index + c]; sq += x * x }
        meanSquare += rmsCoef * (sq / channels - meanSquare)
        val levelDb = 10f * log10(meanSquare + 1e-12f)
        val over = levelDb - thresholdDb
        val targetDb = when {
            2f * over < -kneeDb -> 0f
            2f * abs(over) <= kneeDb -> { val t = over + kneeDb / 2f; slope * t * t / (2f * kneeDb) }
            else -> slope * over
        }
        val coef = if (targetDb < reductionDb) attack else release
        reductionDb += coef * (targetDb - reductionDb)
        val g = dbToLinear(reductionDb + makeup)
        for (c in 0 until channels) buf[index + c] *= g
    }
}

/**
 * Look-ahead-free, stereo-linked peak limiter: instant attack (a sample never leaves above the ceiling), smooth
 * exponential release, followed by a brickwall clamp at the ceiling as a numerical safety net.
 */
class PeakLimiter(sampleRate: Int, private val channels: Int, ceilingDb: Float = -1f, releaseMs: Float = 80f) {
    val ceiling: Float = dbToLinear(ceilingDb)
    private val release = onePole(releaseMs, sampleRate)
    private var envelope = 1f

    /** Current gain (≤ 1). */
    val gain: Float get() = envelope

    fun reset() { envelope = 1f }

    fun processFrame(buf: FloatArray, index: Int) {
        var peak = 0f
        for (c in 0 until channels) { val a = abs(buf[index + c]); if (a > peak) peak = a }
        val target = if (peak > ceiling) ceiling / peak else 1f
        envelope = if (target < envelope) target else envelope + release * (target - envelope)
        val g = envelope
        val cl = ceiling
        for (c in 0 until channels) {
            val y = buf[index + c] * g
            buf[index + c] = if (y > cl) cl else if (y < -cl) -cl else y
        }
    }
}

/** Linear ramp used for crossfading processing stages in/out without clicks. */
internal class LinearRamp(private val step: Float, initial: Float) {
    var value: Float = initial
        private set
    var target: Float = initial

    val isSilent: Boolean get() = value == 0f && target == 0f
    val isSteady: Boolean get() = value == target

    fun snap(v: Float) { value = v; target = v }

    fun next(): Float {
        val v = value
        if (v != target) {
            value = if (v < target) minOf(target, v + step) else maxOf(target, v - step)
        }
        return value
    }
}
