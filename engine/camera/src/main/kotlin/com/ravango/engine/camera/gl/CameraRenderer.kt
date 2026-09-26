package com.ravango.engine.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.LensFacing
import com.ravango.engine.camera.PipelineStats
import com.ravango.engine.camera.PreviewFrame
import com.ravango.engine.camera.recorder.PtsClock
import com.ravango.engine.render.EglCore
import com.ravango.engine.render.FullScreenQuad
import com.ravango.engine.render.GlFrameProcessor
import com.ravango.engine.render.GlDiagnostics
import com.ravango.engine.render.GlFramebuffer
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlProgram
import com.ravango.engine.render.GlTextureFrame
import com.ravango.engine.render.GlUtil
import com.ravango.engine.render.Shaders
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Static description of the stream the renderer converts. */
internal data class RenderGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val sensorOrientation: Int,
    val facing: LensFacing,
    val outputWidth: Int,
    val outputHeight: Int,
    /** Processed frames are mirrored (selfie view) — true for the front camera. */
    val mirror: Boolean,
    /** Keep the mirror in the recording (otherwise the encoder receives the true, un-mirrored image). */
    val mirrorRecording: Boolean,
    val timestampRealtime: Boolean,
    val fps: Int,
)

/** The mapping in use for the current frame, read by tap-to-focus on other threads. */
internal data class FrameMapping(
    val rotationCw: Int,
    val crop: CropRect,
    val mirror: Boolean,
    val previewRotationCw: Int,
)

/**
 * The camera GL pipeline on a dedicated thread:
 *
 * `SurfaceTexture (OES) → normalize pass (rotate to gravity-upright, mirror, crop to aspect) → RGBA FBO →
 * GlFrameProcessor chain → encoder surface (first) + preview surface (letterboxed, rotated for the locked screen)`.
 *
 * The camera never waits on this thread: SurfaceTexture keeps only the newest buffer. When a frame takes longer
 * than the budget while recording, the next preview draw is skipped so the encoder keeps every frame.
 */
internal class CameraRenderer(private val callbacks: Callbacks) {

    interface Callbacks {
        fun onPreviewFrame(frame: PreviewFrame)
        fun onStats(stats: PipelineStats)
        fun onEncoderSurfaceLost()
    }

    private val thread = HandlerThread("RgCameraGL", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler

    // --- GL-thread state -------------------------------------------------------------------------------------
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var oesProgram: GlProgram? = null
    private var copyProgram: GlProgram? = null
    private var fbo: GlFramebuffer? = null
    private var processingContext: GlProcessingContext? = null

    private var geometry: RenderGeometry? = null
    private var deviceOrientation = 0
    private var lockedOrientation = -1
    private var geometryDirty = true

    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val stMatrix = FloatArray(16)
    private val bufferMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val stCompensation = FloatArray(16)
    private val stTmp = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    private val encoderMatrix = FloatArray(16)

    private var previewWindow: Surface? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewRotation = 0
    private var fitX = 0
    private var fitY = 0
    private var fitW = 0
    private var fitH = 0

    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderClock: PtsClock? = null
    private var lastEncoderPtsNs = -1L

    private val attached = ArrayList<GlFrameProcessor>()
    private var desired: List<GlFrameProcessor> = emptyList()

    private var pendingFrames = 0
    private var renderScheduled = false
    private var lastRawTimestamp = 0L
    private var lastFrameCostNs = 0L
    private var skippedLastPreview = false

    // stats
    private var statsWindowStart = 0L
    private var previewFramesInWindow = 0
    private var encoderFramesInWindow = 0
    private var processNanosInWindow = 0L
    private var framesInWindow = 0
    private var droppedCamera = 0L
    private var droppedPreview = 0L
    private var errorLogBudget = 20

    private val renderRunnable = Runnable { renderFrame() }

    /** Read from other threads for tap-to-focus. */
    val mapping = AtomicReference<FrameMapping?>(null)

    /** When true the preview shows the frame *before* the processor chain; the encoder still gets the processed frame. */
    @Volatile var previewBypass: Boolean = false

    /** Scales the frame budget reported to processors (thermal pressure asks them to be cheaper). */
    @Volatile var budgetScale: Float = 1f

    val isStarted: Boolean get() = cameraSurface != null

    /** Starts the GL thread and returns the camera output surface. Blocking. */
    fun start(): Surface {
        thread.start()
        handler = Handler(thread.looper)
        return runOnGl(3_000) { setupGl() } ?: throw IllegalStateException("GL setup timed out")
    }

    fun setBufferSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun setGeometry(g: RenderGeometry) = post {
        geometry = g
        geometryDirty = true
    }

    fun setDeviceOrientation(degrees: Int) = post {
        val snapped = FrameGeometry.snap(degrees)
        if (snapped != deviceOrientation) {
            deviceOrientation = snapped
            geometryDirty = true
        }
    }

    fun setFrameProcessors(list: List<GlFrameProcessor>) = post {
        desired = list.toList()
        syncProcessors()
    }

    /**
     * Called from the UI thread (SurfaceHolder callbacks): must never throw. A surface that cannot be used (already
     * abandoned, connected to another producer…) is logged and skipped; the next surfaceChanged retries.
     */
    fun setPreviewSurface(surface: Surface, width: Int, height: Int) {
        runOnGlQuietly("setPreviewSurface") {
            if (previewWindow !== surface) {
                releasePreviewSurface()
                val e = egl ?: return@runOnGlQuietly
                if (!surface.isValid) {
                    RgLog.w(TAG, "preview surface is no longer valid")
                    return@runOnGlQuietly
                }
                previewEgl = try {
                    e.createWindowSurface(surface)
                } catch (t: Throwable) {
                    RgLog.e(TAG, "preview surface failed", t)
                    EGL14.EGL_NO_SURFACE
                }
                if (previewEgl != EGL14.EGL_NO_SURFACE) {
                    try {
                        e.makeCurrent(previewEgl)
                        EGL14.eglSwapInterval(e.display, 0)
                        previewWindow = surface
                    } catch (t: Throwable) {
                        RgLog.e(TAG, "preview surface cannot be made current", t)
                        runCatching { e.makeCurrent(pbuffer) }
                        runCatching { e.releaseSurface(previewEgl) }
                        previewEgl = EGL14.EGL_NO_SURFACE
                    }
                    runCatching { e.makeCurrent(pbuffer) }
                }
            }
            previewWidth = width
            previewHeight = height
            updateFit()
        }
    }

    fun clearPreviewSurface(surface: Surface) {
        runOnGlQuietly("clearPreviewSurface") { if (previewWindow === surface) releasePreviewSurface() }
    }

    /** Starts feeding [surface] (a MediaCodec input surface). Locks the output orientation. Blocking. */
    fun startEncoding(surface: Surface, width: Int, height: Int, clock: PtsClock): Boolean =
        runOnGl(2_000) {
            val e = egl ?: return@runOnGl false
            encoderEgl = try {
                e.createWindowSurface(surface)
            } catch (t: Throwable) {
                RgLog.e(TAG, "encoder surface failed", t)
                return@runOnGl false
            }
            encoderWidth = width
            encoderHeight = height
            encoderClock = clock
            lastEncoderPtsNs = -1
            lockedOrientation = deviceOrientation
            e.makeCurrent(pbuffer)
            true
        } ?: false

    /** Stops feeding the encoder; after this returns no more frames reach it. Blocking. */
    fun stopEncoding() {
        runOnGl(2_000) {
            releaseEncoderSurface()
            if (lockedOrientation != deviceOrientation) geometryDirty = true
            lockedOrientation = -1
        }
    }

    fun release() {
        if (!thread.isAlive) return
        runOnGl(3_000) { teardownGl() }
        thread.quitSafely()
    }

    // --- GL thread -------------------------------------------------------------------------------------------

    private fun setupGl(): Surface {
        val e = EglCore()
        egl = e
        pbuffer = e.createOffscreenSurface(1, 1)
        e.makeCurrent(pbuffer)
        oesTexture = GlUtil.createOesTexture()
        val st = SurfaceTexture(oesTexture)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        GlDiagnostics.captureContextInfo(e.glVersion)
        oesProgram = GlProgram(Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_OES, "camera.oes")
        copyProgram = GlProgram(Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_2D, "camera.copy")
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        processingContext = GlProcessingContext(e.glVersion, max[0])
        statsWindowStart = System.nanoTime()
        syncProcessors()
        return Surface(st).also { cameraSurface = it }
    }

    private fun teardownGl() {
        val e = egl ?: return
        runCatching { e.makeCurrent(pbuffer) }
        attached.forEach { p -> runCatching { p.onDetach() }.onFailure { RgLog.w(TAG, "processor detach failed", it) } }
        attached.clear()
        releaseEncoderSurface()
        releasePreviewSurface()
        fbo?.release(); fbo = null
        oesProgram?.release(); oesProgram = null
        copyProgram?.release(); copyProgram = null
        GlUtil.deleteTexture(oesTexture); oesTexture = 0
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release(); surfaceTexture = null
        cameraSurface?.release(); cameraSurface = null
        if (pbuffer != EGL14.EGL_NO_SURFACE) e.releaseSurface(pbuffer)
        pbuffer = EGL14.EGL_NO_SURFACE
        e.release()
        egl = null
        mapping.set(null)
    }

    private fun syncProcessors() {
        val ctx = processingContext ?: return
        val e = egl ?: return
        runCatching { e.makeCurrent(pbuffer) }
        val iterator = attached.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            if (desired.none { it === p }) {
                runCatching { p.onDetach() }.onFailure { RgLog.w(TAG, "processor detach failed", it) }
                iterator.remove()
            }
        }
        for (p in desired) {
            if (attached.none { it === p }) {
                runCatching { p.onAttach(ctx) }
                    .onSuccess { attached += p }
                    .onFailure { RgLog.e(TAG, "processor attach failed; it is skipped", it) }
            }
        }
        // Keep the requested order.
        attached.sortBy { p -> desired.indexOfFirst { it === p } }
    }

    private fun onFrameAvailable() {
        pendingFrames++
        if (!renderScheduled) {
            renderScheduled = true
            handler.post(renderRunnable)
        }
    }

    private fun renderFrame() {
        renderScheduled = false
        val e = egl ?: return
        val st = surfaceTexture ?: return
        val frames = pendingFrames
        pendingFrames = 0
        if (frames > 1) droppedCamera += frames - 1
        val start = System.nanoTime()
        try {
            e.makeCurrent(pbuffer)
            st.updateTexImage()
        } catch (t: Throwable) {
            logError("updateTexImage failed", t)
            return
        }
        val g = geometry ?: return
        val rawTs = st.timestamp
        if (rawTs == 0L || rawTs == lastRawTimestamp) return
        lastRawTimestamp = rawTs
        try {
            if (geometryDirty) recomputeGeometry(g)
            val frameFbo = fbo ?: return
            val ts = if (g.timestampRealtime) rawTs - (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else rawTs
            st.getTransformMatrix(stMatrix)
            // Cancel the orientation Camera2 folds into the SurfaceTexture matrix; our geometry targets the raw buffer.
            FrameGeometry.cancelSurfaceTextureOrientation(stMatrix, stCompensation)
            Matrix.multiplyMM(stTmp, 0, stMatrix, 0, stCompensation, 0)
            Matrix.multiplyMM(texMatrix, 0, stTmp, 0, bufferMatrix, 0)

            GLES20.glDisable(GLES20.GL_BLEND)
            frameFbo.bind()
            val oes = oesProgram!!
            oes.use()
            oes.setMat4("uMvpMatrix", identity)
            oes.setMat4("uTexMatrix", texMatrix)
            oes.bindTexture("uTexture", 0, oesTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            FullScreenQuad.draw(oes)

            val input = GlTextureFrame(frameFbo.textureId, frameFbo.width, frameFbo.height, ts, g.mirror)
            var frame = input
            val budget = (1_000_000_000.0 / g.fps.coerceAtLeast(1) * budgetScale).toLong()
            var processTotal = 0L
            var i = 0
            while (i < attached.size) {
                val p = attached[i]
                val t0 = System.nanoTime()
                val out = try {
                    p.process(frame)
                } catch (t: Throwable) {
                    // A broken effect must never break the camera: drop it and keep going.
                    RgLog.e(TAG, "processor failed; detaching it", t)
                    runCatching { p.onDetach() }
                    attached.removeAt(i)
                    desired = desired.filterNot { it === p }
                    continue
                }
                val dt = System.nanoTime() - t0
                processTotal += dt
                runCatching { p.onFrameTiming(dt, budget) }
                frame = out
                i++
            }
            restoreGlState()

            val encoding = encoderEgl != EGL14.EGL_NO_SURFACE
            if (encoding) drawToEncoder(e, frame, ts)
            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                val behind = encoding && lastFrameCostNs > budget * 85 / 100 && !skippedLastPreview
                if (behind) {
                    droppedPreview++
                    skippedLastPreview = true
                } else {
                    drawPreview(e, if (previewBypass) input else frame)
                    skippedLastPreview = false
                }
            }
            processNanosInWindow += processTotal
            framesInWindow++
        } catch (t: Throwable) {
            logError("frame failed", t)
        } finally {
            lastFrameCostNs = System.nanoTime() - start
            publishStatsIfDue(g)
        }
    }

    /** Processors may leave arbitrary GL state behind; reset everything our own draws rely on. */
    private fun restoreGlState() {
        GlFramebuffer.unbind()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        if ((egl?.glVersion ?: 2) >= 3) android.opengl.GLES30.glBindVertexArray(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
    }

    private fun drawToEncoder(e: EglCore, frame: GlTextureFrame, ts: Long) {
        val clock = encoderClock ?: return
        if (!clock.isAnchored && !clock.isPaused) clock.anchor(ts)
        val pts = clock.mapNs(ts)
        if (pts < 0 || pts <= lastEncoderPtsNs) return
        try {
            e.makeCurrent(encoderEgl)
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            drawTexture(frame.textureId, encoderMatrix)
            e.setPresentationTime(encoderEgl, pts)
            if (!e.swapBuffers(encoderEgl)) {
                RgLog.e(TAG, "encoder swap failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                releaseEncoderSurface()
                callbacks.onEncoderSurfaceLost()
                return
            }
            lastEncoderPtsNs = pts
            encoderFramesInWindow++
        } catch (t: Throwable) {
            logError("encoder draw failed", t)
            releaseEncoderSurface()
            callbacks.onEncoderSurfaceLost()
        }
    }

    private fun drawPreview(e: EglCore, frame: GlTextureFrame) {
        try {
            e.makeCurrent(previewEgl)
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(fitX, fitY, fitW, fitH)
            drawTexture(frame.textureId, previewMatrix)
            if (!e.swapBuffers(previewEgl)) {
                RgLog.w(TAG, "preview swap failed; dropping preview surface")
                releasePreviewSurface()
                return
            }
            previewFramesInWindow++
        } catch (t: Throwable) {
            logError("preview draw failed", t)
        }
    }

    private fun drawTexture(textureId: Int, matrix: FloatArray) {
        val p = copyProgram ?: return
        p.use()
        p.setMat4("uMvpMatrix", identity)
        p.setMat4("uTexMatrix", matrix)
        p.bindTexture("uTexture", 0, textureId)
        FullScreenQuad.draw(p)
    }

    private fun recomputeGeometry(g: RenderGeometry) {
        geometryDirty = false
        val orientation = if (lockedOrientation >= 0) lockedOrientation else deviceOrientation
        val rotation = FrameGeometry.rotationCw(g.sensorOrientation, orientation, g.facing)
        val (uw, uh) = FrameGeometry.rotatedSize(g.bufferWidth, g.bufferHeight, rotation)
        val crop = FrameGeometry.centerCrop(uw.toFloat() / uh, g.outputWidth.toFloat() / g.outputHeight)
        FrameGeometry.outputToBufferGl(rotation, crop, g.mirror).toGlMatrix(bufferMatrix)
        previewRotation = FrameGeometry.previewRotationCw(orientation)
        FrameGeometry.displayToFrameGl(previewRotation).toGlMatrix(previewMatrix)
        FrameGeometry.encoderMatrixGl(g.mirror && !g.mirrorRecording).toGlMatrix(encoderMatrix)
        val current = fbo
        if (current == null) {
            fbo = GlFramebuffer(g.outputWidth, g.outputHeight)
        } else {
            current.ensureSize(g.outputWidth, g.outputHeight)
        }
        updateFit()
        mapping.set(FrameMapping(rotation, crop, g.mirror, previewRotation))
        callbacks.onPreviewFrame(PreviewFrame(g.outputWidth, g.outputHeight, previewRotation))
    }

    private fun updateFit() {
        val g = geometry ?: return
        if (previewWidth <= 0 || previewHeight <= 0) return
        val (dw, dh) = FrameGeometry.displayedSize(g.outputWidth, g.outputHeight, previewRotation)
        val fit = FrameGeometry.fit(dw.toFloat(), dh.toFloat(), previewWidth.toFloat(), previewHeight.toFloat())
        fitX = fit.left.roundToInt()
        fitW = fit.width.roundToInt()
        fitH = fit.height.roundToInt()
        // GL viewport origin is bottom-left.
        fitY = (previewHeight - fit.top - fit.height).roundToInt()
    }

    private fun releasePreviewSurface() {
        val e = egl
        if (e != null && previewEgl != EGL14.EGL_NO_SURFACE) {
            runCatching { e.makeCurrent(pbuffer) }
            runCatching { e.releaseSurface(previewEgl) }
        }
        previewEgl = EGL14.EGL_NO_SURFACE
        previewWindow = null
    }

    private fun releaseEncoderSurface() {
        val e = egl
        if (e != null && encoderEgl != EGL14.EGL_NO_SURFACE) {
            runCatching { e.makeCurrent(pbuffer) }
            runCatching { e.releaseSurface(encoderEgl) }
        }
        encoderEgl = EGL14.EGL_NO_SURFACE
        encoderClock = null
    }

    private fun publishStatsIfDue(g: RenderGeometry?) {
        val now = System.nanoTime()
        val elapsed = now - statsWindowStart
        if (elapsed < 1_000_000_000L) return
        val seconds = elapsed / 1e9f
        val fps = g?.fps ?: 30
        callbacks.onStats(
            PipelineStats(
                previewFps = previewFramesInWindow / seconds,
                encoderFps = encoderFramesInWindow / seconds,
                droppedCameraFrames = droppedCamera,
                droppedPreviewFrames = droppedPreview,
                processorMs = if (framesInWindow > 0) processNanosInWindow / framesInWindow / 1e6f else 0f,
                frameBudgetMs = 1000f / fps * budgetScale,
            ),
        )
        statsWindowStart = now
        // Once a second: surface GL errors (driver problems) in the diagnostics log; duplicates are rate-limited.
        if (egl != null) runCatching { GlDiagnostics.drainErrors("camera frames") }
        previewFramesInWindow = 0
        encoderFramesInWindow = 0
        processNanosInWindow = 0
        framesInWindow = 0
    }

    private fun logError(message: String, t: Throwable) {
        if (errorLogBudget > 0) {
            errorLogBudget--
            RgLog.e(TAG, message, t)
        }
    }

    private fun post(block: () -> Unit) {
        if (!thread.isAlive) return
        handler.post(block)
    }

    /** [runOnGl] for callers that must not throw (UI-thread callbacks): failures are logged and recorded. */
    private fun runOnGlQuietly(what: String, block: () -> Unit) {
        try {
            runOnGl(2_000, block)
        } catch (t: Throwable) {
            RgLog.e(TAG, "$what failed", t)
        }
    }

    /** Runs [block] on the GL thread and waits (bounded) for its result. */
    private fun <T> runOnGl(timeoutMs: Long, block: () -> T): T? {
        if (!thread.isAlive) return null
        if (Looper.myLooper() == thread.looper) return block()
        val result = AtomicReference<T?>(null)
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            RgLog.e(TAG, "GL thread did not respond in ${timeoutMs}ms")
            return null
        }
        error.get()?.let { throw it }
        return result.get()
    }

    private companion object {
        const val TAG = "CameraGL"
    }
}
