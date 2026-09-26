package com.ravango.engine.editor.effects

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.toUri
import com.ravango.core.model.OverlayItem
import com.ravango.engine.editor.composition.OverlayTiming
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Picture-in-picture video overlay.
 *
 * Why not a second composition sequence: Media3 1.8's DefaultVideoCompositor draws the *primary* sequence on top and
 * derives output timestamps from it, so a PiP sequence would be hidden under the full-frame main track. Instead this
 * effect decodes the PiP source itself (MediaExtractor + MediaCodec into a SurfaceTexture on the effect's GL thread) and
 * composites the frame whose source time matches each output frame's presentation time — frame-accurate in both
 * CompositionPlayer preview and Transformer export, with rounded corners, border, opacity and in/out animations.
 * The PiP's audio is mixed as a separate audio-only sequence by the composition builder.
 */
data class PipEffect(val item: OverlayItem.Video) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = PipShaderProgram(context.applicationContext, item)
}

private class PipShaderProgram(private val context: Context, private val item: OverlayItem.Video) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = GlSl.program(FRAGMENT)
    private var outW = 1
    private var outH = 1
    private var source: PipFrameSource? = null
    private var sourceFailed = false

    /** External texture bound when no PiP frame exists yet (a 2D texture must never be bound to the OES target). */
    private var placeholderTex = -1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        outW = inputWidth
        outH = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val anim = OverlayTiming.state(item, presentationTimeUs)
        var hasFrame = false
        if (anim.visible && !sourceFailed) {
            val src = source ?: runCatching { PipFrameSource(context, item.source.uri).also { source = it } }
                .onFailure { sourceFailed = true; RgLog.w(TAG, "PiP source unavailable: ${item.source.uri}", it) }
                .getOrNull()
            if (src != null) {
                val sourceUs = (item.trimStartUs + (presentationTimeUs - item.startUs)).coerceIn(0, item.source.durationUs.coerceAtLeast(0))
                hasFrame = runCatching { src.frameAt(sourceUs) }.onFailure { RgLog.w(TAG, "PiP decode failed", it) }.getOrDefault(false)
            }
        }
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            val src = source
            val pipTex = src?.texId ?: placeholderTex.takeIf { it >= 0 } ?: GlUtil.createExternalTexture().also { placeholderTex = it }
            program.setSamplerTexIdUniform("uPipSampler", pipTex, 1)
            program.setFloatsUniform("uPipMatrix", src?.transform ?: IDENTITY)
            val t = item.transform
            val widthPx = outW * t.scale * anim.scale
            val aspect = src?.aspectHeightOverWidth ?: if (item.source.width > 0) item.source.height.toFloat() / item.source.width else 9f / 16f
            val heightPx = widthPx * aspect
            val rad = Math.toRadians((t.rotationDegrees + anim.rotationDegrees).toDouble())
            program.setFloatsUniform("uOutSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
            program.setFloatsUniform("uCenter", floatArrayOf((t.centerX + anim.offsetX) * outW, (t.centerY + anim.offsetY) * outH))
            program.setFloatsUniform("uHalfSize", floatArrayOf(widthPx / 2f, heightPx / 2f))
            program.setFloatsUniform("uRot", floatArrayOf(cos(rad).toFloat(), sin(rad).toFloat()))
            program.setFloatsUniform(
                "uStyle",
                floatArrayOf(
                    if (hasFrame) (t.opacity * anim.alpha).coerceIn(0f, 1f) else 0f,
                    item.cornerRadius.coerceIn(0f, 0.5f) * minOf(widthPx, heightPx),
                    if (item.borderColor != null) outW * 0.006f else 0f,
                    0f,
                ),
            )
            program.setFloatsUniform("uBorderColor", item.borderColor?.argbToFloats() ?: floatArrayOf(0f, 0f, 0f, 0f))
            GlSl.drawQuad(program)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        source?.release()
        source = null
        if (placeholderTex >= 0) runCatching { GlUtil.deleteTexture(placeholderTex) }
        placeholderTex = -1
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val TAG = "PipEffect"
        private val IDENTITY = GlUtil.create4x4IdentityMatrix()

        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
precision highp float;
uniform sampler2D uTexSampler;
uniform samplerExternalOES uPipSampler;
uniform mat4 uPipMatrix;
uniform vec2 uOutSize;
uniform vec2 uCenter;
uniform vec2 uHalfSize;
uniform vec2 uRot;
uniform vec4 uStyle; // alpha, corner radius px, border px, unused
uniform vec4 uBorderColor;
varying vec2 vTexCoord;
float sdRoundBox(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + vec2(r);
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
void main() {
  vec4 bg = texture2D(uTexSampler, vTexCoord);
  float alpha = uStyle.x;
  if (alpha <= 0.0) { gl_FragColor = bg; return; }
  vec2 px = vec2(vTexCoord.x, 1.0 - vTexCoord.y) * uOutSize;
  vec2 q = px - uCenter;
  vec2 r = vec2(q.x * uRot.x + q.y * uRot.y, -q.x * uRot.y + q.y * uRot.x);
  float d = sdRoundBox(r, uHalfSize, min(uStyle.y, min(uHalfSize.x, uHalfSize.y)));
  float inside = clamp(0.5 - d, 0.0, 1.0);
  if (inside <= 0.0) { gl_FragColor = bg; return; }
  vec2 local = clamp(r / (2.0 * uHalfSize) + 0.5, 0.0, 1.0);
  vec2 tc = (uPipMatrix * vec4(local.x, 1.0 - local.y, 0.0, 1.0)).xy;
  vec3 pip = texture2D(uPipSampler, tc).rgb;
  float border = uStyle.z > 0.0 ? clamp(d + uStyle.z + 0.5, 0.0, 1.0) : 0.0;
  vec3 col = mix(pip, uBorderColor.rgb, border * uBorderColor.a);
  gl_FragColor = vec4(mix(bg.rgb, col, inside * alpha), 1.0);
}
"""
    }
}

/**
 * Decodes a video file into an external OES texture on the calling (GL) thread, delivering the frame closest to a
 * requested source time. Forward requests decode sequentially; backward or far jumps seek to the previous sync sample.
 */
internal class PipFrameSource(context: Context, uriString: String) {
    val texId: Int = GlUtil.createExternalTexture()
    val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    var aspectHeightOverWidth: Float = 9f / 16f
        private set

    private val thread = HandlerThread("RgPipFrames").apply { start() }
    private val surfaceTexture = SurfaceTexture(texId)
    private val surface: Surface
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val lock = Object()
    private var frameAvailable = false
    private var inputDone = false
    private var outputDone = false
    private var currentPts = -1L
    private var pending: Decoded? = null
    private val frameDurationUs: Long

    private data class Decoded(val index: Int, val ptsUs: Long, val eos: Boolean)

    init {
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }, Handler(thread.looper))
        surface = Surface(surfaceTexture)
        val uri = uriString.toUri()
        if (uri.scheme == null || uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IllegalArgumentException("No video track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        aspectHeightOverWidth = if (rotation % 180 == 0) h.toFloat() / w else w.toFloat() / h
        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE) } else 30f
        frameDurationUs = (1_000_000f / fps.coerceIn(1f, 240f)).toLong()
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    /** Makes the frame for [sourceUs] current in [texId]. Returns false if no frame could be produced. */
    fun frameAt(sourceUs: Long): Boolean {
        val half = frameDurationUs / 2
        val needSeek = currentPts < 0 && pending == null && !inputDone ||
            (currentPts >= 0 && sourceUs < currentPts - half) ||
            (currentPts >= 0 && sourceUs - currentPts > SEEK_AHEAD_US)
        if (needSeek) seek(sourceUs)
        if (currentPts >= 0 && abs(sourceUs - currentPts) <= half && pending?.let { it.ptsUs > sourceUs + half } != false) return true
        var guard = 0
        while (guard++ < MAX_FRAMES_PER_REQUEST) {
            val p = pending ?: pull() ?: break
            pending = p
            if (p.eos) break
            if (p.ptsUs > sourceUs + half && currentPts >= 0) break
            // p is due (or nothing has been shown yet): look at the next frame to decide whether to skip p.
            val next = if (p.ptsUs <= sourceUs + half) pull() else null
            if (next != null && !next.eos && next.ptsUs <= sourceUs + half) {
                codec.releaseOutputBuffer(p.index, false)
                pending = next
                continue
            }
            render(p)
            pending = next
            break
        }
        return currentPts >= 0
    }

    private fun seek(sourceUs: Long) {
        pending?.let { if (!it.eos) runCatching { codec.releaseOutputBuffer(it.index, false) } }
        pending = null
        codec.flush()
        extractor.seekTo(sourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
        outputDone = false
        currentPts = -1
    }

    private fun pull(): Decoded? {
        if (outputDone) return Decoded(-1, Long.MAX_VALUE, true)
        var attempts = 0
        while (attempts++ < 400) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 5_000)
            if (outIndex >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) outputDone = true
                if (eos && info.size == 0) {
                    codec.releaseOutputBuffer(outIndex, false)
                    return Decoded(-1, Long.MAX_VALUE, true)
                }
                return Decoded(outIndex, info.presentationTimeUs, false)
            }
        }
        return null
    }

    private fun render(d: Decoded) {
        synchronized(lock) { frameAvailable = false }
        codec.releaseOutputBuffer(d.index, true)
        synchronized(lock) {
            val deadline = System.nanoTime() + FRAME_WAIT_NS
            while (!frameAvailable) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                lock.wait(remainingMs)
            }
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)
        currentPts = d.ptsUs
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        thread.quitSafely()
        runCatching { GlUtil.deleteTexture(texId) }
    }

    private companion object {
        const val SEEK_AHEAD_US = 2_500_000L
        const val MAX_FRAMES_PER_REQUEST = 600
        const val FRAME_WAIT_NS = 250_000_000L
    }
}
