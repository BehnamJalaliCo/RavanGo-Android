package com.ravango.engine.beauty

import android.opengl.GLES20
import android.opengl.GLES30
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.engine.beauty.geometry.FaceGeometry
import com.ravango.engine.beauty.geometry.MaskGeometry
import com.ravango.engine.beauty.geometry.TriangleBuffer
import com.ravango.engine.beauty.geometry.WarpDerivation
import com.ravango.engine.beauty.geometry.WarpSet
import com.ravango.engine.beauty.gl.BeautyShaders
import com.ravango.engine.beauty.quality.QualityController
import com.ravango.engine.beauty.quality.QualityProfile
import com.ravango.engine.beauty.quality.ThermalHint
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.beauty.tracking.FaceLandmarks
import com.ravango.engine.beauty.tracking.FaceTracker
import com.ravango.engine.beauty.tracking.FrameGrabber
import com.ravango.engine.beauty.tracking.LandmarkStabilizer
import com.ravango.engine.beauty.tracking.PresenceFader
import com.ravango.engine.render.FullScreenQuad
import com.ravango.engine.render.GlFramebuffer
import com.ravango.engine.render.GlFrameProcessor
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlProgram
import com.ravango.engine.render.GlTextureFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Cross-thread inputs of the processor. Written from any thread, read on the GL thread each frame. */
internal class BeautyControls(tier: TierHint) {
    @Volatile var state: BeautyState = BeautyState()
    @Volatile var bypassAll: Boolean = false
    @Volatile var recording: Boolean = false
    @Volatile var thermal: ThermalHint = ThermalHint.NORMAL
    @Volatile var powerSave: Boolean = false
    @Volatile var tier: TierHint = tier
    @Volatile var onStatus: (BeautyStatus) -> Unit = {}
}

/**
 * The beauty GPU pipeline (runs entirely on the camera's GL thread):
 *
 * ```
 * input ─► downsample ─► skin mask ◄─ face masks (oval / exclusions / under-eye / mouth, from landmarks)
 *            │                 │
 *            ├► bilateral H/V ─┤
 *            └► large blur ────┤
 * input ───────────────────────┴► composite (skin ops) ─► makeup ─► warp (reshape) ─► output
 *   └► small y-flipped copy ─► readback (PBO) ─► ML Kit (background) ─► One Euro + prediction
 * ```
 * Every pass is skipped when its features are at neutral; the whole processor returns the input untouched when
 * the state is neutral. All GL objects are created lazily at the sizes needed and released in [onDetach].
 */
internal class BeautyProcessor(private val controls: BeautyControls) : GlFrameProcessor {

    private var glVersion = 2
    private var attached = false
    private var failed = false

    // Programs (lazy).
    private var downsampleProgram: GlProgram? = null
    private val bilateralPrograms = arrayOfNulls<GlProgram>(MAX_BILATERAL_RADIUS + 1)
    private var gaussianProgram: GlProgram? = null
    private var skinProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null
    private var makeupProgram: GlProgram? = null
    private var warpProgram: GlProgram? = null
    private var maskProgram: GlProgram? = null

    // Framebuffers (lazy).
    private var small: GlFramebuffer? = null
    private var smallTmp: GlFramebuffer? = null
    private var smooth: GlFramebuffer? = null
    private var large: GlFramebuffer? = null
    private var largeTmp: GlFramebuffer? = null
    private var skin: GlFramebuffer? = null
    private var faceMask: GlFramebuffer? = null
    private var makeupA: GlFramebuffer? = null
    private var makeupB: GlFramebuffer? = null
    private var maskTmp: GlFramebuffer? = null
    private var outA: GlFramebuffer? = null
    private var outB: GlFramebuffer? = null
    private var faceMaskClear = false

    // Tracking.
    private var tracker: FaceTracker? = null
    private var grabber: FrameGrabber? = null
    private val measured = FaceLandmarks()
    private val predicted = FaceLandmarks()
    private val stabilizer = LandmarkStabilizer()
    private val fader = PresenceFader(0.15f)
    private var lastFaceSeenNs = 0L
    private var lastTrackingId = -1
    private var framesSinceDetect = 0
    private var faceCount = 0
    private var trackingActive = false

    // Geometry.
    private val geometry = FaceGeometry()
    private val masks = MaskGeometry()
    private val warps = WarpSet()
    private val scratch = FloatArray(2)
    private val triOval = TriangleBuffer(256)
    private val triExclude = TriangleBuffer(512)
    private val triUnderEye = TriangleBuffer(128)
    private val triMouth = TriangleBuffer(128)
    private val triLips = TriangleBuffer(256)
    private val triBrows = TriangleBuffer(128)
    private val triLiner = TriangleBuffer(128)
    private val triLashes = TriangleBuffer(128)
    private val triShadow = TriangleBuffer(128)
    private val triBlush = TriangleBuffer(256)
    private val triContour = TriangleBuffer(512)
    private val triHighlight = TriangleBuffer(512)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(512 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Quality.
    private val controller = QualityController(controls.tier)
    private var lastExternalTimingNs = 0L
    private var lastFrameNs = 0L
    private var lastStatus = BeautyStatus()
    private var lastStatusNs = 0L
    private var lastRecording = false

    // ------------------------------------------------------------------------------------------------ lifecycle

    override fun onAttach(context: GlProcessingContext) {
        glVersion = context.glVersion
        attached = true
        failed = false
        tracker = FaceTracker()
        grabber = FrameGrabber(glVersion)
        stabilizer.reset()
        fader.reset()
    }

    override fun onDetach() {
        attached = false
        tracker?.close()
        tracker = null
        grabber?.release()
        grabber = null
        listOf(downsampleProgram, gaussianProgram, skinProgram, compositeProgram, makeupProgram, warpProgram, maskProgram)
            .forEach { it?.release() }
        bilateralPrograms.forEach { it?.release() }
        bilateralPrograms.fill(null)
        downsampleProgram = null; gaussianProgram = null; skinProgram = null; compositeProgram = null
        makeupProgram = null; warpProgram = null; maskProgram = null
        listOf(small, smallTmp, smooth, large, largeTmp, skin, faceMask, makeupA, makeupB, maskTmp, outA, outB).forEach { it?.release() }
        small = null; smallTmp = null; smooth = null; large = null; largeTmp = null; skin = null
        faceMask = null; makeupA = null; makeupB = null; maskTmp = null; outA = null; outB = null
        trackingActive = false
        publishStatus(force = true, nowNs = System.nanoTime())
    }

    override fun onFrameTiming(processNanos: Long, frameBudgetNanos: Long) {
        val now = System.nanoTime()
        lastExternalTimingNs = now
        feedController(processNanos, frameBudgetNanos, now)
    }

    // ------------------------------------------------------------------------------------------------ frame

    override fun process(input: GlTextureFrame): GlTextureFrame {
        val start = System.nanoTime()
        val state = controls.state
        syncControllerInputs(start)
        if (!attached || failed || controls.bypassAll || state.isNeutral || input.width <= 0 || input.height <= 0) {
            if (trackingActive) { trackingActive = false; stabilizer.reset(); fader.reset() }
            publishStatus(force = false, nowNs = start)
            return input
        }
        return try {
            val out = render(input, state)
            val end = System.nanoTime()
            // Without timing feedback from the renderer, measure our own submission cost against a 30 fps budget.
            if (end - lastExternalTimingNs > 2_000_000_000L) feedController(end - start, DEFAULT_BUDGET_NS, end)
            publishStatus(force = false, nowNs = end)
            out
        } catch (t: Throwable) {
            // Never break the camera: bypass for the rest of this attachment and report it.
            RgLog.e(TAG, "Beauty pipeline failed; bypassing", t)
            failed = true
            GlFramebuffer.unbind()
            publishStatus(force = true, nowNs = System.nanoTime())
            input
        }
    }

    private fun render(input: GlTextureFrame, state: BeautyState): GlTextureFrame {
        val w = input.width
        val h = input.height
        val aspect = w.toFloat() / h
        val nowNs = if (input.timestampNs > 0) input.timestampNs else System.nanoTime()
        val dtSec = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs
        val profile = QualityProfile.of(controller.level)

        resetGlState()

        // ---------------------------------------------------------------- tracking
        trackingActive = profile.faceEffects && tracker?.unavailable == false
        if (trackingActive) updateTracking(input, profile, nowNs) else lastTrackingId = -1
        val visible = trackingActive && stabilizer.hasEstimate && nowNs - lastFaceSeenNs < FACE_STALE_NS
        val presence = fader.update(visible, dtSec)
        val hasFace = presence > 0f && stabilizer.hasEstimate
        if (hasFace) {
            stabilizer.predict(nowNs / 1e9, predicted)
            geometry.update(predicted)
        }

        // ---------------------------------------------------------------- intensities
        fun unipolar(f: BeautyFeature) = state.intensity(f) / 100f
        val faceK = if (profile.faceEffects && hasFace) presence else 0f
        val smoothK = if (profile.smoothing) unipolar(BeautyFeature.SMOOTH_SKIN) else 0f
        val retouchK = if (profile.retouchAndBlemish) unipolar(BeautyFeature.SKIN_RETOUCH) else 0f
        val blemishK = if (profile.retouchAndBlemish) unipolar(BeautyFeature.BLEMISH_REMOVAL) else 0f
        val brightK = unipolar(BeautyFeature.SKIN_BRIGHTNESS)
        val whitenK = unipolar(BeautyFeature.WHITENING)
        val toneT = (state.intensity(BeautyFeature.SKIN_TONE) - 50) / 50f
        val sharpenK = if (profile.sharpen) unipolar(BeautyFeature.SHARPEN) * 0.9f else 0f
        val darkK = unipolar(BeautyFeature.DARK_CIRCLES) * faceK
        val teethK = unipolar(BeautyFeature.TEETH_WHITENING) * faceK
        fun layerK(f: MakeupFeature) = state.layer(f).intensity / 100f * faceK
        val foundationK = layerK(MakeupFeature.FOUNDATION)
        val needMakeupA = layerK(MakeupFeature.LIPSTICK) > 0f || layerK(MakeupFeature.LIP_COLOR) > 0f ||
            layerK(MakeupFeature.EYEBROW) > 0f || layerK(MakeupFeature.EYELINER) > 0f || layerK(MakeupFeature.EYELASHES) > 0f
        val needMakeupB = layerK(MakeupFeature.EYESHADOW) > 0f || layerK(MakeupFeature.BLUSH) > 0f ||
            layerK(MakeupFeature.CONTOUR) > 0f || layerK(MakeupFeature.HIGHLIGHT) > 0f
        warps.clear()
        if (faceK > 0f && WarpDerivation.hasReshape(state)) WarpDerivation.derive(geometry, state, faceK, warps, scratch)

        // ---------------------------------------------------------------- low-resolution analysis
        val smallW = even((w * profile.smoothScale).roundToInt().coerceAtLeast(32))
        val smallH = even((smallW.toLong() * h / w).toInt().coerceAtLeast(32))
        val smallFb = fbo(small, smallW, smallH).also { small = it }
        downsample(input.textureId, smallFb)

        val maskW = even(minOf(profile.maskWidth, w))
        val maskH = even((maskW.toLong() * h / w).toInt().coerceAtLeast(16))
        if (faceMask?.let { it.width != maskW || it.height != maskH } != false) faceMaskClear = false
        val faceFb = fbo(faceMask, maskW, maskH).also { faceMask = it }
        if (faceK > 0f) {
            renderFaceMasks(faceFb, aspect, maskW, maskH)
            faceMaskClear = false
        } else if (!faceMaskClear) {
            faceFb.bind()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            faceMaskClear = true
        }

        val skinFb = fbo(skin, smallW, smallH).also { skin = it }
        skinProgram().let { p ->
            skinFb.bind()
            p.use()
            p.bindTexture("uTexture", 0, smallFb.textureId)
            p.bindTexture("uFaceMask", 1, faceFb.textureId)
            p.setFloat("uFaceWeight", faceK)
            FullScreenQuad.draw(p)
        }

        var smoothTex = smallFb.textureId
        if (smoothK > 0f || retouchK > 0f) {
            val strength = max(smoothK, retouchK * 0.6f)
            smoothTex = bilateral(smallFb, smallW, smallH, w, h, profile.bilateralRadius, strength)
        }

        var largeTex = smallFb.textureId
        if (retouchK > 0f || blemishK > 0f || darkK > 0f) largeTex = largeBlur(smallFb, smallW, smallH)

        // ---------------------------------------------------------------- full-resolution composite
        val a = fbo(outA, w, h).also { outA = it }
        compositeProgram().let { p ->
            a.bind()
            p.use()
            p.bindTexture("uTexture", 0, input.textureId)
            p.bindTexture("uSmooth", 1, smoothTex)
            p.bindTexture("uLarge", 2, largeTex)
            p.bindTexture("uSkin", 3, skinFb.textureId)
            p.bindTexture("uFaceMask", 4, faceFb.textureId)
            p.setVec2("uTexel", 1f / w, 1f / h)
            p.setFloat("uSmoothK", smoothK)
            p.setFloat("uPoreKeep", 0.4f * (1f - smoothK))
            p.setFloat("uRetouchK", retouchK)
            p.setFloat("uBlemishK", blemishK)
            p.setFloat("uBrightK", brightK)
            p.setFloat("uWhitenK", whitenK)
            p.setFloat("uToneT", toneT)
            p.setFloat("uSharpenK", sharpenK)
            p.setFloat("uDarkCircleK", darkK)
            p.setFloat("uTeethK", teethK)
            p.setFloat("uFoundationK", foundationK)
            val fc = state.layer(MakeupFeature.FOUNDATION).color
            p.setVec3("uFoundationColor", red(fc), green(fc), blue(fc))
            FullScreenQuad.draw(p)
        }
        var current = a
        var spare = fbo(outB, w, h).also { outB = it }

        // ---------------------------------------------------------------- makeup
        if (needMakeupA || needMakeupB) {
            val ma = if (needMakeupA) renderMakeupA(state, aspect, maskW, maskH) else faceFb
            val mb = if (needMakeupB) renderMakeupB(state, aspect, maskW, maskH) else faceFb
            makeupProgram().let { p ->
                spare.bind()
                p.use()
                p.bindTexture("uTexture", 0, current.textureId)
                p.bindTexture("uMaskA", 1, ma.textureId)
                p.bindTexture("uMaskB", 2, mb.textureId)
                p.bindTexture("uSkin", 3, skinFb.textureId)
                setLayer(p, "uLipstick", state, MakeupFeature.LIPSTICK, faceK, needMakeupA)
                setLayer(p, "uLipColor", state, MakeupFeature.LIP_COLOR, faceK, needMakeupA)
                setLayer(p, "uBrow", state, MakeupFeature.EYEBROW, faceK, needMakeupA)
                setLayer(p, "uLiner", state, MakeupFeature.EYELINER, faceK, needMakeupA)
                setLayer(p, "uLash", state, MakeupFeature.EYELASHES, faceK, needMakeupA)
                setLayer(p, "uShadow", state, MakeupFeature.EYESHADOW, faceK, needMakeupB)
                setLayer(p, "uBlush", state, MakeupFeature.BLUSH, faceK, needMakeupB)
                setLayer(p, "uContour", state, MakeupFeature.CONTOUR, faceK, needMakeupB)
                setLayer(p, "uHighlight", state, MakeupFeature.HIGHLIGHT, faceK, needMakeupB)
                FullScreenQuad.draw(p)
            }
            val t = current; current = spare; spare = t
        }

        // ---------------------------------------------------------------- reshape
        if (warps.count > 0) {
            warpProgram().let { p ->
                spare.bind()
                p.use()
                p.bindTexture("uTexture", 0, current.textureId)
                p.setFloat("uAspect", aspect)
                p.setInt("uCount", warps.count)
                p.setVec4Array("uWarpA", warps.a, warps.count)
                p.setVec4Array("uWarpB", warps.b, warps.count)
                FullScreenQuad.draw(p)
            }
            val t = current; current = spare; spare = t
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GlFramebuffer.unbind()
        return GlTextureFrame(current.textureId, w, h, input.timestampNs, input.mirrored)
    }

    // ------------------------------------------------------------------------------------------------ tracking

    private fun updateTracking(input: GlTextureFrame, profile: QualityProfile, nowNs: Long) {
        val tr = tracker ?: return
        val gr = grabber ?: return

        // 1) Consume a finished detection.
        if (tr.pollNew(measured)) {
            faceCount = tr.resultFaceCount
            if (tr.resultHasLandmarks) {
                if (tr.resultTrackingId != lastTrackingId && lastTrackingId != -1 && tr.resultTrackingId != -1) stabilizer.reset()
                lastTrackingId = tr.resultTrackingId
                geometry.update(measured)
                stabilizer.onMeasurement(measured, tr.resultTimestampNs / 1e9, geometry.interocular)
                lastFaceSeenNs = max(lastFaceSeenNs, tr.resultTimestampNs)
            } else {
                // Explicit "no face" result: let the fader ramp out.
                lastFaceSeenNs = 0L
            }
        }

        // 2) Async readback started on an earlier frame → hand to the detector.
        if (gr.isAsync && gr.hasPending && tr.isIdle) {
            if (gr.collect()) tr.submit(gr.pixels, gr.width, gr.height, gr.pixelsTimestampNs)
        }

        // 3) Start a new readback when the detector is idle and the cadence allows.
        framesSinceDetect++
        if (tr.isIdle && !gr.hasPending && framesSinceDetect >= profile.detectEveryFrames) {
            framesSinceDetect = 0
            gr.configure(input.width, input.height, profile.detectWidth)
            val ready = gr.startReadback(input.textureId, downsampleProgram(), nowNs, input.width, input.height)
            if (ready) tr.submit(gr.pixels, gr.width, gr.height, gr.pixelsTimestampNs)
        }
    }

    // ------------------------------------------------------------------------------------------------ passes

    private fun downsample(texture: Int, target: GlFramebuffer) {
        val p = downsampleProgram()
        target.bind()
        p.use()
        p.bindTexture("uTexture", 0, texture)
        p.setFloat("uFlipY", 0f)
        p.setVec2("uOffset", 0.25f / target.width, 0.25f / target.height)
        FullScreenQuad.draw(p)
    }

    /** Separable bilateral at low resolution; returns the smoothed texture. */
    private fun bilateral(src: GlFramebuffer, sw: Int, sh: Int, fullW: Int, fullH: Int, radius: Int, strength: Float): Int {
        val tmp = fbo(smallTmp, sw, sh).also { smallTmp = it }
        val dst = fbo(smooth, sw, sh).also { smooth = it }
        val p = bilateralProgram(radius)
        // Spatial reach ≈ 0.8 % of the long side in full-resolution pixels, expressed in low-res texels per tap.
        val reachFull = 0.008f * max(fullW, fullH)
        val stepTexels = (reachFull * sw / fullW / radius).coerceIn(0.75f, 3f)
        val sigma = 0.055f + 0.065f * strength
        val rangeFactor = 1f / (2f * sigma * sigma)
        p.use()
        tmp.bind()
        p.bindTexture("uTexture", 0, src.textureId)
        p.setVec2("uStep", stepTexels / sw, 0f)
        p.setFloat("uRangeFactor", rangeFactor)
        FullScreenQuad.draw(p)
        dst.bind()
        p.bindTexture("uTexture", 0, tmp.textureId)
        p.setVec2("uStep", 0f, stepTexels / sh)
        FullScreenQuad.draw(p)
        return dst.textureId
    }

    /** Heavy low-frequency blur (1/4 of the smoothing resolution) for retouch, blemish and dark circles. */
    private fun largeBlur(src: GlFramebuffer, sw: Int, sh: Int): Int {
        val lw = even((sw / 4).coerceAtLeast(16))
        val lh = even((sh / 4).coerceAtLeast(16))
        val l = fbo(large, lw, lh).also { large = it }
        val t = fbo(largeTmp, lw, lh).also { largeTmp = it }
        downsample(src.textureId, l)
        val p = gaussianProgram()
        p.use()
        repeat(2) {
            t.bind()
            p.bindTexture("uTexture", 0, l.textureId)
            p.setVec2("uStep", 1f / lw, 0f)
            FullScreenQuad.draw(p)
            l.bind()
            p.bindTexture("uTexture", 0, t.textureId)
            p.setVec2("uStep", 0f, 1f / lh)
            FullScreenQuad.draw(p)
        }
        return l.textureId
    }

    private fun renderFaceMasks(target: GlFramebuffer, aspect: Float, mw: Int, mh: Int) {
        val g = geometry
        triOval.clear(); triExclude.clear(); triUnderEye.clear(); triMouth.clear()
        masks.faceOval(g, triOval)
        masks.exclusions(g, triExclude)
        masks.underEye(g, triUnderEye)
        masks.innerMouth(g, triMouth)
        beginMask(target, aspect)
        drawChannel(triOval, 0)
        drawChannel(triExclude, 1)
        drawChannel(triUnderEye, 2)
        drawChannel(triMouth, 3)
        endMask()
        featherMask(target, mw, mh, 0.04f * g.interocular * mh)
    }

    private fun renderMakeupA(state: BeautyState, aspect: Float, mw: Int, mh: Int): GlFramebuffer {
        val target = fbo(makeupA, mw, mh).also { makeupA = it }
        val g = geometry
        triLips.clear(); triBrows.clear(); triLiner.clear(); triLashes.clear()
        if (state.layer(MakeupFeature.LIPSTICK).intensity > 0 || state.layer(MakeupFeature.LIP_COLOR).intensity > 0) masks.lips(g, triLips)
        if (state.layer(MakeupFeature.EYEBROW).intensity > 0) masks.brows(g, triBrows)
        if (state.layer(MakeupFeature.EYELINER).intensity > 0) masks.eyeliner(g, triLiner)
        if (state.layer(MakeupFeature.EYELASHES).intensity > 0) masks.eyelashes(g, triLashes)
        beginMask(target, aspect)
        drawChannel(triLips, 0)
        drawChannel(triBrows, 1)
        drawChannel(triLiner, 2)
        drawChannel(triLashes, 3)
        endMask()
        featherMask(target, mw, mh, 0.018f * g.interocular * mh)
        return target
    }

    private fun renderMakeupB(state: BeautyState, aspect: Float, mw: Int, mh: Int): GlFramebuffer {
        val target = fbo(makeupB, mw, mh).also { makeupB = it }
        val g = geometry
        triShadow.clear(); triBlush.clear(); triContour.clear(); triHighlight.clear()
        if (state.layer(MakeupFeature.EYESHADOW).intensity > 0) masks.eyeshadow(g, triShadow)
        if (state.layer(MakeupFeature.BLUSH).intensity > 0) masks.blush(g, triBlush)
        if (state.layer(MakeupFeature.CONTOUR).intensity > 0) masks.contour(g, triContour)
        if (state.layer(MakeupFeature.HIGHLIGHT).intensity > 0) masks.highlight(g, triHighlight)
        beginMask(target, aspect)
        drawChannel(triShadow, 0)
        drawChannel(triBlush, 1)
        drawChannel(triContour, 2)
        drawChannel(triHighlight, 3)
        endMask()
        featherMask(target, mw, mh, 0.05f * g.interocular * mh)
        return target
    }

    private fun beginMask(target: GlFramebuffer, aspect: Float) {
        target.bind()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        val p = maskProgram()
        p.use()
        p.setFloat("uAspect", aspect)
    }

    private fun drawChannel(tris: TriangleBuffer, channel: Int) {
        if (tris.isEmpty()) return
        GLES20.glColorMask(channel == 0, channel == 1, channel == 2, channel == 3)
        val p = maskProgram()
        val loc = p.attribute("aVertex")
        var start = 0
        val total = tris.vertexCount
        // Upload in chunks that fit the direct buffer (no allocation).
        val chunk = (vertexBuffer.capacity() / 3) / 3 * 3
        while (start < total) {
            val count = minOf(chunk, total - start)
            vertexBuffer.clear()
            vertexBuffer.put(tris.data, start * 3, count * 3)
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
            GLES20.glDisableVertexAttribArray(loc)
            start += count
        }
    }

    private fun endMask() {
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Separable Gaussian feather of a mask; [radiusTexels] is the desired softness in mask texels. */
    private fun featherMask(target: GlFramebuffer, mw: Int, mh: Int, radiusTexels: Float) {
        val tmp = fbo(maskTmp, mw, mh).also { maskTmp = it }
        val step = (radiusTexels / 3.23f).coerceIn(0.5f, 6f)
        val p = gaussianProgram()
        p.use()
        tmp.bind()
        p.bindTexture("uTexture", 0, target.textureId)
        p.setVec2("uStep", step / mw, 0f)
        FullScreenQuad.draw(p)
        target.bind()
        p.bindTexture("uTexture", 0, tmp.textureId)
        p.setVec2("uStep", 0f, step / mh)
        FullScreenQuad.draw(p)
    }

    private fun setLayer(p: GlProgram, name: String, state: BeautyState, f: MakeupFeature, faceK: Float, groupActive: Boolean) {
        val layer = state.layer(f)
        val k = if (groupActive) layer.intensity / 100f * faceK else 0f
        p.setVec4(name, red(layer.color), green(layer.color), blue(layer.color), k)
    }

    // ------------------------------------------------------------------------------------------------ quality & status

    private fun syncControllerInputs(nowNs: Long) {
        if (controller.tier != controls.tier) controller.tier = controls.tier
        if (controller.thermal != controls.thermal) controller.thermal = controls.thermal
        if (controller.powerSave != controls.powerSave) controller.powerSave = controls.powerSave
        val rec = controls.recording
        if (rec != lastRecording) {
            lastRecording = rec
            controller.recording = rec
            // Starting a recording with a borderline cost: step down right away instead of risking dropped frames.
            if (rec && controller.averageRatio > 0.38f) controller.stepDown(nowNs / 1e9)
        }
    }

    private fun feedController(processNanos: Long, budgetNanos: Long, nowNs: Long) {
        if (controls.state.isNeutral || controls.bypassAll) return
        if (controller.onFrame(processNanos, budgetNanos, nowNs / 1e9)) {
            RgLog.i(TAG, "Quality → ${controller.level} (avg ${"%.2f".format(controller.averageRatio)} of budget)")
            publishStatus(force = true, nowNs = nowNs)
        }
    }

    private fun publishStatus(force: Boolean, nowNs: Long) {
        if (!force && nowNs - lastStatusNs < STATUS_INTERVAL_NS) return
        val state = controls.state
        val active = attached && !failed && !controls.bypassAll && !state.isNeutral
        val faceDetected = active && trackingActive && fader.value > 0.5f
        val level = controller.level
        val reason = when {
            failed -> BeautySuspendReason.GPU_ERROR
            !active -> null
            tracker?.unavailable == true && state.needsFaceTracking -> BeautySuspendReason.TRACKING_UNAVAILABLE
            level.ordinal > BeautyQuality.BALANCED.ordinal && droppedByQuality(state, level) ->
                if (controller.thermal.ordinal >= ThermalHint.HOT.ordinal) BeautySuspendReason.THERMAL else BeautySuspendReason.REDUCED_QUALITY
            state.needsFaceTracking && !faceDetected -> BeautySuspendReason.NO_FACE
            else -> null
        }
        val status = BeautyStatus(
            faceDetected = faceDetected,
            faceCount = if (active && trackingActive) faceCount else 0,
            quality = level,
            frameCostMs = if (active) (controller.averageCostMs * 10f).roundToInt() / 10f else 0f,
            suspendedReason = reason,
            tracking = active && trackingActive,
        )
        lastStatusNs = nowNs
        if (status != lastStatus) {
            lastStatus = status
            controls.onStatus(status)
        }
    }

    private fun droppedByQuality(state: BeautyState, level: BeautyQuality): Boolean {
        val p = QualityProfile.of(level)
        if (!p.retouchAndBlemish && (state.intensity(BeautyFeature.SKIN_RETOUCH) > 0 || state.intensity(BeautyFeature.BLEMISH_REMOVAL) > 0)) return true
        if (!p.smoothing && state.intensity(BeautyFeature.SMOOTH_SKIN) > 0) return true
        if (!p.sharpen && state.intensity(BeautyFeature.SHARPEN) > 0) return true
        if (!p.faceEffects && state.needsFaceTracking) return true
        return false
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun resetGlState() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        // Client-side vertex arrays (FullScreenQuad, mask geometry) need no VBO/VAO bound.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (glVersion >= 3) GLES30.glBindVertexArray(0)
    }

    private fun fbo(existing: GlFramebuffer?, w: Int, h: Int): GlFramebuffer {
        if (existing == null) return GlFramebuffer(w, h)
        existing.ensureSize(w, h)
        return existing
    }

    private fun downsampleProgram() = downsampleProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.DOWNSAMPLE).also { downsampleProgram = it }
    private fun gaussianProgram() = gaussianProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.GAUSSIAN).also { gaussianProgram = it }
    private fun skinProgram() = skinProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.SKIN_MASK).also { skinProgram = it }
    private fun compositeProgram() = compositeProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.COMPOSITE).also { compositeProgram = it }
    private fun makeupProgram() = makeupProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.MAKEUP).also { makeupProgram = it }
    private fun warpProgram() = warpProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.WARP).also { warpProgram = it }
    private fun maskProgram() = maskProgram ?: GlProgram(BeautyShaders.MASK_VERTEX, BeautyShaders.MASK_FRAGMENT).also { maskProgram = it }
    private fun bilateralProgram(radius: Int): GlProgram {
        val r = radius.coerceIn(1, MAX_BILATERAL_RADIUS)
        return bilateralPrograms[r] ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.bilateral(r)).also { bilateralPrograms[r] = it }
    }

    private fun even(v: Int) = (v + 1) and 0x7FFFFFFE

    private fun red(c: Long) = ((c shr 16) and 0xFF) / 255f
    private fun green(c: Long) = ((c shr 8) and 0xFF) / 255f
    private fun blue(c: Long) = (c and 0xFF) / 255f

    companion object {
        private const val TAG = "Beauty"
        private const val MAX_BILATERAL_RADIUS = 6
        private const val DEFAULT_BUDGET_NS = 33_333_333L
        private const val STATUS_INTERVAL_NS = 250_000_000L
        /** A face not re-detected for this long (frame time) is considered lost. */
        private const val FACE_STALE_NS = 600_000_000L
    }
}
