package com.ravango.engine.camera.encoder

import com.ravango.core.model.BitrateProfile
import com.ravango.core.model.VideoCodec
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Target bitrates. Video: bits-per-pixel model with sub-linear frame-rate scaling (motion between frames
 * shrinks as fps rises), scaled by [BitrateProfile] and codec efficiency. Pure, unit tested.
 */
object BitrateCalculator {
    /** Bits per pixel per frame at 30 fps for H.264 "standard" quality. */
    const val H264_BPP = 0.14
    const val HEVC_EFFICIENCY = 0.65
    const val MIN_VIDEO_BPS = 1_000_000
    const val MAX_VIDEO_BPS = 200_000_000

    fun videoBitrate(
        width: Int,
        height: Int,
        fps: Int,
        codec: VideoCodec,
        profile: BitrateProfile,
        encoderRange: IntRange? = null,
    ): Int {
        val effectiveFps = 30.0 * (fps.coerceAtLeast(1) / 30.0).pow(0.8)
        val codecFactor = if (codec == VideoCodec.HEVC) HEVC_EFFICIENCY else 1.0
        val raw = width.toDouble() * height * effectiveFps * H264_BPP * profile.factor * codecFactor
        var bps = ((raw / 100_000.0).roundToLong() * 100_000L).coerceIn(MIN_VIDEO_BPS.toLong(), MAX_VIDEO_BPS.toLong()).toInt()
        if (encoderRange != null && !encoderRange.isEmpty()) bps = bps.coerceIn(encoderRange.first, encoderRange.last)
        return bps
    }

    fun audioBitrate(kbps: Int): Int = (kbps * 1000).coerceIn(64_000, 320_000)

    /** Total bits per second of a recording including ~1% container overhead. */
    fun totalBitsPerSecond(videoBps: Int, audioBps: Int): Long = ((videoBps.toLong() + audioBps) * 101) / 100
}
