package com.ravango.engine.camera.capability

import com.ravango.core.common.device.DeviceTier
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.FlashMode
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Output frame dimensions produced by the GL pipeline (and fed to the encoder). */
data class OutputSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * Pure selection logic for video sizes and frame rates. Kept free of Android types so it is unit tested.
 */
object VideoModeSelector {

    /** Frame rates we offer, when the device truly supports them in a normal capture session. */
    val CANDIDATE_FPS = listOf(24, 25, 30, 48, 50, 60, 90, 120)

    /** Short sides we offer as recording resolutions (16:9 camera streams). */
    val STANDARD_SHORT_SIDES = listOf(720, 1080, 1440, 2160)

    val TIMER_OPTIONS = listOf(0, 3, 5, 10)

    private const val SIXTEEN_NINE = 16f / 9f

    /**
     * Frame rates valid for a stream whose minimum frame duration is [minFrameDurationNs].
     * A rate is offered when an AE target range has it as its upper bound (above 60 fps only a fixed range counts,
     * since higher rates in variable ranges are usually reserved for constrained high-speed sessions).
     */
    fun frameRatesFor(fpsRanges: List<Pair<Int, Int>>, minFrameDurationNs: Long): List<Int> =
        CANDIDATE_FPS.filter { fps ->
            val rangeOk = if (fps > 60) {
                fpsRanges.any { (lo, hi) -> lo == fps && hi == fps }
            } else {
                fpsRanges.any { (lo, hi) -> hi == fps && lo <= fps }
            }
            // 1% tolerance: HALs report e.g. 33_366_666ns for "30 fps".
            val durationOk = minFrameDurationNs <= 0 || minFrameDurationNs <= (1_000_000_000.0 / fps * 1.01)
            rangeOk && durationOk
        }

    /** The AE target range to request for [fps]: prefer a fixed range, else the one with the highest lower bound. */
    fun aeRangeFor(fps: Int, fpsRanges: List<Pair<Int, Int>>): Pair<Int, Int>? =
        fpsRanges.firstOrNull { it.first == fps && it.second == fps }
            ?: fpsRanges.filter { it.second == fps }.maxByOrNull { it.first }
            ?: fpsRanges.filter { it.first <= fps && it.second >= fps }.maxByOrNull { it.first }

    fun isStandardSize(size: VideoSize): Boolean {
        val w = max(size.width, size.height)
        val h = min(size.width, size.height)
        return h in STANDARD_SHORT_SIDES && abs(w.toFloat() / h - SIXTEEN_NINE) < 0.01f
    }

    /** Largest short side / frame rate / bitrate a software encoder (OMX.google.*, c2.android.*) is asked to sustain. */
    const val SOFTWARE_MAX_SHORT_SIDE = 720
    const val SOFTWARE_MAX_FPS = 30
    const val SOFTWARE_MAX_BITRATE = 5_000_000

    fun withinSoftwareEncoderCap(size: VideoSize, fps: Int): Boolean = size.shortSide <= SOFTWARE_MAX_SHORT_SIDE && fps <= SOFTWARE_MAX_FPS

    /** Bitrate for an encoder: hardware encoders keep [bitrate]; software ones are capped to what they can sustain. */
    fun encoderBitrate(bitrate: Int, softwareEncoder: Boolean): Int = if (softwareEncoder) min(bitrate, SOFTWARE_MAX_BITRATE) else bitrate

    /**
     * Builds the list of recordable modes. [encoderSupports] must answer for the exact output dimensions
     * (both orientations are checked because portrait aspect ratios produce portrait output).
     *
     * [hardwareEncoderSupports] answers the same for hardware encoders only: a codec that only a software encoder
     * can handle is offered up to 720p30 ([withinSoftwareEncoderCap]) — software encoders cannot keep up with
     * 1080p+ in realtime, which starves the pipeline and loses takes. When that leaves nothing, the lightest mode is
     * still offered so the camera stays usable.
     */
    fun buildModes(
        streamSizes: List<VideoSize>,
        fpsRanges: List<Pair<Int, Int>>,
        minFrameDurationNs: (VideoSize) -> Long,
        codecs: Set<VideoCodec>,
        encoderSupports: (codec: VideoCodec, width: Int, height: Int, fps: Int) -> Boolean,
        hardwareEncoderSupports: (codec: VideoCodec, width: Int, height: Int, fps: Int) -> Boolean = encoderSupports,
    ): List<VideoMode> {
        val standard = streamSizes.filter(::isStandardSize).distinct().sortedDescending()
        val first = collectModes(standard, streamSizes, fpsRanges, minFrameDurationNs, codecs, encoderSupports, hardwareEncoderSupports)
        val softwareLimited = first.uncapped.sumOf { it.codecs.size } != first.modes.sumOf { it.codecs.size }
        val software720 = VideoSize(1280, 720)
        // Software-limited and the camera has no 720p stream: record 720p from a larger stream (the GL pipeline scales).
        val result = if (softwareLimited && standard.none { it.shortSide == SOFTWARE_MAX_SHORT_SIDE } && standard.any { it.shortSide > SOFTWARE_MAX_SHORT_SIDE }) {
            collectModes(standard + software720, streamSizes, fpsRanges, minFrameDurationNs, codecs, encoderSupports, hardwareEncoderSupports)
        } else {
            first
        }
        if (result.modes.isEmpty() && result.uncapped.isNotEmpty()) {
            return listOf(result.uncapped.minWith(compareBy<VideoMode> { it.size.pixels.toLong() * it.fps }))
        }
        return result.modes
    }

    private class ModeLists(val modes: List<VideoMode>, val uncapped: List<VideoMode>)

    @Suppress("LongParameterList")
    private fun collectModes(
        sizes: List<VideoSize>,
        streamSizes: List<VideoSize>,
        fpsRanges: List<Pair<Int, Int>>,
        minFrameDurationNs: (VideoSize) -> Long,
        codecs: Set<VideoCodec>,
        encoderSupports: (codec: VideoCodec, width: Int, height: Int, fps: Int) -> Boolean,
        hardwareEncoderSupports: (codec: VideoCodec, width: Int, height: Int, fps: Int) -> Boolean,
    ): ModeLists {
        val modes = ArrayList<VideoMode>()
        val uncapped = ArrayList<VideoMode>()
        for (size in sizes) {
            val longSide = max(size.width, size.height)
            val shortSide = min(size.width, size.height)
            val landscape = VideoSize(longSide, shortSide)
            val stream = if (streamSizes.any { it == landscape }) landscape else streamSizeFor(landscape, streamSizes)
            for (fps in frameRatesFor(fpsRanges, minFrameDurationNs(stream))) {
                val encodable = codecs.filterTo(LinkedHashSet()) { codec ->
                    encoderSupports(codec, longSide, shortSide, fps) && encoderSupports(codec, shortSide, longSide, fps)
                }
                if (encodable.isEmpty()) continue
                uncapped += VideoMode(landscape, fps, encodable)
                val sustainable = encodable.filterTo(LinkedHashSet()) { codec ->
                    withinSoftwareEncoderCap(landscape, fps) ||
                        (hardwareEncoderSupports(codec, longSide, shortSide, fps) && hardwareEncoderSupports(codec, shortSide, longSide, fps))
                }
                if (sustainable.isNotEmpty()) modes += VideoMode(landscape, fps, sustainable)
            }
        }
        return ModeLists(modes, uncapped)
    }

    /**
     * Output dimensions for [resolution] cropped to [aspect]: the largest rectangle of that aspect that fits the
     * resolution's box in the matching orientation, aligned to 16 px (except where it equals the box edge).
     */
    fun outputSize(resolution: VideoSize, aspect: AspectRatioSpec): OutputSize {
        val longSide = max(resolution.width, resolution.height)
        val shortSide = min(resolution.width, resolution.height)
        val ratio = aspect.width.toDouble() / aspect.height.toDouble()
        val (boxW, boxH) = if (ratio >= 1.0) longSide to shortSide else shortSide to longSide
        val w: Int
        val h: Int
        if (boxW.toDouble() / boxH > ratio) {
            h = boxH
            w = align(boxH * ratio, boxW, boxH)
        } else {
            w = boxW
            h = align(boxW / ratio, boxH, boxW)
        }
        return OutputSize(w, h)
    }

    private fun align(value: Double, boxEdge: Int, otherEdge: Int): Int {
        val v = value.roundToInt()
        if (v >= boxEdge) return boxEdge
        if (v == otherEdge) return v // e.g. 1:1 keeps the standard 1080 edge
        return max(16, (v / 16) * 16)
    }

    /** Maximum pixel rate (pixels × fps) allowed on a device tier; null means unlimited. */
    fun maxPixelRate(tier: DeviceTier): Long? = when (tier) {
        DeviceTier.LOW -> 1920L * 1080 * 60
        DeviceTier.MID -> 3840L * 2160 * 30
        DeviceTier.HIGH -> null
    }

    fun withinTier(mode: VideoMode, tier: DeviceTier): Boolean {
        val limit = maxPixelRate(tier) ?: return true
        return mode.size.pixels.toLong() * mode.fps <= limit
    }

    /** Default recording mode for a device: 1080p30 when available, else the closest lighter mode. */
    fun defaultMode(modes: List<VideoMode>, tier: DeviceTier): VideoMode? {
        val allowed = modes.filter { withinTier(it, tier) }.ifEmpty { modes }
        return allowed.firstOrNull { it.size.shortSide == 1080 && it.fps == 30 }
            ?: allowed.firstOrNull { it.size.shortSide == 720 && it.fps == 30 }
            ?: nearestMode(allowed, VideoSize(1920, 1080), 30)
    }

    /**
     * The supported mode closest to the request. Resolution distance is measured in octaves of pixel count and
     * weighs more than frame-rate distance; ties prefer the lighter mode (safer for thermals and storage).
     */
    fun nearestMode(modes: List<VideoMode>, size: VideoSize, fps: Int): VideoMode? {
        if (modes.isEmpty()) return null
        modes.firstOrNull { it.size.shortSide == size.shortSide && it.size.pixels == size.pixels && it.fps == fps }?.let { return it }
        val reqPixels = max(1, size.pixels).toDouble()
        return modes.minWithOrNull(
            compareBy<VideoMode> { mode ->
                val sizeScore = abs(ln(mode.size.pixels / reqPixels) / ln(2.0))
                val fpsScore = abs(mode.fps - fps) / 30.0
                sizeScore * 2 + fpsScore
            }.thenBy { it.size.pixels.toLong() * it.fps },
        )
    }

    /**
     * Validates user settings against [caps], replacing anything unsupported with the closest supported option.
     * [tier] caps the pixel rate on weaker devices.
     */
    fun validate(settings: CameraSettings, caps: CameraCapabilities, tier: DeviceTier): CameraSettings {
        val allowedModes = caps.videoModes.filter { withinTier(it, tier) }.ifEmpty { caps.videoModes }
        val mode = nearestMode(allowedModes, settings.resolution, settings.frameRate)
        val codec = when {
            mode == null -> settings.codec
            settings.codec in mode.codecs -> settings.codec
            VideoCodec.H264 in mode.codecs -> VideoCodec.H264
            else -> mode.codecs.first()
        }
        return settings.copy(
            cameraId = caps.cameraId,
            lensFacing = caps.facing,
            resolution = mode?.size ?: settings.resolution,
            frameRate = mode?.fps ?: settings.frameRate,
            codec = codec,
            stabilization = validateStabilization(settings.stabilization, caps.stabilizationModes),
            hdr = settings.hdr && caps.hdrOptions.isNotEmpty(),
            flash = validateFlash(settings.flash, caps),
            timerSeconds = TIMER_OPTIONS.minBy { abs(it - settings.timerSeconds) },
        )
    }

    /**
     * [settings] limited to at most [maxShortSide]p at [maxFps] (the closest supported mode below the limit), for a
     * camera that keeps failing at the requested size. Unchanged when already within the limit or nothing fits.
     */
    fun capped(settings: CameraSettings, caps: CameraCapabilities, maxShortSide: Int, maxFps: Int): CameraSettings {
        if (settings.resolution.shortSide <= maxShortSide && settings.frameRate <= maxFps) return settings
        val candidates = caps.videoModes.filter { it.size.shortSide <= maxShortSide && it.fps <= maxFps }
        val mode = nearestMode(candidates, settings.resolution, settings.frameRate) ?: return settings
        val codec = when {
            settings.codec in mode.codecs -> settings.codec
            VideoCodec.H264 in mode.codecs -> VideoCodec.H264
            else -> mode.codecs.first()
        }
        return settings.copy(resolution = mode.size, frameRate = mode.fps, codec = codec)
    }

    fun validateStabilization(requested: StabilizationMode, available: List<StabilizationMode>): StabilizationMode = when {
        requested in available -> requested
        requested == StabilizationMode.PREVIEW_OPTIMIZED && StabilizationMode.STANDARD in available -> StabilizationMode.STANDARD
        requested != StabilizationMode.OFF && StabilizationMode.STANDARD in available -> StabilizationMode.STANDARD
        else -> StabilizationMode.OFF
    }

    fun validateFlash(requested: FlashMode, caps: CameraCapabilities): FlashMode = when (requested) {
        FlashMode.OFF -> FlashMode.OFF
        FlashMode.TORCH -> if (caps.flashAvailable) FlashMode.TORCH else if (caps.screenFlash) FlashMode.SCREEN else FlashMode.OFF
        FlashMode.SCREEN -> if (caps.screenFlash) FlashMode.SCREEN else if (caps.flashAvailable) FlashMode.TORCH else FlashMode.OFF
    }

    /** Flash modes the UI should cycle through for [caps]. */
    fun flashModes(caps: CameraCapabilities): List<FlashMode> = buildList {
        add(FlashMode.OFF)
        if (caps.flashAvailable) add(FlashMode.TORCH)
        if (caps.screenFlash) add(FlashMode.SCREEN)
    }

    /**
     * Picks the camera stream size for a recording size: the recording size itself when the camera offers it,
     * otherwise the smallest 16:9 stream that is at least as large, otherwise the largest available.
     */
    fun streamSizeFor(resolution: VideoSize, streamSizes: List<VideoSize>): VideoSize {
        val target = VideoSize(max(resolution.width, resolution.height), min(resolution.width, resolution.height))
        if (streamSizes.any { it.width == target.width && it.height == target.height }) return target
        val sixteenNine = streamSizes.filter { abs(it.width.toFloat() / it.height - SIXTEEN_NINE) < 0.01f }
        return sixteenNine.filter { it.width >= target.width && it.height >= target.height }.minByOrNull { it.pixels }
            ?: sixteenNine.maxByOrNull { it.pixels }
            ?: streamSizes.maxByOrNull { it.pixels }
            ?: target
    }
}
