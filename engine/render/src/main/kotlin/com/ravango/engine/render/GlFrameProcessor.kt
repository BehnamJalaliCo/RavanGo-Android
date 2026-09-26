package com.ravango.engine.render

/**
 * A frame travelling through the GPU pipeline: an RGBA GL_TEXTURE_2D in upright (display) orientation,
 * already rotated/mirrored for the output. Texture coordinates follow GL convention (origin bottom-left).
 */
data class GlTextureFrame(
    val textureId: Int,
    val width: Int,
    val height: Int,
    /** Sensor timestamp in nanoseconds (monotonic, same base as the encoder presentation time). */
    val timestampNs: Long,
    /** True for the front camera; lets processors reason about left/right in landmarks if needed. */
    val mirrored: Boolean,
)

/** Information about the GL environment, given once when a processor is attached. */
data class GlProcessingContext(
    val glVersion: Int,
    val maxTextureSize: Int,
)

/**
 * Pluggable GPU stage between camera input and outputs (preview + encoder). All methods are invoked on the
 * renderer's GL thread with its EGL context current. Implementations must be fast: the renderer measures
 * [process] time and reports it via [onFrameTiming] so heavy processors can adapt their quality.
 *
 * The camera engine renders `process(...)` output to the preview and, while recording, to the encoder —
 * so effects are identical in preview and in the recorded file.
 */
interface GlFrameProcessor {
    /** Allocates GL resources. */
    fun onAttach(context: GlProcessingContext)

    /**
     * Processes [input] and returns the output frame. May return [input] unchanged (bypass).
     * The returned texture must stay valid until the next call to [process] or [onDetach].
     */
    fun process(input: GlTextureFrame): GlTextureFrame

    /** Feedback: how long the last [process] call took on the GPU/CPU side, and the frame budget. */
    fun onFrameTiming(processNanos: Long, frameBudgetNanos: Long) {}

    /** Releases GL resources. */
    fun onDetach()
}
