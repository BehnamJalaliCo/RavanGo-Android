package com.ravango.engine.audio.internal

import com.ravango.core.model.AudioLevel
import kotlin.math.max

/**
 * Streaming channel-count + sample-rate converter for interleaved 16-bit PCM (linear interpolation).
 * Used when the capture format changes mid-recording (e.g. a stereo USB mic is unplugged and capture falls back to
 * the mono built-in mic) so an encoder configured for the first format can keep going.
 * Allocation-free once the output buffer has grown to the largest chunk size.
 */
internal class PcmConverter(val dstRate: Int, val dstChannels: Int) {
    private var srcRate = dstRate
    private var srcChannels = dstChannels
    private var pos = 0.0
    private val last = FloatArray(dstChannels)
    private var hasLast = false

    var output: ShortArray = ShortArray(0)
        private set

    val isPassthrough: Boolean get() = srcRate == dstRate && srcChannels == dstChannels

    fun setSource(rate: Int, channels: Int) {
        if (rate == srcRate && channels == srcChannels) return
        srcRate = rate
        srcChannels = channels
        pos = if (hasLast) -1.0 else 0.0
    }

    private fun sampleAt(input: ShortArray, frame: Int, dst: Int): Float {
        if (frame < 0) return last[dst]
        val base = frame * srcChannels
        return when {
            srcChannels == dstChannels -> input[base + dst].toFloat()
            srcChannels == 1 -> input[base].toFloat()
            dstChannels == 1 -> {
                var s = 0f
                for (c in 0 until srcChannels) s += input[base + c]
                s / srcChannels
            }
            else -> input[base + minOf(dst, srcChannels - 1)].toFloat()
        }
    }

    /** Converts [length] samples of [input]; returns the number of samples written to [output]. */
    fun convert(input: ShortArray, length: Int): Int {
        val frames = length / srcChannels
        if (frames == 0) return 0
        val step = srcRate.toDouble() / dstRate
        val maxOut = ((frames + 1) / step).toInt() + 2
        if (output.size < maxOut * dstChannels) output = ShortArray(maxOut * dstChannels)
        val out = output
        var o = 0
        var p = pos
        while (p < frames - 1) {
            val i0 = if (p < 0) -1 else p.toInt()
            val frac = (p - i0).toFloat()
            for (d in 0 until dstChannels) {
                val a = sampleAt(input, i0, d)
                val b = sampleAt(input, i0 + 1, d)
                val v = a + (b - a) * frac
                out[o++] = (if (v >= 0f) v + 0.5f else v - 0.5f).toInt().coerceIn(-32768, 32767).toShort()
            }
            p += step
        }
        pos = p - frames
        for (d in 0 until dstChannels) last[d] = sampleAt(input, frames - 1, d)
        hasLast = true
        return o
    }
}

/**
 * Maps AudioRecord frame positions to System.nanoTime() presentation times. Uses the hardware timestamp
 * (AudioRecord.getTimestamp, TIMEBASE_MONOTONIC — the same clock as System.nanoTime()) when available, otherwise
 * the frame count anchored at the first read. Always strictly increasing.
 */
internal class PresentationClock(sampleRate: Int) {
    private var sampleRate = sampleRate
    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var hasHwAnchor = false
    private var fallbackAnchorNs = Long.MIN_VALUE
    private var lastPts = Long.MIN_VALUE

    /** Starts a new AudioRecord session (frame positions restart at 0). Monotonicity is kept across sessions. */
    fun reset(sampleRate: Int = this.sampleRate) {
        this.sampleRate = sampleRate
        hasHwAnchor = false
        fallbackAnchorNs = Long.MIN_VALUE
    }

    fun onHardwareTimestamp(framePosition: Long, nanoTime: Long) {
        anchorFrame = framePosition
        anchorNs = nanoTime
        hasHwAnchor = true
    }

    fun framesToNs(frames: Long): Long = frames * 1_000_000_000L / sampleRate

    /**
     * @param framesBefore frames read before this chunk. @param chunkFrames frames in this chunk.
     * @param nowNs System.nanoTime() right after the read returned.
     * @return presentation time of the chunk's first frame.
     */
    fun ptsFor(framesBefore: Long, chunkFrames: Int, nowNs: Long): Long {
        var pts = if (hasHwAnchor) {
            anchorNs + framesToNs(framesBefore - anchorFrame)
        } else {
            if (fallbackAnchorNs == Long.MIN_VALUE) fallbackAnchorNs = nowNs - framesToNs(framesBefore + chunkFrames)
            fallbackAnchorNs + framesToNs(framesBefore)
        }
        if (lastPts != Long.MIN_VALUE && pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        return pts
    }
}

/** Meter ballistics: peak hold then decay, lightly smoothed RMS, sticky clip indicator. */
internal class LevelBallistics(
    private val holdMs: Long = 600,
    private val decayDbPerSecond: Float = 24f,
    private val clipHoldMs: Long = 1_000,
) {
    private var heldPeak = -90f
    private var heldAtMs = 0L
    private var lastMs = -1L
    private var rms = -90f
    private var clipUntilMs = -1L

    fun reset() {
        heldPeak = -90f; lastMs = -1; rms = -90f; clipUntilMs = -1
    }

    fun update(peakDb: Float, rmsDb: Float, clipped: Boolean, nowMs: Long): AudioLevel {
        val dt = if (lastMs < 0) 0L else nowMs - lastMs
        lastMs = nowMs
        if (peakDb >= heldPeak) {
            heldPeak = peakDb
            heldAtMs = nowMs
        } else if (nowMs - heldAtMs > holdMs) {
            heldPeak = max(peakDb, heldPeak - decayDbPerSecond * dt / 1000f)
        }
        rms = if (rmsDb > rms) rmsDb else rms + (rmsDb - rms) * 0.35f
        if (clipped) clipUntilMs = nowMs + clipHoldMs
        return AudioLevel(
            peakDbfs = heldPeak.coerceIn(-90f, 0f),
            rmsDbfs = rms.coerceIn(-90f, 0f),
            clipping = nowMs <= clipUntilMs,
        )
    }
}
