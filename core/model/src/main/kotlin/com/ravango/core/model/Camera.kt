package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LensFacing { FRONT, BACK, EXTERNAL }

/** What gets recorded. */
@Serializable
enum class CaptureMode { VIDEO_WITH_AUDIO, VIDEO_ONLY, AUDIO_ONLY }

@Serializable
enum class VideoCodec(val mimeType: String) { H264("video/avc"), HEVC("video/hevc") }

@Serializable
enum class FlashMode { OFF, TORCH, SCREEN }

@Serializable
enum class StabilizationMode { OFF, STANDARD, PREVIEW_OPTIMIZED }

@Serializable
enum class GridType { NONE, THIRDS, GOLDEN, SQUARE, CENTER }

@Serializable
enum class SafeAreaType { NONE, TITLE_SAFE, ACTION_SAFE, SOCIAL_VERTICAL }

@Serializable
enum class WhiteBalanceMode { AUTO, INCANDESCENT, FLUORESCENT, WARM_FLUORESCENT, DAYLIGHT, CLOUDY, TWILIGHT, SHADE, MANUAL_KELVIN }

@Serializable
enum class BitrateProfile(val factor: Float) { STANDARD(1f), HIGH(1.6f), MAX(2.4f) }

/** Encoded video size (landscape convention: width >= height). */
@Serializable
data class VideoSize(val width: Int, val height: Int) : Comparable<VideoSize> {
    val pixels: Int get() = width * height
    val shortSide: Int get() = minOf(width, height)
    val label: String
        get() = when {
            shortSide >= 4320 -> "8K"
            shortSide >= 2160 -> "4K"
            shortSide >= 1440 -> "2K"
            shortSide >= 1080 -> "1080p"
            shortSide >= 720 -> "720p"
            else -> "${shortSide}p"
        }

    override fun compareTo(other: VideoSize): Int = pixels.compareTo(other.pixels)
}

/**
 * User-chosen camera configuration. Values are *requests*: the camera engine validates each against the
 * current device capabilities and falls back to the closest supported option.
 */
@Serializable
data class CameraSettings(
    val lensFacing: LensFacing = LensFacing.FRONT,
    /** Specific camera id (for multi-lens devices); null = default for [lensFacing]. */
    val cameraId: String? = null,
    val resolution: VideoSize = VideoSize(1920, 1080),
    val frameRate: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateProfile: BitrateProfile = BitrateProfile.HIGH,
    val aspectRatio: AspectRatioSpec = AspectRatioSpec.Portrait9x16,
    val captureMode: CaptureMode = CaptureMode.VIDEO_WITH_AUDIO,
    val stabilization: StabilizationMode = StabilizationMode.STANDARD,
    val hdr: Boolean = false,
    val grid: GridType = GridType.THIRDS,
    val showLevel: Boolean = false,
    val safeArea: SafeAreaType = SafeAreaType.NONE,
    val timerSeconds: Int = 0,
    val flash: FlashMode = FlashMode.OFF,
    val mirrorFrontRecording: Boolean = false,
    val showHistogram: Boolean = false,
)

/** Manual exposure/focus/white-balance state. `null` fields mean "automatic". */
@Serializable
data class ManualControls(
    val exposureCompensation: Int = 0,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val kelvin: Int = 5500,
    val zoomRatio: Float = 1f,
    val aeAfLocked: Boolean = false,
)
