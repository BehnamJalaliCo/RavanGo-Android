package com.ravango.engine.beauty.effects

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * AR face lenses (Snapchat-style). Every lens renders inside the beauty GL pipeline, so it is identical in the
 * preview and in the recording. [needsFace] lenses use the tracked 478-point face mesh (up to two faces).
 *
 * Kinds:
 * - [Kind.WARP] — mesh-warp displacement fields authored in canonical face space (projected by the head pose).
 * - [Kind.PAINT] — a UV-space face-paint texture drawn on the tracked mesh.
 * - [Kind.SPRITE] — 2D billboards anchored in canonical 3D space and projected with the head pose.
 * - [Kind.PARTICLES] — animated sprites around the head or triggered by an expression (blendshapes).
 * - [Kind.GRADE] — full-frame looks (soft focus / bloom).
 */
enum class Lens(val id: String, val kind: Kind, val needsFace: Boolean, val pro: Boolean) {
    BIG_EYES("big_eyes", Kind.WARP, needsFace = true, pro = false),
    PUFFY_CHEEKS("puffy_cheeks", Kind.WARP, needsFace = true, pro = false),
    TINY_FACE("tiny_face", Kind.WARP, needsFace = true, pro = true),
    ALIEN("alien", Kind.WARP, needsFace = true, pro = true),
    FACE_SWIRL("face_swirl", Kind.WARP, needsFace = true, pro = true),
    SMOOTH_GLOW("smooth_glow", Kind.GRADE, needsFace = false, pro = false),
    FRECKLES("freckles", Kind.PAINT, needsFace = true, pro = false),
    SPARKLES("sparkles", Kind.PARTICLES, needsFace = true, pro = false),
    SUNGLASSES("sunglasses", Kind.SPRITE, needsFace = true, pro = false),
    CAT("cat", Kind.SPRITE, needsFace = true, pro = false),
    CROWN("crown", Kind.SPRITE, needsFace = true, pro = true),
    RAINBOW("rainbow", Kind.PARTICLES, needsFace = true, pro = true),
    ;

    enum class Kind { WARP, PAINT, SPRITE, PARTICLES, GRADE }

    /** True for lenses that react to an expression (the UI shows a hint such as "Open your mouth"). */
    val hasTrigger: Boolean get() = this == RAINBOW

    companion object {
        fun fromId(id: String?): Lens? = entries.firstOrNull { it.id == id }
    }
}

/** Live colour filters (3D LUTs generated procedurally, see [LutGenerator]). [NONE] is the unfiltered image. */
enum class LiveFilter(val id: String, val pro: Boolean) {
    NONE("none", false),
    VIVID("vivid", false),
    WARM("warm", false),
    COOL("cool", false),
    MONO("mono", false),
    PASTEL("pastel", false),
    FADE("fade", false),
    FILM("film", true),
    NOIR("noir", true),
    SUNSET("sunset", true),
    TEAL_ORANGE("teal_orange", true),
    VINTAGE("vintage", true),
    CINEMA("cinema", true),
    ;

    companion object {
        fun fromId(id: String?): LiveFilter = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Background effect driven by person segmentation (MediaPipe selfie segmenter). */
sealed interface BackgroundEffect {
    val active: Boolean get() = true

    data object None : BackgroundEffect {
        override val active: Boolean get() = false
    }

    /** Portrait (bokeh-like) blur; [strength] 0..100. */
    data class Blur(val strength: Int = 60) : BackgroundEffect

    /** Solid colour (ARGB). */
    data class Color(val argb: Long) : BackgroundEffect

    /** Vertical gradient from [top] to [bottom] (ARGB). */
    data class Gradient(val top: Long, val bottom: Long) : BackgroundEffect

    /** The photo set with [CameraEffects.setBackgroundImage] ("cover" fit). */
    data object Image : BackgroundEffect
}

/** Everything the effects layer applies on top of beauty. */
data class EffectsState(
    val lens: Lens? = null,
    val filter: LiveFilter = LiveFilter.NONE,
    /** 0..100. */
    val filterIntensity: Int = 100,
    val background: BackgroundEffect = BackgroundEffect.None,
) {
    val isNeutral: Boolean get() = lens == null && (filter == LiveFilter.NONE || filterIntensity <= 0) && !background.active
}

/**
 * A live filter swipe on the preview: [target] slides in from the side the finger moves away from.
 * [progress] is the signed fraction of the preview width travelled (−1..1; negative = swiping towards the start
 * edge). [screenRotationCw] is the rotation of the output frame on screen (`PreviewFrame.rotationCw`), so the split
 * line follows the finger whatever the device orientation.
 */
data class FilterSwipe(val target: LiveFilter, val progress: Float, val screenRotationCw: Int = 0)

/** Runtime status of the effects layer (for UI hints). */
data class EffectsStatus(
    /** A face lens is selected but no face is currently tracked. */
    val lensNeedsFace: Boolean = false,
    /** Background effects cannot run (segmentation model or native library unavailable). */
    val backgroundUnavailable: Boolean = false,
    /** A background effect is waiting for its first segmentation mask. */
    val backgroundWarmingUp: Boolean = false,
    /** Set when [BackgroundEffect.Image] is selected but no photo is loaded. */
    val backgroundImageMissing: Boolean = false,
)

/**
 * CONTRACT (ADDED) — Snapchat-style camera effects on top of beauty: lenses, swipeable live colour filters and
 * background effects. Implemented by the beauty engine (same GL processor, shared face tracking), bound in Hilt as
 * a singleton; attach `BeautyEngine.processor` to the camera to see them.
 */
interface CameraEffects {
    val effects: StateFlow<EffectsState>
    val effectsStatus: StateFlow<EffectsStatus>

    /** True once a background photo is available for [BackgroundEffect.Image]. */
    val hasBackgroundImage: StateFlow<Boolean>

    fun setLens(lens: Lens?)
    fun setFilter(filter: LiveFilter)
    fun setFilterIntensity(intensity: Int)

    /** Live split preview while a finger drags across the preview; null ends it (after committing with [setFilter]). */
    fun setFilterSwipe(swipe: FilterSwipe?)

    fun setBackground(effect: BackgroundEffect)

    /**
     * Decodes (downscaled) and keeps the photo at [uri] as the replacement background; it is copied into app storage
     * so it survives restarts. Returns false when the image cannot be read. Call from a coroutine (does I/O).
     */
    suspend fun setBackgroundImage(uri: Uri): Boolean

    /** Warms up the assets a lens or filter needs (LUTs, sprite atlas) so switching is instant. */
    fun prefetch(filters: List<LiveFilter>) {}
}
