package com.ravango.engine.beauty.quality

import com.ravango.engine.beauty.BeautyQuality

/** Coarse device class, mirrored from `core:common` so the controller stays pure and unit-testable. */
enum class TierHint { LOW, MID, HIGH }

/** Thermal pressure, mirrored from `core:common`. */
enum class ThermalHint { NORMAL, WARM, HOT, CRITICAL }

/**
 * Adaptive quality state machine.
 *
 * - The **cap** (best allowed level) comes from device tier, thermal state and power-save mode.
 * - At runtime, an exponential moving average of `process cost / frame budget` drives the level:
 *   above [downRatio] continuously for [downHoldSec] → one step down; below [upRatio] continuously for
 *   [upHoldSec] → one step up (never above the cap).
 * - While recording the controller is conservative: it steps down sooner and only steps up with huge headroom.
 * - After every change a cooldown lets the new level's cost settle before deciding again.
 *
 * Not thread-safe: drive it from the GL thread (inputs from other threads go through volatile fields upstream).
 */
class QualityController(
    tier: TierHint,
    private val downRatio: Float = 0.45f,
    private val downHoldSec: Double = 1.0,
    private val upRatio: Float = 0.25f,
    private val upHoldSec: Double = 5.0,
    private val recordingDownRatio: Float = 0.38f,
    private val recordingDownHoldSec: Double = 0.6,
    private val recordingUpRatio: Float = 0.12f,
    private val recordingUpHoldSec: Double = 10.0,
    private val cooldownSec: Double = 1.5,
    private val emaAlpha: Float = 0.12f,
) {
    var tier: TierHint = tier
        set(value) { field = value; clampToCap() }
    var thermal: ThermalHint = ThermalHint.NORMAL
        set(value) { field = value; clampToCap() }
    var powerSave: Boolean = false
        set(value) { field = value; clampToCap() }
    var recording: Boolean = false

    var level: BeautyQuality = capFor(tier, ThermalHint.NORMAL, false)
        private set

    /** Smoothed cost ratio (process time / frame budget). */
    var averageRatio: Float = 0f
        private set

    /** Smoothed absolute cost in milliseconds. */
    var averageCostMs: Float = 0f
        private set

    private var samples = 0
    private var aboveSince = Double.NaN
    private var belowSince = Double.NaN
    private var lastChangeSec = Double.NEGATIVE_INFINITY

    val cap: BeautyQuality get() = capFor(tier, thermal, powerSave)

    /** Feeds one frame measurement; returns true when [level] changed. */
    fun onFrame(processNanos: Long, budgetNanos: Long, nowSec: Double): Boolean {
        if (budgetNanos <= 0L) return false
        val ratio = processNanos.toFloat() / budgetNanos.toFloat()
        val costMs = processNanos / 1_000_000f
        if (samples == 0) {
            averageRatio = ratio; averageCostMs = costMs
        } else {
            averageRatio += emaAlpha * (ratio - averageRatio)
            averageCostMs += emaAlpha * (costMs - averageCostMs)
        }
        samples++
        if (samples < MIN_SAMPLES || nowSec - lastChangeSec < cooldownSec) {
            aboveSince = Double.NaN; belowSince = Double.NaN
            return false
        }
        val down = if (recording) recordingDownRatio else downRatio
        val downHold = if (recording) recordingDownHoldSec else downHoldSec
        val up = if (recording) recordingUpRatio else upRatio
        val upHold = if (recording) recordingUpHoldSec else upHoldSec

        if (averageRatio > down) {
            belowSince = Double.NaN
            if (aboveSince.isNaN()) aboveSince = nowSec
            if (nowSec - aboveSince >= downHold && level != BeautyQuality.MINIMAL) {
                return change(step(level, +1), nowSec)
            }
        } else if (averageRatio < up) {
            aboveSince = Double.NaN
            if (belowSince.isNaN()) belowSince = nowSec
            if (nowSec - belowSince >= upHold && level.ordinal > cap.ordinal) {
                return change(step(level, -1), nowSec)
            }
        } else {
            aboveSince = Double.NaN; belowSince = Double.NaN
        }
        return false
    }

    /** Forces a level (e.g. when recording starts and the current cost is already borderline). */
    fun stepDown(nowSec: Double): Boolean =
        if (level == BeautyQuality.MINIMAL) false else change(step(level, +1), nowSec)

    private fun change(to: BeautyQuality, nowSec: Double): Boolean {
        if (to == level) return false
        level = to
        lastChangeSec = nowSec
        aboveSince = Double.NaN
        belowSince = Double.NaN
        // The new level has a different cost profile: restart averaging.
        samples = 0
        return true
    }

    private fun clampToCap() {
        if (level.ordinal < cap.ordinal) {
            level = cap
            samples = 0
            aboveSince = Double.NaN; belowSince = Double.NaN
        }
    }

    companion object {
        private const val MIN_SAMPLES = 8

        fun capFor(tier: TierHint, thermal: ThermalHint, powerSave: Boolean): BeautyQuality {
            var cap = when (tier) {
                TierHint.HIGH -> BeautyQuality.FULL
                TierHint.MID -> BeautyQuality.BALANCED
                TierHint.LOW -> BeautyQuality.LIGHT
            }
            val thermalCap = when (thermal) {
                ThermalHint.NORMAL -> BeautyQuality.FULL
                ThermalHint.WARM -> BeautyQuality.BALANCED
                ThermalHint.HOT -> BeautyQuality.LIGHT
                ThermalHint.CRITICAL -> BeautyQuality.MINIMAL
            }
            if (thermalCap.ordinal > cap.ordinal) cap = thermalCap
            if (powerSave && cap.ordinal < BeautyQuality.BALANCED.ordinal) cap = BeautyQuality.BALANCED
            return cap
        }

        /** +1 = cheaper (lower quality), -1 = better. */
        fun step(level: BeautyQuality, direction: Int): BeautyQuality {
            val entries = BeautyQuality.entries
            return entries[(level.ordinal + direction).coerceIn(0, entries.lastIndex)]
        }
    }
}

/** What each quality level costs: resolution/taps of smoothing, detection cadence, mask sizes and enabled passes. */
data class QualityProfile(
    /** Smoothing resolution as a fraction of the frame width. */
    val smoothScale: Float,
    /** Taps per side of the separable bilateral filter (compile-time constant in the shader). */
    val bilateralRadius: Int,
    /** Run detection at most every N frames. */
    val detectEveryFrames: Int,
    /** Width of the detection image in pixels. */
    val detectWidth: Int,
    /** Width of landmark mask textures. */
    val maskWidth: Int,
    val retouchAndBlemish: Boolean,
    val smoothing: Boolean,
    val faceEffects: Boolean,
    val sharpen: Boolean,
) {
    companion object {
        fun of(level: BeautyQuality): QualityProfile = when (level) {
            BeautyQuality.FULL -> QualityProfile(0.5f, 4, 1, 320, 360, retouchAndBlemish = true, smoothing = true, faceEffects = true, sharpen = true)
            BeautyQuality.BALANCED -> QualityProfile(0.375f, 3, 2, 288, 288, retouchAndBlemish = true, smoothing = true, faceEffects = true, sharpen = true)
            BeautyQuality.LIGHT -> QualityProfile(0.25f, 2, 3, 256, 224, retouchAndBlemish = false, smoothing = true, faceEffects = true, sharpen = true)
            BeautyQuality.MINIMAL -> QualityProfile(0.25f, 2, 4, 256, 192, retouchAndBlemish = false, smoothing = false, faceEffects = false, sharpen = false)
        }
    }
}
