package com.ravango.engine.beauty

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.engine.beauty.gl.BeautyShaders
import com.ravango.engine.beauty.gl.DepthBufferGl
import com.ravango.engine.beauty.gl.FaceMeshGl
import com.ravango.engine.beauty.gl.MakeupTexturesGl
import com.ravango.engine.beauty.mesh.FaceAssets
import com.ravango.engine.beauty.mesh.FaceLandmarkIndex
import com.ravango.engine.beauty.mesh.FaceTopology
import com.ravango.engine.beauty.mesh.MeshWarp
import com.ravango.engine.beauty.mesh.PoseFit
import com.ravango.engine.beauty.mesh.ReshapeField
import com.ravango.engine.beauty.mesh.ReshapeParams
import com.ravango.engine.beauty.quality.QualityController
import com.ravango.engine.beauty.quality.QualityProfile
import com.ravango.engine.beauty.quality.ThermalHint
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.beauty.tracking.Blendshape
import com.ravango.engine.beauty.tracking.DetectionFrame
import com.ravango.engine.beauty.tracking.FaceLandmarkerTracker
import com.ravango.engine.beauty.tracking.FaceTracks
import com.ravango.engine.beauty.tracking.FrameGrabber
import com.ravango.engine.beauty.tracking.TrackedFace
import com.ravango.engine.render.FullScreenQuad
import com.ravango.engine.render.GlFramebuffer
import com.ravango.engine.render.GlFrameProcessor
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlProgram
import com.ravango.engine.render.GlTextureFrame
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cross-thread inputs of the processor. Written from any thread, read on the GL thread each frame. */
internal class BeautyControls(tier: TierHint) {
    @Volatile var state: BeautyState = BeautyState()
    @Volatile var eyeColor: EyeColorSetting = EyeColorSetting()
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
 * input ─► ½-res low-pass ─► skin likelihood ◄─ region masks (tracked mesh × UV regions: skin, under-eye,
 *   │           │                                mouth opening, eye openings)
 *   │           ├► bilateral (edge-preserving) ─┐
 *   │           └► large blur ──────────────────┤
 *   └──────────────────────────────────────────┴► Face Retouch composite (frequency-separated soft skin, tone,
 *                                                  dark circles, teeth, sclera, iris colour, sharpening)
 *        ─► Face Mask makeup (mesh sampling UV makeup textures, per-layer blend modes, depth-tested)
 *        ─► Face Liquify (mesh + padded ring, displaced positions, original texcoords) ─► output
 *   └► small y-flipped copy ─► readback (PBO) ─► MediaPipe Face Landmarker (LIVE_STREAM, 2 faces)
 *        ─► per-face One Euro stabilization + prediction + presence fade
 * ```
 * Every pass is skipped when its features are neutral; the processor returns the input untouched when the look is
 * neutral. GL objects are created lazily at the sizes needed and released in [onDetach]. Per-frame work does not
 * allocate. Any GL failure bypasses beauty (reported as [BeautySuspendReason.GPU_ERROR]); a face-mesh-only failure
 * keeps skin effects running.
 */
internal class BeautyProcessor(
    private val controls: BeautyControls,
    private val context: Context,
) : GlFrameProcessor {

    private var glVersion = 2
    private var attached = false
    private var failed = false
    private var meshFailed = false

    // Programs (lazy).
    private var downsampleProgram: GlProgram? = null
    private var copyProgram: GlProgram? = null
    private val bilateralPrograms = arrayOfNulls<GlProgram>(MAX_BILATERAL_RADIUS + 1)
    private var gaussianProgram: GlProgram? = null
    private var skinProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null
    private var maskRegionsProgram: GlProgram? = null
    private var maskSolidProgram: GlProgram? = null
    private var makeupProgram: GlProgram? = null
    private var warpProgram: GlProgram? = null

    // Framebuffers (lazy).
    private var small: GlFramebuffer? = null
    private var smallTmp: GlFramebuffer? = null
    private var smooth: GlFramebuffer? = null
    private var large: GlFramebuffer? = null
    private var largeTmp: GlFramebuffer? = null
    private var skin: GlFramebuffer? = null
    private var faceMask: GlFramebuffer? = null
    private var maskTmp: GlFramebuffer? = null
    private var outA: GlFramebuffer? = null
    private var outB: GlFramebuffer? = null
    private var faceMaskClear = false
    private val depth = DepthBufferGl()

    // Tracking.
    private var tracker: FaceLandmarkerTracker? = null
    private var grabber: FrameGrabber? = null
    private val detection = DetectionFrame()
    private val tracks = FaceTracks()
    private var framesSinceDetect = 0
    private var trackingActive = false

    // Face mesh.
    private var topology: FaceTopology? = null
    private var meshGl: FaceMeshGl? = null
    private val textures = MakeupTexturesGl()
    private var poses: Array<PoseFit> = emptyArray()
    private var reshapeField: ReshapeField? = null
    private var meshWarp: MeshWarp? = null
    private var meshPositions: Array<FloatArray> = emptyArray()
    private var warpPositions = FloatArray(0)
    private val faceVisible = BooleanArray(DetectionFrame.MAX_FACES)
    private val frontCcw = BooleanArray(DetectionFrame.MAX_FACES)
    private val faceScale = FloatArray(DetectionFrame.MAX_FACES)
    private val faceTurn = FloatArray(DetectionFrame.MAX_FACES)
    private val reshapeParams = ReshapeParams()
    private val cachedParams = ReshapeParams()
    private var canonicalDisp = FloatArray(0)
    private var reshapeValid = false
    private var reshapeAny = false
    private val iris = FloatArray(16)
    private var irisCount = 0

    // Values derived from the current BeautyState, recomputed only when the state object changes.
    private var cachedState: BeautyState? = null
    private var cachedNeutral = true
    private var cachedHasMakeup = false
    private val layerRgbk = FloatArray(MakeupFeature.entries.size * 4)

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
        meshFailed = false
        FaceAssets.prepare(this.context)
        tracker = try {
            FaceLandmarkerTracker(this.context).also { it.start() }
        } catch (t: Throwable) {
            RgLog.e(TAG, "Face landmarker unavailable", t)
            null
        }
        grabber = FrameGrabber(glVersion)
        tracks.reset()
    }

    override fun onDetach() {
        attached = false
        tracker?.close()
        tracker = null
        grabber?.release()
        grabber = null
        releasePrograms()
        listOf(small, smallTmp, smooth, large, largeTmp, skin, faceMask, maskTmp, outA, outB).forEach { it?.release() }
        small = null; smallTmp = null; smooth = null; large = null; largeTmp = null; skin = null
        faceMask = null; maskTmp = null; outA = null; outB = null
        depth.release()
        meshGl?.release()
        meshGl = null
        textures.release()
        topology = null
        trackingActive = false
        tracks.reset()
        publishStatus(force = true, nowNs = System.nanoTime())
    }

    private fun releasePrograms() {
        listOf(downsampleProgram, copyProgram, gaussianProgram, skinProgram, compositeProgram, maskRegionsProgram,
            maskSolidProgram, makeupProgram, warpProgram).forEach { it?.release() }
        bilateralPrograms.forEach { it?.release() }
        bilateralPrograms.fill(null)
        downsampleProgram = null; copyProgram = null; gaussianProgram = null; skinProgram = null
        compositeProgram = null; maskRegionsProgram = null; maskSolidProgram = null; makeupProgram = null
        warpProgram = null
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
        val eyeColor = controls.eyeColor
        syncControllerInputs(start)
        syncState(state)
        val neutral = !state.enabled || (cachedNeutral && !eyeColor.active)
        if (!attached || failed || controls.bypassAll || neutral || input.width <= 0 || input.height <= 0) {
            if (trackingActive) { trackingActive = false; tracks.reset() }
            publishStatus(force = false, nowNs = start)
            return input
        }
        return try {
            val out = render(input, state, eyeColor)
            val end = System.nanoTime()
            // Without timing feedback from the renderer, measure our own submission cost against a 30 fps budget.
            if (end - lastExternalTimingNs > 2_000_000_000L) feedController(end - start, DEFAULT_BUDGET_NS, end)
            publishStatus(force = false, nowNs = end)
            out
        } catch (t: Throwable) {
            // Never break the camera: bypass for the rest of this attachment and report it.
            RgLog.e(TAG, "Beauty pipeline failed; bypassing", t)
            failed = true
            restoreGlState()
            publishStatus(force = true, nowNs = System.nanoTime())
            input
        }
    }

    private fun render(input: GlTextureFrame, state: BeautyState, eyeColor: EyeColorSetting): GlTextureFrame {
        val w = input.width
        val h = input.height
        val aspect = w.toFloat() / h
        val nowNs = if (input.timestampNs > 0) input.timestampNs else System.nanoTime()
        val dtSec = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs
        val profile = QualityProfile.of(controller.level)

        restoreGlState()
        val meshReady = ensureMeshResources()

        // ---------------------------------------------------------------- tracking
        val tr = tracker
        trackingActive = profile.faceEffects && tr != null && !tr.isFailed && !meshFailed
        if (trackingActive) updateTracking(input, profile, nowNs, aspect)
        tracks.update(nowNs / 1e9, dtSec)
        var faceCount = 0
        var maxPresence = 0f
        for (s in 0 until DetectionFrame.MAX_FACES) {
            val f = tracks.faces[s]
            val visible = meshReady && profile.faceEffects && f.active && f.presence > 0f && preparePose(s, f)
            faceVisible[s] = visible
            if (visible) {
                faceCount++
                maxPresence = max(maxPresence, f.presence)
            }
        }
        val anyFace = faceCount > 0

        // ---------------------------------------------------------------- intensities
        fun uni(f: BeautyFeature) = state.intensity(f) / 100f
        val smoothK = if (profile.smoothing) uni(BeautyFeature.SMOOTH_SKIN) else 0f
        val retouchK = if (profile.retouchAndBlemish) uni(BeautyFeature.SKIN_RETOUCH) else 0f
        val blemishK = if (profile.retouchAndBlemish) uni(BeautyFeature.BLEMISH_REMOVAL) else 0f
        val brightK = uni(BeautyFeature.SKIN_BRIGHTNESS)
        val whitenK = uni(BeautyFeature.WHITENING)
        val toneT = (state.intensity(BeautyFeature.SKIN_TONE) - 50) / 50f
        val sharpenK = if (profile.sharpen) uni(BeautyFeature.SHARPEN) * 0.9f else 0f
        val darkK = if (anyFace) uni(BeautyFeature.DARK_CIRCLES) else 0f
        val teethK = if (anyFace) uni(BeautyFeature.TEETH_WHITENING) else 0f
        // Eye whitening rides on WHITENING and eye sharpening on SHARPEN (documented on BeautyEngine).
        val eyeFx = anyFace && profile.eyeEffects
        val eyeWhitenK = if (eyeFx) whitenK * 0.85f else 0f
        val eyeSharpenK = if (eyeFx) uni(BeautyFeature.SHARPEN) * 0.9f else 0f
        val eyeColorK = if (eyeFx && eyeColor.active) eyeColor.intensity.coerceIn(0, 100) / 100f else 0f
        val needMakeup = anyFace && textures.ready && cachedHasMakeup
        val needWarp = anyFace && !reshapeParams.isNeutral && updateReshapeField()
        if (eyeWhitenK > 0f || eyeColorK > 0f) collectIrises() else irisCount = 0

        // ---------------------------------------------------------------- low-resolution analysis
        val smallW = even((w * profile.smoothScale).roundToInt().coerceAtLeast(32))
        val smallH = even((smallW.toLong() * h / w).toInt().coerceAtLeast(32))
        val smallFb = fbo(small, smallW, smallH).also { small = it }
        downsample(input.textureId, smallFb)

        val maskW = even(minOf(profile.maskWidth, w))
        val maskH = even((maskW.toLong() * h / w).toInt().coerceAtLeast(16))
        if (faceMask?.let { it.width != maskW || it.height != maskH } != false) faceMaskClear = false
        val faceFb = fbo(faceMask, maskW, maskH).also { faceMask = it }
        if (anyFace) {
            renderRegionMasks(faceFb, aspect)
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
            p.setFloat("uOutsideK", 1f - 0.35f * maxPresence)
            FullScreenQuad.draw(p)
        }

        var smoothTex = smallFb.textureId
        if (smoothK > 0f || retouchK > 0f) {
            val strength = max(smoothK, retouchK * 0.6f)
            smoothTex = bilateral(smallFb, smallW, smallH, w, h, profile.bilateralRadius, strength)
        }
        var largeTex = smallFb.textureId
        if (retouchK > 0f || blemishK > 0f || darkK > 0f) largeTex = largeBlur(smallFb, smallW, smallH)

        // ---------------------------------------------------------------- Face Retouch (full resolution)
        val a = fbo(outA, w, h).also { outA = it }
        compositeProgram().let { p ->
            a.bind()
            p.use()
            p.bindTexture("uTexture", 0, input.textureId)
            p.bindTexture("uLow", 1, smallFb.textureId)
            p.bindTexture("uSmooth", 2, smoothTex)
            p.bindTexture("uLarge", 3, largeTex)
            p.bindTexture("uSkin", 4, skinFb.textureId)
            p.bindTexture("uFaceMask", 5, faceFb.textureId)
            p.setVec2("uTexel", 1f / w, 1f / h)
            p.setFloat("uAspect", aspect)
            p.setFloat("uSmoothK", smoothK)
            // Frequency separation: keep most fine texture (pores) at low strengths, less at high ones.
            p.setFloat("uDetailKeep", 0.85f - 0.4f * smoothK)
            p.setFloat("uRetouchK", retouchK)
            p.setFloat("uBlemishK", blemishK)
            p.setFloat("uBrightK", brightK)
            p.setFloat("uWhitenK", whitenK)
            p.setFloat("uToneT", toneT)
            p.setFloat("uSharpenK", sharpenK)
            p.setFloat("uDarkCircleK", darkK)
            p.setFloat("uTeethK", teethK)
            p.setFloat("uEyeWhitenK", eyeWhitenK)
            p.setFloat("uEyeSharpenK", eyeSharpenK)
            p.setVec4("uEyeColor", red(eyeColor.color), green(eyeColor.color), blue(eyeColor.color), eyeColorK)
            p.setFloat("uUseFaceMask", if (anyFace) 1f else 0f)
            p.setInt("uIrisCount", irisCount)
            if (irisCount > 0) p.setVec4Array("uIris", iris, irisCount)
            FullScreenQuad.draw(p)
        }
        var current = a
        var spare = fbo(outB, w, h).also { outB = it }

        // ---------------------------------------------------------------- Face Mask makeup
        if (needMakeup) {
            renderMakeup(current, spare, smallFb, skinFb, aspect, profile)
            val t = current; current = spare; spare = t
        }

        // ---------------------------------------------------------------- Face Liquify
        if (needWarp) {
            renderWarp(current, spare, aspect, profile)
            val t = current; current = spare; spare = t
        }

        restoreGlState()
        return GlTextureFrame(current.textureId, w, h, input.timestampNs, input.mirrored)
    }

    // ------------------------------------------------------------------------------------------------ tracking

    private fun updateTracking(input: GlTextureFrame, profile: QualityProfile, nowNs: Long, aspect: Float) {
        val tr = tracker ?: return
        val gr = grabber ?: return

        // 1) Consume a finished detection.
        if (tr.pollNew(detection)) tracks.onDetection(detection, detection.timestampNs / 1e9)

        // 2) Async readback started on an earlier frame → hand it to the landmarker.
        if (gr.isAsync && gr.hasPending) {
            if (tr.canAccept()) {
                val buf = tr.acquireInput(gr.width, gr.height)
                if (gr.collect(buf)) tr.commit(gr.width, gr.height, aspect, gr.pendingTimestampNs)
            } else if (nowNs - gr.pendingTimestampNs > STALE_READBACK_NS) {
                gr.discardPending()
            }
        }

        // 3) Start a new readback when the landmarker can take it and the cadence allows.
        framesSinceDetect++
        if (!gr.hasPending && framesSinceDetect >= profile.detectEveryFrames && tr.isReady) {
            gr.configure(input.width, input.height, profile.detectWidth)
            if (gr.isAsync) {
                framesSinceDetect = 0
                gr.startAsync(input.textureId, downsampleProgram(), nowNs)
            } else if (tr.canAccept()) {
                framesSinceDetect = 0
                val buf = tr.acquireInput(gr.width, gr.height)
                if (gr.readSync(input.textureId, downsampleProgram(), buf)) tr.commit(gr.width, gr.height, aspect, nowNs)
            }
        }
    }

    /** One-time mesh GL setup once the canonical mesh and makeup atlas are loaded. */
    private fun ensureMeshResources(): Boolean {
        if (meshFailed) return false
        if (meshGl != null) return true
        val loaded = FaceAssets.loaded ?: return false
        return try {
            val topo = loaded.topology
            val gl = FaceMeshGl(topo, DetectionFrame.MAX_FACES)
            gl.create()
            textures.upload(loaded.atlas)
            // Build the mesh programs now so a driver problem is found once, not mid-recording.
            maskRegionsProgram(); maskSolidProgram(); makeupProgram(); warpProgram(); copyProgram()
            topology = topo
            poses = Array(DetectionFrame.MAX_FACES) { PoseFit(loaded.model) }
            reshapeField = ReshapeField(loaded.model)
            meshWarp = MeshWarp(topo)
            meshPositions = Array(DetectionFrame.MAX_FACES) { FloatArray(topo.totalVertexCount * 3) }
            warpPositions = FloatArray(topo.totalVertexCount * 3)
            canonicalDisp = FloatArray(topo.meshVertexCount * 2)
            reshapeValid = false
            meshGl = gl
            true
        } catch (t: Throwable) {
            RgLog.e(TAG, "Face mesh GPU setup failed; face effects disabled", t)
            meshFailed = true
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            false
        }
    }

    /**
     * Builds the frame-space mesh (+ padded ring) of face [slot] from its stabilized landmarks, fits its pose,
     * uploads it and derives its front-face winding. Returns false for a degenerate pose.
     */
    private fun preparePose(slot: Int, face: TrackedFace): Boolean {
        val topo = topology ?: return false
        val fit = poses.getOrNull(slot) ?: return false
        val gl = meshGl ?: return false
        val lm = face.landmarks
        if (!fit.fit(lm)) return false
        faceScale[slot] = fit.scale
        faceTurn[slot] = fit.turn
        // GL winding: canonical (u, v) winding × orientation of the image map (y down, maybe mirrored) × NDC y flip.
        val det = fit.affine[0] * fit.affine[5] - fit.affine[1] * fit.affine[4]
        frontCcw[slot] = (topo.uvWinding > 0) == (det < 0f)

        val pos = meshPositions[slot]
        System.arraycopy(lm, 0, pos, 0, topo.meshVertexCount * 3)
        val oval = FaceLandmarkIndex.FACE_OVAL
        var cx = 0f; var cy = 0f
        for (i in oval) { cx += lm[i * 3]; cy += lm[i * 3 + 1] }
        cx /= oval.size; cy /= oval.size
        val ringDepth = fit.scale * RING_DEPTH_CM
        for (k in oval.indices) {
            val src = oval[k] * 3
            val dx = lm[src] - cx; val dy = lm[src + 1] - cy
            val inner = (topo.innerRingStart + k) * 3
            val outer = (topo.outerRingStart + k) * 3
            pos[inner] = cx + dx * FaceTopology.INNER_RING_SCALE
            pos[inner + 1] = cy + dy * FaceTopology.INNER_RING_SCALE
            pos[inner + 2] = lm[src + 2] + ringDepth
            pos[outer] = cx + dx * FaceTopology.OUTER_RING_SCALE
            pos[outer + 1] = cy + dy * FaceTopology.OUTER_RING_SCALE
            pos[outer + 2] = lm[src + 2] + ringDepth * 2f
        }
        gl.uploadMesh(slot, pos)
        return true
    }

    /** Recomputes the canonical displacement field when the reshape values changed. */
    private fun updateReshapeField(): Boolean {
        val field = reshapeField ?: return false
        if (!reshapeValid || !reshapeParams.sameAs(cachedParams)) {
            reshapeAny = field.compute(reshapeParams, canonicalDisp)
            cachedParams.copyFrom(reshapeParams)
            reshapeValid = true
        }
        return reshapeAny
    }

    /** Iris circles (frame space) of visible faces, weighted by presence and eye openness. */
    private fun collectIrises() {
        irisCount = 0
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            val f = tracks.faces[s]
            val lm = f.landmarks
            addIris(lm, FaceLandmarkIndex.RIGHT_IRIS_CENTER, FaceLandmarkIndex.RIGHT_IRIS_RING, f.presence * (1f - f.blendshapes[Blendshape.EYE_BLINK_RIGHT]))
            addIris(lm, FaceLandmarkIndex.LEFT_IRIS_CENTER, FaceLandmarkIndex.LEFT_IRIS_RING, f.presence * (1f - f.blendshapes[Blendshape.EYE_BLINK_LEFT]))
        }
    }

    private fun addIris(lm: FloatArray, center: Int, ring: IntArray, weight: Float) {
        if (irisCount >= 4 || weight <= 0.02f) return
        val cx = lm[center * 3]; val cy = lm[center * 3 + 1]
        var r = 0f
        for (i in ring) {
            val dx = lm[i * 3] - cx; val dy = lm[i * 3 + 1] - cy
            r += sqrt(dx * dx + dy * dy)
        }
        r /= ring.size
        val o = irisCount * 4
        iris[o] = cx; iris[o + 1] = cy; iris[o + 2] = r * 1.05f; iris[o + 3] = weight.coerceIn(0f, 1f)
        irisCount++
    }

    // ------------------------------------------------------------------------------------------------ mesh passes

    private fun setMeshCommon(p: GlProgram, aspect: Float) {
        p.use()
        p.setFloat("uAspect", aspect)
        p.setFloat("uDepthScale", DEPTH_SCALE)
    }

    private fun cullFor(slot: Int) {
        GLES20.glFrontFace(if (frontCcw[slot]) GLES20.GL_CCW else GLES20.GL_CW)
    }

    /** Region masks: R ½·(surface + skin), G under-eye, B mouth opening, A eye openings; then a light feather. */
    private fun renderRegionMasks(target: GlFramebuffer, aspect: Float) {
        val gl = meshGl ?: return
        target.bind()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        val regions = maskRegionsProgram()
        val solid = maskSolidProgram()
        var scale = 0f
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            val weight = tracks.faces[s].presence
            scale = max(scale, faceScale[s])
            cullFor(s)
            setMeshCommon(regions, aspect)
            regions.bindTexture("uRegions", 0, textures.ids[2])
            regions.setFloat("uWeight", weight)
            GLES20.glColorMask(true, true, false, false)
            gl.drawMesh(regions, s, FaceMeshGl.SURFACE)
            setMeshCommon(solid, aspect)
            solid.setFloat("uWeight", weight)
            GLES20.glColorMask(true, false, true, false)
            solid.setVec4("uValue", 0.5f, 0f, 1f, 0f)
            gl.drawMesh(solid, s, FaceMeshGl.MOUTH)
            GLES20.glColorMask(true, false, false, true)
            solid.setVec4("uValue", 0.5f, 0f, 0f, 1f)
            gl.drawMesh(solid, s, FaceMeshGl.EYES)
        }
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // ~0.25 cm of feather at the mask resolution (scale is frame units per cm, frame height = 1).
        featherMask(target, 0.25f * scale * target.height)
    }

    private fun renderMakeup(
        source: GlFramebuffer, target: GlFramebuffer, low: GlFramebuffer, skinFb: GlFramebuffer,
        aspect: Float, profile: QualityProfile,
    ) {
        val gl = meshGl ?: return
        copy(source, target)
        val useDepth = beginMeshDepth(target, profile)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        val p = makeupProgram()
        setMeshCommon(p, aspect)
        p.bindTexture("uFrame", 0, source.textureId)
        p.bindTexture("uLow", 1, low.textureId)
        p.bindTexture("uSkin", 2, skinFb.textureId)
        p.bindTexture("uMakeupA", 3, textures.ids[0])
        p.bindTexture("uMakeupB", 4, textures.ids[1])
        p.bindTexture("uMakeupC", 5, textures.ids[2])
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            val k = tracks.faces[s].presence
            setLayer(p, "uFoundation", MakeupFeature.FOUNDATION, k)
            setLayer(p, "uContour", MakeupFeature.CONTOUR, k)
            setLayer(p, "uBlush", MakeupFeature.BLUSH, k)
            setLayer(p, "uHighlight", MakeupFeature.HIGHLIGHT, k)
            setLayer(p, "uShadow", MakeupFeature.EYESHADOW, k)
            setLayer(p, "uBrow", MakeupFeature.EYEBROW, k)
            setLayer(p, "uLipColor", MakeupFeature.LIP_COLOR, k)
            setLayer(p, "uLipstick", MakeupFeature.LIPSTICK, k)
            setLayer(p, "uLiner", MakeupFeature.EYELINER, k)
            setLayer(p, "uLash", MakeupFeature.EYELASHES, k)
            cullFor(s)
            gl.drawMesh(p, s, FaceMeshGl.SURFACE)
        }
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (useDepth) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun renderWarp(source: GlFramebuffer, target: GlFramebuffer, aspect: Float, profile: QualityProfile) {
        val gl = meshGl ?: return
        val warp = meshWarp ?: return
        copy(source, target)
        val useDepth = beginMeshDepth(target, profile)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        val p = warpProgram()
        setMeshCommon(p, aspect)
        p.bindTexture("uTexture", 0, source.textureId)
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            warp.apply(meshPositions[s], canonicalDisp, poses[s], tracks.faces[s].presence, warpPositions)
            gl.uploadWarp(s, warpPositions)
            cullFor(s)
            gl.drawWarp(p, s)
        }
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (useDepth) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun beginMeshDepth(target: GlFramebuffer, profile: QualityProfile): Boolean {
        if (!profile.depthTest || !depth.attach(target.framebufferId, target.width, target.height)) {
            target.bind()
            return false
        }
        target.bind()
        GLES20.glDepthMask(true)
        GLES20.glClearDepthf(1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        return true
    }

    private fun copy(source: GlFramebuffer, target: GlFramebuffer) {
        val p = copyProgram()
        target.bind()
        p.use()
        p.bindTexture("uTexture", 0, source.textureId)
        FullScreenQuad.draw(p)
    }

    private fun setLayer(p: GlProgram, name: String, f: MakeupFeature, presence: Float) {
        val o = f.ordinal * 4
        p.setVec4(name, layerRgbk[o], layerRgbk[o + 1], layerRgbk[o + 2], layerRgbk[o + 3] * presence)
    }

    /** Re-derives cached per-state values when the (immutable) state object changes. */
    private fun syncState(state: BeautyState) {
        if (state === cachedState) return
        cachedState = state
        cachedNeutral = state.isNeutral
        var any = false
        for (f in MakeupFeature.entries) {
            val layer = state.layer(f)
            val o = f.ordinal * 4
            layerRgbk[o] = red(layer.color)
            layerRgbk[o + 1] = green(layer.color)
            layerRgbk[o + 2] = blue(layer.color)
            layerRgbk[o + 3] = layer.intensity.coerceIn(0, 100) / 100f
            if (layer.intensity > 0) any = true
        }
        cachedHasMakeup = any
        reshapeParams.set(state)
    }

    // ------------------------------------------------------------------------------------------------ 2D passes

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
        for (pass in 0 until 2) {
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

    /** Separable Gaussian feather of a mask; [radiusTexels] is the desired softness in mask texels. */
    private fun featherMask(target: GlFramebuffer, radiusTexels: Float) {
        val tmp = fbo(maskTmp, target.width, target.height).also { maskTmp = it }
        val step = (radiusTexels / 3.23f).coerceIn(0.5f, 4f)
        val p = gaussianProgram()
        p.use()
        tmp.bind()
        p.bindTexture("uTexture", 0, target.textureId)
        p.setVec2("uStep", step / target.width, 0f)
        FullScreenQuad.draw(p)
        target.bind()
        p.bindTexture("uTexture", 0, tmp.textureId)
        p.setVec2("uStep", 0f, step / target.height)
        FullScreenQuad.draw(p)
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
        val state = controls.state
        syncState(state)
        if ((cachedNeutral && !controls.eyeColor.active) || !state.enabled || controls.bypassAll) return
        if (controller.onFrame(processNanos, budgetNanos, nowNs / 1e9)) {
            RgLog.i(TAG, "Quality → ${controller.level} (avg ${"%.2f".format(controller.averageRatio)} of budget)")
            publishStatus(force = true, nowNs = nowNs)
        }
    }

    private fun publishStatus(force: Boolean, nowNs: Long) {
        if (!force && nowNs - lastStatusNs < STATUS_INTERVAL_NS) return
        val state = controls.state
        val eyeColorOn = controls.eyeColor.active
        val active = attached && !failed && !controls.bypassAll && state.enabled && (!state.isNeutral || eyeColorOn)
        val faces = if (active && trackingActive) tracks.visibleCount else 0
        val needsFace = state.needsFaceTracking || (state.enabled && eyeColorOn)
        val level = controller.level
        val tr = tracker
        val reason = when {
            failed -> BeautySuspendReason.GPU_ERROR
            !active -> null
            meshFailed && needsFace -> BeautySuspendReason.GPU_ERROR
            needsFace && (tr == null || tr.isFailed || FaceAssets.failed) -> BeautySuspendReason.TRACKING_UNAVAILABLE
            level.ordinal > BeautyQuality.BALANCED.ordinal && droppedByQuality(state, level) ->
                if (controller.thermal.ordinal >= ThermalHint.HOT.ordinal) BeautySuspendReason.THERMAL else BeautySuspendReason.REDUCED_QUALITY
            needsFace && faces == 0 -> BeautySuspendReason.NO_FACE
            else -> null
        }
        val status = BeautyStatus(
            faceDetected = faces > 0,
            faceCount = faces,
            quality = level,
            frameCostMs = if (active) (controller.averageCostMs * 10f).roundToInt() / 10f else 0f,
            suspendedReason = reason,
            tracking = active && trackingActive,
            trackingDelegate = when {
                !(active && trackingActive) || tr == null || !tr.isReady -> null
                tr.usingGpu -> BeautyTrackingDelegate.GPU
                else -> BeautyTrackingDelegate.CPU
            },
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

    /** Leaves the GL state the camera renderer expects (and our 2D passes assume). */
    private fun restoreGlState() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glFrontFace(GLES20.GL_CCW)
        // Client-side vertex arrays (FullScreenQuad) need no VBO/VAO bound.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        if (glVersion >= 3) GLES30.glBindVertexArray(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GlFramebuffer.unbind()
    }

    private fun fbo(existing: GlFramebuffer?, w: Int, h: Int): GlFramebuffer {
        if (existing == null) return GlFramebuffer(w, h)
        existing.ensureSize(w, h)
        return existing
    }

    private fun downsampleProgram() = downsampleProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.DOWNSAMPLE).also { downsampleProgram = it }
    private fun copyProgram() = copyProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.COPY).also { copyProgram = it }
    private fun gaussianProgram() = gaussianProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.GAUSSIAN).also { gaussianProgram = it }
    private fun skinProgram() = skinProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.SKIN_MASK).also { skinProgram = it }
    private fun compositeProgram() = compositeProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.COMPOSITE).also { compositeProgram = it }
    private fun maskRegionsProgram() = maskRegionsProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_REGIONS).also { maskRegionsProgram = it }
    private fun maskSolidProgram() = maskSolidProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_SOLID).also { maskSolidProgram = it }
    private fun makeupProgram() = makeupProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MAKEUP).also { makeupProgram = it }
    private fun warpProgram() = warpProgram ?: GlProgram(BeautyShaders.WARP_VERTEX, BeautyShaders.WARP_FRAGMENT).also { warpProgram = it }
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
        /** An async readback the landmarker could not take within this time is dropped. */
        private const val STALE_READBACK_NS = 200_000_000L
        /** Landmark z (frame units) → NDC depth. */
        private const val DEPTH_SCALE = 1.5f
        /** Ring vertices sit this far (cm, scaled by the face) behind the oval so the face wins depth ties. */
        private const val RING_DEPTH_CM = 1.0f
    }
}
