package com.ravango.engine.editor.export

import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.VideoCodec
import com.ravango.engine.editor.composition.CanvasSizing
import kotlin.math.roundToLong

/** Pure export-setting rules: allowed options, plan clamping, output size and bitrate/file-size estimates. */
object ExportMath {
    val RESOLUTIONS = listOf(480, 720, 1080, 1440, 2160)
    val FRAME_RATES = listOf(24, 25, 30, 50, 60)
    const val MIN_CUSTOM_BITRATE = 1_000_000
    const val MAX_CUSTOM_BITRATE = 80_000_000

    /** Largest allowed option not above [maxShortSide] (never below the smallest option). */
    fun clampResolution(requested: Int, maxShortSide: Int): Int {
        val allowed = RESOLUTIONS.filter { it <= maxShortSide }.ifEmpty { listOf(RESOLUTIONS.first()) }
        return allowed.lastOrNull { it <= requested } ?: allowed.first()
    }

    fun clampFrameRate(requested: Int, maxFps: Int): Int {
        val allowed = FRAME_RATES.filter { it <= maxFps }.ifEmpty { listOf(FRAME_RATES.first()) }
        return allowed.lastOrNull { it <= requested } ?: allowed.first()
    }

    /** Applies plan limits to [settings]. */
    fun clamp(settings: ExportSettings, entitlements: Entitlements): ExportSettings = settings.copy(
        resolutionShortSide = clampResolution(settings.resolutionShortSide, entitlements.maxExportShortSide),
        frameRate = clampFrameRate(settings.frameRate, entitlements.maxExportFps),
        videoBitrateBps = settings.videoBitrateBps?.coerceIn(MIN_CUSTOM_BITRATE, MAX_CUSTOM_BITRATE),
    )

    fun outputSize(aspect: AspectRatioSpec, shortSide: Int): Pair<Int, Int> = CanvasSizing.size(aspect, shortSide)

    /**
     * Automatic bitrate: ~0.11 bits per pixel per frame for H.264 at 30 fps, scaled sub-linearly with frame rate
     * (motion between frames is smaller at high fps); HEVC needs ~35% less for the same quality.
     */
    fun autoBitrate(width: Int, height: Int, fps: Int, codec: VideoCodec): Int {
        val pixels = width.toLong() * height
        val fpsFactor = Math.pow(fps / 30.0, 0.75)
        val base = pixels * 30 * 0.11 * fpsFactor
        val codecFactor = if (codec == VideoCodec.HEVC) 0.65 else 1.0
        return (base * codecFactor).roundToLong().coerceIn(1_000_000L, 100_000_000L).toInt()
    }

    fun effectiveBitrate(settings: ExportSettings, aspect: AspectRatioSpec): Int {
        val (w, h) = outputSize(aspect, settings.resolutionShortSide)
        return settings.videoBitrateBps ?: autoBitrate(w, h, settings.frameRate, settings.codec)
    }

    /** Estimated output size (bytes) including ~2% container overhead. */
    fun estimateBytes(durationUs: Long, videoBps: Int, audioBps: Int, includeAudio: Boolean): Long {
        val seconds = durationUs / 1_000_000.0
        val bits = seconds * (videoBps + if (includeAudio) audioBps else 0)
        return (bits / 8 * 1.02).roundToLong()
    }
}
