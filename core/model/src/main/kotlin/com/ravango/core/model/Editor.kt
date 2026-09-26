package com.ravango.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Editor document model.
 *
 * The document is immutable and fully serializable: undo/redo keeps snapshots, drafts persist it as JSON,
 * cloud sync uploads it, and AI features return edits against it. All times are in microseconds (µs),
 * matching Media3. "Timeline time" is output time after speed changes.
 */

@Serializable
data class EditorDocument(
    val id: String = newId(),
    val schemaVersion: Int = CURRENT_SCHEMA,
    val canvas: CanvasSpec = CanvasSpec(),
    /** The primary storyline: clips play back-to-back. */
    val mainTrack: List<VideoClip> = emptyList(),
    /** Layers composited above the main track, lowest index drawn first. */
    val overlayTracks: List<OverlayTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitles: SubtitleTrack = SubtitleTrack(),
    val export: ExportSettings = ExportSettings(),
) {
    /** Output duration of the main storyline. */
    val durationUs: Long get() = mainTrack.sumOf { it.outputDurationUs }

    /** Start time of each main-track clip on the timeline. */
    fun clipStartUs(clipId: String): Long {
        var t = 0L
        for (c in mainTrack) {
            if (c.id == clipId) return t
            t += c.outputDurationUs
        }
        return -1
    }

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class CanvasSpec(
    val aspectRatio: AspectRatioSpec = AspectRatioSpec.Portrait9x16,
    val background: CanvasBackground = CanvasBackground.Solid(0xFF000000),
    /** How clips whose aspect differs from the canvas are placed. */
    val fit: ContentFit = ContentFit.FIT,
)

@Serializable
enum class ContentFit { FIT, FILL }

@Serializable
sealed interface CanvasBackground {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: ArgbColor) : CanvasBackground

    @Serializable
    @SerialName("gradient")
    data class Gradient(val start: ArgbColor, val end: ArgbColor, val angleDegrees: Float = 90f) : CanvasBackground

    /** The clip itself, scaled to fill and blurred. */
    @Serializable
    @SerialName("blur")
    data class Blur(val radius: Float = 0.6f) : CanvasBackground
}

/** A media file referenced by a clip. */
@Serializable
data class MediaSource(
    val uri: String,
    val kind: MediaKind,
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val hasAudio: Boolean = true,
    val frameRate: Float = 0f,
    val assetId: String? = null,
)

@Serializable
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
}

/** Placement of a clip or overlay inside the canvas. Coordinates are normalized to the canvas (0..1). */
@Serializable
data class Transform2D(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
)

/** Values are normalized to -1..1 (0 = neutral) except [sharpen], [blur], [vignette], [grain] which are 0..1. */
@Serializable
data class ColorAdjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpen: Float = 0f,
    val blur: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
) {
    val isNeutral: Boolean get() = this == Neutral

    companion object {
        val Neutral = ColorAdjustments()
    }
}

@Serializable
enum class FilterPreset { NONE, VIVID, WARM, COOL, CINEMA, TEAL_ORANGE, FILM, FADE, MONO, NOIR, PASTEL, SUNSET }

@Serializable
enum class TransitionType { NONE, FADE_BLACK, FADE_WHITE, CROSS_ZOOM, ZOOM_IN, ZOOM_OUT, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, BLUR, SPIN }

@Serializable
data class Transition(val type: TransitionType = TransitionType.NONE, val durationUs: Long = 500_000)

@Serializable
data class VideoClip(
    val id: String = newId(),
    val source: MediaSource,
    /** Source-time trim window. */
    val trimStartUs: Long = 0,
    val trimEndUs: Long = source.durationUs,
    val speed: Float = 1f,
    val reversed: Boolean = false,
    /** Cached reversed rendition produced by the editor engine. */
    val reversedUri: String? = null,
    /** For image sources (photos, freeze frames): how long the still is shown, in source time. */
    val stillDurationUs: Long = 3_000_000,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val audioFadeInUs: Long = 0,
    val audioFadeOutUs: Long = 0,
    val videoFadeInUs: Long = 0,
    val videoFadeOutUs: Long = 0,
    val crop: CropRect = CropRect(),
    /** Quarter-turn rotation (0, 90, 180, 270) plus fine rotation in [transform]. */
    val rotationQuarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val transform: Transform2D = Transform2D(),
    val filter: FilterPreset = FilterPreset.NONE,
    val filterIntensity: Float = 1f,
    val adjustments: ColorAdjustments = ColorAdjustments(),
    /** Transition into the next clip. */
    val transitionOut: Transition = Transition(),
    val noiseReduction: Float = 0f,
    val voiceEnhance: Boolean = false,
) {
    val sourceDurationUs: Long
        get() = if (source.kind == MediaKind.IMAGE) stillDurationUs else (trimEndUs - trimStartUs).coerceAtLeast(0)

    val outputDurationUs: Long
        get() = (sourceDurationUs / speed.toDouble()).toLong()
}

@Serializable
data class OverlayTrack(
    val id: String = newId(),
    val items: List<OverlayItem> = emptyList(),
    val hidden: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
enum class OverlayAnimation { NONE, FADE, POP, SLIDE_UP, SLIDE_DOWN, TYPEWRITER, BOUNCE, ZOOM }

@Serializable
sealed interface OverlayItem {
    val id: String
    val startUs: Long
    val endUs: Long
    val transform: Transform2D
    val animationIn: OverlayAnimation
    val animationOut: OverlayAnimation

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String = newId(),
        override val startUs: Long,
        override val endUs: Long,
        override val transform: Transform2D = Transform2D(),
        override val animationIn: OverlayAnimation = OverlayAnimation.FADE,
        override val animationOut: OverlayAnimation = OverlayAnimation.FADE,
        val text: String,
        val style: TextStyleSpec = TextStyleSpec(),
    ) : OverlayItem

    /** Emoji or bundled sticker, rendered as text or asset. */
    @Serializable
    @SerialName("sticker")
    data class Sticker(
        override val id: String = newId(),
        override val startUs: Long,
        override val endUs: Long,
        override val transform: Transform2D = Transform2D(),
        override val animationIn: OverlayAnimation = OverlayAnimation.POP,
        override val animationOut: OverlayAnimation = OverlayAnimation.FADE,
        val emoji: String? = null,
        val assetUri: String? = null,
    ) : OverlayItem

    /** Image overlay: logo, watermark, graphic. */
    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String = newId(),
        override val startUs: Long,
        override val endUs: Long,
        override val transform: Transform2D = Transform2D(),
        override val animationIn: OverlayAnimation = OverlayAnimation.NONE,
        override val animationOut: OverlayAnimation = OverlayAnimation.NONE,
        val uri: String,
        val isWatermark: Boolean = false,
    ) : OverlayItem

    /** Picture-in-picture video. */
    @Serializable
    @SerialName("video")
    data class Video(
        override val id: String = newId(),
        override val startUs: Long,
        override val endUs: Long,
        override val transform: Transform2D = Transform2D(centerX = 0.75f, centerY = 0.25f, scale = 0.35f),
        override val animationIn: OverlayAnimation = OverlayAnimation.FADE,
        override val animationOut: OverlayAnimation = OverlayAnimation.FADE,
        val source: MediaSource,
        val trimStartUs: Long = 0,
        val volume: Float = 0f,
        val cornerRadius: Float = 0.08f,
        val borderColor: ArgbColor? = 0xFFFFFFFF,
    ) : OverlayItem
}

@Serializable
data class TextStyleSpec(
    val font: PrompterFont = PrompterFont.VAZIRMATN,
    val sizeSp: Float = 32f,
    val weight: Int = 700,
    val color: ArgbColor = 0xFFFFFFFF,
    val backgroundColor: ArgbColor? = null,
    val outlineColor: ArgbColor? = 0xFF000000,
    val outlineWidth: Float = 0f,
    val shadow: Boolean = true,
    val align: PrompterTextAlign = PrompterTextAlign.CENTER,
    val direction: ContentDirection = ContentDirection.AUTO,
)

@Serializable
enum class AudioTrackKind { MUSIC, VOICEOVER, SFX, EXTRACTED }

@Serializable
data class AudioTrack(
    val id: String = newId(),
    val kind: AudioTrackKind,
    val clips: List<AudioClip> = emptyList(),
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Lower this track automatically while speech is present on the main track. */
    val ducking: Boolean = false,
)

@Serializable
data class AudioClip(
    val id: String = newId(),
    val source: MediaSource,
    /** Position on the timeline. */
    val startUs: Long,
    val trimStartUs: Long = 0,
    val trimEndUs: Long = source.durationUs,
    val volume: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val loop: Boolean = false,
    val speed: Float = 1f,
) {
    val durationUs: Long get() = ((trimEndUs - trimStartUs) / speed.toDouble()).toLong()
    val endUs: Long get() = startUs + durationUs
}

@Serializable
data class SubtitleCue(
    val id: String = newId(),
    val startUs: Long,
    val endUs: Long,
    val text: String,
    /** Optional word timings for karaoke-style animation. */
    val words: List<WordTiming> = emptyList(),
)

@Serializable
data class WordTiming(val text: String, val startUs: Long, val endUs: Long)

@Serializable
enum class SubtitleAnimation { NONE, FADE, POP, KARAOKE, WORD_BY_WORD, SLIDE_UP }

@Serializable
data class SubtitleStyle(
    val font: PrompterFont = PrompterFont.VAZIRMATN,
    val sizeSp: Float = 26f,
    val weight: Int = 700,
    val color: ArgbColor = 0xFFFFFFFF,
    val activeWordColor: ArgbColor = 0xFFFFD166,
    val backgroundColor: ArgbColor? = 0x99000000,
    val outlineColor: ArgbColor? = null,
    /** Vertical center of the subtitle block, 0 = top, 1 = bottom. */
    val positionY: Float = 0.8f,
    val maxWidthFraction: Float = 0.86f,
    val animation: SubtitleAnimation = SubtitleAnimation.FADE,
    val uppercase: Boolean = false,
)

@Serializable
data class SubtitleTrack(
    val cues: List<SubtitleCue> = emptyList(),
    val style: SubtitleStyle = SubtitleStyle(),
    val language: String? = null,
    /** Burn subtitles into the exported video (otherwise exported as a sidecar .srt). */
    val burnIn: Boolean = true,
    val visible: Boolean = true,
)

@Serializable
data class ExportSettings(
    /** Short side of the output, e.g. 1080 for 1080x1920. */
    val resolutionShortSide: Int = 1080,
    val frameRate: Int = 30,
    /** Null = automatic bitrate based on resolution and frame rate. */
    val videoBitrateBps: Int? = null,
    val codec: VideoCodec = VideoCodec.H264,
    val audioBitrateBps: Int = 192_000,
    val includeAudio: Boolean = true,
    val exportSrt: Boolean = false,
)
