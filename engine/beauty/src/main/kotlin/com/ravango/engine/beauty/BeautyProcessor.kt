package com.ravango.engine.beauty

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import com.ravango.core.common.diagnostics.Diagnostics
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.BeautyFeature
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.BitmapTextureGl
import com.ravango.engine.beauty.effects.EffectsAssets
import com.ravango.engine.beauty.effects.EffectsShaders
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.FacePaint
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LensScene
import com.ravango.engine.beauty.effects.LensWarpField
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.effects.LutTexturesGl
import com.ravango.engine.beauty.effects.MaskTextureGl
import com.ravango.engine.beauty.effects.SpriteAtlas
import com.ravango.engine.beauty.effects.SpriteDrawer
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
import com.ravango.engine.beauty.tracking.SelfieSegmenterTracker
import com.ravango.engine.beauty.tracking.TrackedFace
import com.ravango.engine.render.FullScreenQuad
import com.ravango.engine.render.GlDiagnostics
import com.ravango.engine.render.GlFramebuffer
import com.ravango.engine.render.GlFrameProcessor
import com.ravango.engine.render.GlProcessingContext
import com.ravango.engine.render.GlProgram
import com.ravango.engine.render.GlProgramException
import com.ravango.engine.render.GlTextureFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
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

    // ADDED — camera effects.
    @Volatile var effects: EffectsState = EffectsState()
    @Volatile var filterSwipe: FilterSwipe? = null
    @Volatile var backgroundBitmap: Bitmap? = null
    @Volatile var onEffectsStatus: (EffectsStatus) -> Unit = {}
}

/**
 * The beauty + effects GPU pipeline (runs entirely on the camera's GL thread):
 *
 * ```
 * input ─► ½-res low-pass ─► skin likelihood ◄─ region masks (tracked mesh × UV regions: skin, under-eye,
 *   │           │                                mouth opening, eye openings)
 *   │           ├► bilateral (edge-preserving) ─┐
 *   │           └► large blur ──────────────────┤
 *   └──────────────────────────────────────────┴► Face Retouch composite (+ live filter when it is the last pass)
 *        ─► Face Mask makeup (mesh sampling UV makeup textures, per-layer blend modes, depth-tested)
 *        ─► Face paint lens (UV texture on the mesh, blended in place)
 *        ─► Face Liquify (beauty reshape + warp lens, one combined mesh warp)
 *        ─► Final pass (only when needed): background (segmentation mask, guided-upsampled × bokeh / colour /
 *           photo) · Smooth Glow · live filter with swipe split
 *        ─► Sprites & particles (atlas billboards anchored with the head pose, blended in place) ─► output
 *   └► small y-flipped copy ─► readback (PBO + fence) ─► MediaPipe Face Landmarker (LIVE_STREAM, 2 faces)
 *                                                    └► MediaPipe selfie segmenter (LIVE_STREAM) ─► smoothed mask
 * ```
 * Every pass is skipped when its features are neutral; the processor returns the input untouched when nothing is
 * active. Full-resolution passes ping-pong between two reused framebuffers; low-resolution work (masks, blurs) runs
 * at ¼–½ resolution. GL objects are created lazily at the sizes needed and released in [onDetach]. Per-frame work
 * does not allocate.
 *
 * Failure isolation: every pass runs as a [Stage]. A stage that throws (shader rejected by this GPU, framebuffer
 * incomplete, texture upload refused…) is recorded in the diagnostics log with the GPU name and switched off for this
 * camera session — the frame and every other effect keep working (reported as [BeautySuspendReason.GPU_ERROR] /
 * [EffectsStatus.effectsGpuError]). Only repeated failures outside any stage bypass the whole processor.
 */
internal class BeautyProcessor(
    private val controls: BeautyControls,
    private val context: Context,
) : GlFrameProcessor {

    /** Independent GPU passes; one failing never takes the others down. */
    internal enum class Stage { SKIN, MAKEUP, PAINT, WARP, BACKGROUND, GLOW, FINAL, SPRITES, READBACK }

    private var glVersion = 2
    private var attached = false
    private var failed = false
    private var meshFailed = false

    // Failure isolation (GL thread only).
    private val disabledStages = EnumSet.noneOf(Stage::class.java)
    private val stageFailures = IntArray(Stage.entries.size)
    private val stageRetryAtNs = LongArray(Stage.entries.size)
    private var consecutiveFrameFailures = 0
    private var lastGlCheckNs = 0L

    /** The GL thread this processor is attached to; calls from any other thread are ignored (stale renderer). */
    @Volatile private var ownerThread: Thread? = null

    /** Stages switched off in this session (for instrumented tests and diagnostics). GL thread only. */
    internal val disabledStageNames: Set<String> get() = disabledStages.mapTo(HashSet()) { it.name }

    /** True when repeated failures bypass the whole processor. */
    internal val isBypassedByFailure: Boolean get() = failed

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
    private var finalProgram: GlProgram? = null
    private var maskRefineProgram: GlProgram? = null
    private var bokehPrepProgram: GlProgram? = null
    private var bokehProgram: GlProgram? = null
    private var bokehProgramTaps = 0
    private var paintProgram: GlProgram? = null
    private var spriteProgram: GlProgram? = null

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
    private var fxGuide: GlFramebuffer? = null
    private var fxMask: GlFramebuffer? = null
    private var fxQuarterA: GlFramebuffer? = null
    private var fxQuarterB: GlFramebuffer? = null
    private var fxGlow: GlFramebuffer? = null
    private var fxGlowTmp: GlFramebuffer? = null
    private var faceMaskClear = false
    private var depth = DepthBufferGl()

    // Tracking.
    private var tracker: FaceLandmarkerTracker? = null
    private var segmenter: SelfieSegmenterTracker? = null
    private var grabber: FrameGrabber? = null
    private val detection = DetectionFrame()
    private val tracks = FaceTracks()
    private var framesSinceDetect = 0
    private var framesSinceSegment = 0
    private var pendingForFace = false
    private var pendingForSegment = false
    private var trackingActive = false
    private var segmentationActive = false

    // Face mesh.
    private var topology: FaceTopology? = null
    private var meshGl: FaceMeshGl? = null
    private var textures = MakeupTexturesGl()
    private var poses: Array<PoseFit> = emptyArray()
    private var reshapeField: ReshapeField? = null
    private var lensWarpField: LensWarpField? = null
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
    private var lensDisp = FloatArray(0)
    private var combinedDisp = FloatArray(0)
    private var lensDispFor: Lens? = null
    private var reshapeValid = false
    private var reshapeAny = false
    private val iris = FloatArray(16)
    private var irisCount = 0

    // Effects.
    private var luts = LutTexturesGl()
    private var maskTexture = MaskTextureGl()
    private var maskStaging: ByteBuffer? = null
    private var atlasTexture = BitmapTextureGl(mipmap = true)
    private var paintTexture = BitmapTextureGl(mipmap = true)
    private var bgImageTexture = BitmapTextureGl(mipmap = false)
    private val scene = LensScene()
    private var spriteDrawer: SpriteDrawer? = null
    private val splitAxis = FloatArray(3)
    private var lensTimeSec = 0f
    private var lastEffectsStatus = EffectsStatus()
    private var lastEffectsStatusNs = 0L

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
        val current = Thread.currentThread()
        val previousOwner = ownerThread
        if (attached && previousOwner != null && previousOwner !== current) {
            // A previous renderer never detached (its GL thread stalled): its GL objects died with its context.
            // Drop the stale ids without GL calls and close its trackers so nothing leaks or is double-owned.
            RgLog.w(TAG, "Attached to a new GL thread without a detach; forgetting stale GL objects")
            closeTrackers()
            forgetGlObjects()
        }
        ownerThread = current
        glVersion = context.glVersion
        attached = true
        failed = false
        meshFailed = false
        disabledStages.clear()
        stageFailures.fill(0)
        stageRetryAtNs.fill(0L)
        consecutiveFrameFailures = 0
        FaceAssets.prepare(this.context)
        SpriteAtlas.prepare()
        closeTrackers()
        tracker = try {
            FaceLandmarkerTracker(this.context).also { it.start() }
        } catch (t: Throwable) {
            Diagnostics.record(TAG, "Face landmarker unavailable", t)
            null
        }
        segmenter = try {
            SelfieSegmenterTracker(this.context).also { it.start() }
        } catch (t: Throwable) {
            Diagnostics.record(TAG, "Selfie segmenter unavailable", t)
            null
        }
        grabber = FrameGrabber(glVersion)
        tracks.reset()
        scene.reset()
    }

    private fun closeTrackers() {
        runCatching { tracker?.close() }
        tracker = null
        runCatching { segmenter?.close() }
        segmenter = null
    }

    /** Forgets (without GL calls) every GL object id: they belonged to a context that no longer exists. */
    private fun forgetGlObjects() {
        downsampleProgram = null; copyProgram = null; gaussianProgram = null; skinProgram = null
        compositeProgram = null; maskRegionsProgram = null; maskSolidProgram = null; makeupProgram = null
        warpProgram = null; finalProgram = null; maskRefineProgram = null; bokehPrepProgram = null; bokehProgram = null
        bokehProgramTaps = 0; paintProgram = null; spriteProgram = null
        bilateralPrograms.fill(null)
        small = null; smallTmp = null; smooth = null; large = null; largeTmp = null; skin = null
        faceMask = null; maskTmp = null; outA = null; outB = null
        fxGuide = null; fxMask = null; fxQuarterA = null; fxQuarterB = null; fxGlow = null; fxGlowTmp = null
        depth = DepthBufferGl()
        meshGl = null
        topology = null
        textures = MakeupTexturesGl()
        luts = LutTexturesGl()
        maskTexture = MaskTextureGl()
        atlasTexture = BitmapTextureGl(mipmap = true)
        paintTexture = BitmapTextureGl(mipmap = true)
        bgImageTexture = BitmapTextureGl(mipmap = false)
        spriteDrawer = null
        grabber = null
    }

    override fun onDetach() {
        val owner = ownerThread
        if (owner != null && owner !== Thread.currentThread()) {
            // A late detach from a renderer that was already replaced: the new owner's objects must stay alive.
            RgLog.w(TAG, "Ignoring detach from a stale GL thread")
            return
        }
        ownerThread = null
        attached = false
        closeTrackers()
        grabber?.release()
        grabber = null
        releasePrograms()
        listOf(small, smallTmp, smooth, large, largeTmp, skin, faceMask, maskTmp, outA, outB, fxGuide, fxMask, fxQuarterA, fxQuarterB, fxGlow, fxGlowTmp)
            .forEach { it?.release() }
        small = null; smallTmp = null; smooth = null; large = null; largeTmp = null; skin = null
        faceMask = null; maskTmp = null; outA = null; outB = null
        fxGuide = null; fxMask = null; fxQuarterA = null; fxQuarterB = null; fxGlow = null; fxGlowTmp = null
        depth.release()
        meshGl?.release()
        meshGl = null
        textures.release()
        luts.release()
        maskTexture.release()
        atlasTexture.release()
        paintTexture.release()
        bgImageTexture.release()
        spriteDrawer = null
        topology = null
        trackingActive = false
        segmentationActive = false
        tracks.reset()
        scene.reset()
        publishStatus(force = true, nowNs = System.nanoTime())
    }

    private fun releasePrograms() {
        listOf(downsampleProgram, copyProgram, gaussianProgram, skinProgram, compositeProgram, maskRegionsProgram,
            maskSolidProgram, makeupProgram, warpProgram, finalProgram, maskRefineProgram, bokehPrepProgram, bokehProgram,
            paintProgram, spriteProgram).forEach { it?.release() }
        bilateralPrograms.forEach { it?.release() }
        bilateralPrograms.fill(null)
        downsampleProgram = null; copyProgram = null; gaussianProgram = null; skinProgram = null
        compositeProgram = null; maskRegionsProgram = null; maskSolidProgram = null; makeupProgram = null
        warpProgram = null; finalProgram = null; maskRefineProgram = null; bokehPrepProgram = null; bokehProgram = null
        bokehProgramTaps = 0; paintProgram = null; spriteProgram = null
    }

    override fun onFrameTiming(processNanos: Long, frameBudgetNanos: Long) {
        val now = System.nanoTime()
        lastExternalTimingNs = now
        feedController(processNanos, frameBudgetNanos, now)
    }

    // ------------------------------------------------------------------------------------------------ frame

    override fun process(input: GlTextureFrame): GlTextureFrame {
        if (ownerThread !== Thread.currentThread()) return input
        val start = System.nanoTime()
        val state = controls.state
        val eyeColor = controls.eyeColor
        val effects = controls.effects
        val swipe = controls.filterSwipe
        syncControllerInputs(start)
        syncState(state)
        val beautyOn = beautyActive(state, eyeColor)
        val effectsOn = BeautyMapping.effectsActive(effects, swipe)
        if (!attached || failed || controls.bypassAll || (!beautyOn && !effectsOn) || input.width <= 0 || input.height <= 0) {
            if (trackingActive) { trackingActive = false; tracks.reset() }
            if (segmentationActive) { segmentationActive = false; segmenter?.reset(); maskTexture.invalidate() }
            scene.reset()
            lastFrameNs = 0L
            publishStatus(force = false, nowNs = start)
            if (effectsOn || beautyOn) publishEffectsStatus(effects, false, false, false, false, start)
            return input
        }
        return try {
            val out = render(input, state, eyeColor, beautyOn, effects, swipe)
            consecutiveFrameFailures = 0
            val end = System.nanoTime()
            // Without timing feedback from the renderer, measure our own submission cost against a 30 fps budget.
            if (end - lastExternalTimingNs > 2_000_000_000L) feedController(end - start, DEFAULT_BUDGET_NS, end)
            if (end - lastGlCheckNs > GL_CHECK_INTERVAL_NS) {
                lastGlCheckNs = end
                GlDiagnostics.drainErrors("effects frame (beauty=$beautyOn, fx=${effects.describe()})")
            }
            publishStatus(force = false, nowNs = end)
            out
        } catch (t: Throwable) {
            // Never break the camera: show the unprocessed frame. A failure outside every stage that repeats on
            // consecutive frames bypasses the processor for the rest of this camera session.
            consecutiveFrameFailures++
            Diagnostics.record(TAG, "Effects frame failed ($consecutiveFrameFailures in a row) [${GlDiagnostics.rendererSummary()}]", t)
            restoreGlState()
            if (consecutiveFrameFailures >= MAX_FRAME_FAILURES) {
                failed = true
                Diagnostics.record(TAG, "Effects bypassed for this camera session after repeated failures")
            }
            publishStatus(force = true, nowNs = System.nanoTime())
            input
        }
    }

    /**
     * Runs one pass. A throwing pass is recorded; transient failures are retried after a pause, a pass that fails
     * again (or whose shader this GPU rejects) is switched off for this camera session. Returns true on success.
     */
    private inline fun runStage(stage: Stage, nowNs: Long, block: () -> Unit): Boolean {
        if (!stageUsable(stage, nowNs)) return false
        return try {
            block()
            true
        } catch (t: Throwable) {
            stageFailed(stage, t, nowNs)
            false
        }
    }

    private fun stageUsable(stage: Stage, nowNs: Long): Boolean =
        stage !in disabledStages && nowNs >= stageRetryAtNs[stage.ordinal]

    private fun stageFailed(stage: Stage, t: Throwable, nowNs: Long) {
        restoreGlState()
        val n = ++stageFailures[stage.ordinal]
        val permanent = t is GlProgramException || n >= MAX_STAGE_FAILURES
        if (permanent) disabledStages += stage else stageRetryAtNs[stage.ordinal] = nowNs + STAGE_RETRY_NS
        Diagnostics.record(
            TAG,
            "Effect pass $stage failed (#$n) on ${GlDiagnostics.rendererSummary()}; " +
                if (permanent) "switched off for this camera session" else "retrying shortly",
            t,
        )
        publishStatus(force = true, nowNs = nowNs)
        lastEffectsStatusNs = 0L
    }

    /** The stage a lens draws in (it is unavailable when that stage is off). */
    private fun lensStage(lens: Lens): Stage = when (lens.kind) {
        Lens.Kind.WARP -> Stage.WARP
        Lens.Kind.PAINT -> Stage.PAINT
        Lens.Kind.SPRITE, Lens.Kind.PARTICLES -> Stage.SPRITES
        Lens.Kind.GRADE -> Stage.GLOW
    }

    private fun EffectsState.describe(): String =
        "lens=${lens?.id ?: "-"} filter=${filter.id}@$filterIntensity bg=${background::class.simpleName}"

    private fun beautyActive(state: BeautyState, eyeColor: EyeColorSetting) = state.enabled && (!cachedNeutral || eyeColor.active)

    private fun render(
        input: GlTextureFrame, state: BeautyState, eyeColor: EyeColorSetting, beautyRequested: Boolean,
        effects: EffectsState, swipe: FilterSwipe?,
    ): GlTextureFrame {
        val w = input.width
        val h = input.height
        val aspect = w.toFloat() / h
        val nowNs = if (input.timestampNs > 0) input.timestampNs else System.nanoTime()
        val clockNs = System.nanoTime()
        val dtSec = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs
        lensTimeSec += dtSec
        val profile = QualityProfile.of(controller.level)
        val lens = effects.lens
        // A beauty pipeline whose skin pass is off (GPU rejected it) cannot feed makeup or reshape either.
        val beautyOn = beautyRequested && stageUsable(Stage.SKIN, clockNs)

        restoreGlState()
        val meshReady = ensureMeshResources()

        // ---------------------------------------------------------------- tracking & segmentation
        val beautyTracks = beautyOn && profile.faceEffects
        val lensTracks = lens != null && BeautyMapping.lensNeedsFace(lens) && lensStage(lens) !in disabledStages
        val tr = tracker
        val readbackOk = Stage.READBACK !in disabledStages
        trackingActive = (beautyTracks || lensTracks) && tr != null && !tr.isFailed && !meshFailed && readbackOk
        val wantsBackground = BeautyMapping.wantsBackground(effects, controls.backgroundBitmap != null) &&
            Stage.BACKGROUND !in disabledStages && Stage.FINAL !in disabledStages
        val sg = segmenter
        val segOk = wantsBackground && sg != null && !sg.isFailed && readbackOk
        if (segOk && !segmentationActive) { sg?.reset(); maskTexture.invalidate() }
        if (!segOk && segmentationActive) maskTexture.invalidate()
        segmentationActive = segOk
        if (trackingActive || segmentationActive) {
            val detectEvery = if (beautyTracks) profile.detectEveryFrames else profile.lensDetectEveryFrames
            runStage(Stage.READBACK, clockNs) { updateReadback(input, profile, nowNs, aspect, detectEvery) }
        }
        tracks.update(nowNs / 1e9, dtSec)
        var faceCount = 0
        var maxPresence = 0f
        for (s in 0 until DetectionFrame.MAX_FACES) {
            val f = tracks.faces[s]
            val visible = meshReady && trackingActive && f.active && f.presence > 0f && preparePose(s, f)
            faceVisible[s] = visible
            if (visible) {
                faceCount++
                maxPresence = max(maxPresence, f.presence)
            }
        }
        val anyFace = faceCount > 0
        val beautyFace = beautyTracks && anyFace

        // ---------------------------------------------------------------- beauty intensities
        val u = BeautyMapping.uniforms(state, eyeColor, beautyOn, beautyFace, profile)
        val needMakeup = beautyFace && textures.ready && cachedHasMakeup && stageUsable(Stage.MAKEUP, clockNs)
        val warpOk = stageUsable(Stage.WARP, clockNs)
        val needBeautyWarp = warpOk && beautyFace && !reshapeParams.isNeutral && updateReshapeField()
        val needLensWarp = warpOk && anyFace && lens != null && lens.kind == Lens.Kind.WARP && updateLensWarp(lens)
        val wantPaint = anyFace && lens == Lens.FRECKLES && stageUsable(Stage.PAINT, clockNs)
        val wantSprites = lens != null && (lens.kind == Lens.Kind.SPRITE || lens.kind == Lens.Kind.PARTICLES)
        if (u.eyeWhitenK > 0f || u.eyeColorK > 0f) collectIrises() else irisCount = 0

        // ---------------------------------------------------------------- effect flags
        val glowK = if (lens == Lens.SMOOTH_GLOW && stageUsable(Stage.GLOW, clockNs)) 1f else 0f
        if (segmentationActive) runStage(Stage.BACKGROUND, clockNs) { pollMask() }
        val bgReady = segmentationActive && maskTexture.ready && stageUsable(Stage.BACKGROUND, clockNs)
        val filterActive = BeautyMapping.filterActive(effects, swipe)
        val finalOk = stageUsable(Stage.FINAL, clockNs)
        val needFinal = finalOk && (bgReady || glowK > 0f || filterActive)
        // The filter can ride on the composite when nothing after it rewrites the frame (or the final pass is off).
        val mergeFilterIntoComposite = beautyOn && filterActive && (
            !finalOk || (!bgReady && glowK == 0f && !needMakeup && !wantPaint && !needBeautyWarp && !needLensWarp)
            )

        var current: GlFramebuffer? = null
        var spare: GlFramebuffer? = null
        var currentTex = input.textureId

        // ---------------------------------------------------------------- beauty (low-resolution analysis + composite)
        if (beautyOn) {
            runStage(Stage.SKIN, clockNs) {
                val smallW = even((w * profile.smoothScale).roundToInt().coerceAtLeast(32))
                val smallH = even((smallW.toLong() * h / w).toInt().coerceAtLeast(32))
                val smallFb = fbo(small, smallW, smallH).also { small = it }
                downsample(input.textureId, smallFb)

                val maskW = even(minOf(profile.maskWidth, w))
                val maskH = even((maskW.toLong() * h / w).toInt().coerceAtLeast(16))
                if (faceMask?.let { it.width != maskW || it.height != maskH } != false) faceMaskClear = false
                val faceFb = fbo(faceMask, maskW, maskH).also { faceMask = it }
                if (beautyFace) {
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
                    p.setFloat("uOutsideK", 1f - 0.35f * (if (beautyFace) maxPresence else 0f))
                    FullScreenQuad.draw(p)
                }

                var smoothTex = smallFb.textureId
                if (u.smoothK > 0f || u.retouchK > 0f) {
                    val strength = max(u.smoothK, u.retouchK * 0.6f)
                    smoothTex = bilateral(smallFb, smallW, smallH, w, h, profile.bilateralRadius, strength)
                }
                var largeTex = smallFb.textureId
                if (u.retouchK > 0f || u.blemishK > 0f || u.darkK > 0f) largeTex = largeBlur(smallFb, smallW, smallH)

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
                    p.setFloat("uSmoothK", u.smoothK)
                    // Frequency separation: keep most fine texture (pores) at low strengths, less at high ones.
                    p.setFloat("uDetailKeep", u.detailKeep)
                    p.setFloat("uRetouchK", u.retouchK)
                    p.setFloat("uBlemishK", u.blemishK)
                    p.setFloat("uBrightK", u.brightK)
                    p.setFloat("uWhitenK", u.whitenK)
                    p.setFloat("uToneT", u.toneT)
                    p.setFloat("uSharpenK", u.sharpenK)
                    p.setFloat("uDarkCircleK", u.darkK)
                    p.setFloat("uTeethK", u.teethK)
                    p.setFloat("uEyeWhitenK", u.eyeWhitenK)
                    p.setFloat("uEyeSharpenK", u.eyeSharpenK)
                    p.setVec4("uEyeColor", red(eyeColor.color), green(eyeColor.color), blue(eyeColor.color), u.eyeColorK)
                    p.setFloat("uUseFaceMask", if (beautyFace) 1f else 0f)
                    p.setInt("uIrisCount", irisCount)
                    if (irisCount > 0) p.setVec4Array("uIris", iris, irisCount)
                    setFilterUniforms(p, if (mergeFilterIntoComposite) effects else null, swipe, 6, 7)
                    FullScreenQuad.draw(p)
                }
                current = a
                spare = fbo(outB, w, h).also { outB = it }
                currentTex = a.textureId

                // ---------------------------------------------------------------- Face Mask makeup
                if (needMakeup) {
                    runStage(Stage.MAKEUP, clockNs) {
                        val target = spare!!
                        renderMakeup(a, target, smallFb, skinFb, aspect, profile)
                        spare = current; current = target; currentTex = target.textureId
                    }
                }
            }
        }

        // ---------------------------------------------------------------- face paint lens (in place)
        if (wantPaint) {
            runStage(Stage.PAINT, clockNs) {
                if (paintTexture.ensureRgba(EffectsAssets.paint(), FacePaint.SIZE)) {
                    val owned = current ?: ensureOwned(input, w, h).also { current = it; spare = fbo(outB, w, h).also { b -> outB = b } }
                    renderPaint(owned, aspect, profile)
                    currentTex = owned.textureId
                }
            }
        }

        // ---------------------------------------------------------------- Face Liquify (beauty reshape + warp lens)
        if (needBeautyWarp || needLensWarp) {
            runStage(Stage.WARP, clockNs) {
                val disp = combineWarp(needBeautyWarp, needLensWarp)
                val target = spare ?: fbo(outA, w, h).also { outA = it }
                renderWarp(currentTex, target, aspect, profile, disp)
                spare = current ?: fbo(outB, w, h).also { outB = it }
                current = target
                currentTex = target.textureId
            }
        }

        // ---------------------------------------------------------------- final pass: background · glow · filter
        if (needFinal && !mergeFilterIntoComposite) {
            val maskTex = if (bgReady) {
                var tex = 0
                runStage(Stage.BACKGROUND, clockNs) { tex = prepareBackground(currentTex, w, h, effects.background, profile) }
                tex
            } else {
                0
            }
            val glowTex = if (glowK > 0f) {
                var tex = 0
                runStage(Stage.GLOW, clockNs) { tex = glowTexture(currentTex, w, h) }
                tex
            } else {
                0
            }
            runStage(Stage.FINAL, clockNs) {
                val target = spare ?: fbo(if (current === outA) outB else outA, w, h).also { if (current === outA) outB = it else outA = it }
                renderFinal(currentTex, target, w, h, effects, swipe, maskTex, glowTex, glowK)
                spare = current
                current = target
                currentTex = target.textureId
            }
        }

        // ---------------------------------------------------------------- sprites & particles (in place)
        if (wantSprites && lens != null && stageUsable(Stage.SPRITES, clockNs)) {
            runStage(Stage.SPRITES, clockNs) {
                scene.begin()
                if (anyFace && atlasTexture.ensure(SpriteAtlas.bitmap)) {
                    for (s in 0 until DetectionFrame.MAX_FACES) {
                        if (!faceVisible[s]) continue
                        val f = tracks.faces[s]
                        scene.addFace(lens, s, poses[s].affine, aspect, f.landmarks, f.blendshapes[Blendshape.JAW_OPEN],
                            f.presence, lensTimeSec, dtSec, profile.maxParticles)
                    }
                }
                scene.finish(dtSec)
                if (scene.batch.quads > 0 && atlasTexture.id != 0) {
                    val owned = current ?: ensureOwned(input, w, h).also { current = it }
                    renderSprites(owned)
                    currentTex = owned.textureId
                }
            }
        } else {
            scene.reset()
        }

        publishEffectsStatus(effects, lensTracks, anyFace, wantsBackground, bgReady, clockNs)
        restoreGlState()
        val out = current ?: return input
        return GlTextureFrame(out.textureId, w, h, input.timestampNs, input.mirrored)
    }

    /** Copies the (not owned) input frame into a pipeline framebuffer so passes can draw into it in place. */
    private fun ensureOwned(input: GlTextureFrame, w: Int, h: Int): GlFramebuffer {
        val a = fbo(outA, w, h).also { outA = it }
        val p = copyProgram()
        a.bind()
        p.use()
        p.bindTexture("uTexture", 0, input.textureId)
        FullScreenQuad.draw(p)
        return a
    }

    // ------------------------------------------------------------------------------------------------ tracking

    /**
     * One downscaled readback feeds both MediaPipe tasks: a finished (fence-signalled) PBO read is handed to the face
     * landmarker and/or the segmenter that were due when it was issued; a new read starts when either is due.
     */
    private fun updateReadback(input: GlTextureFrame, profile: QualityProfile, nowNs: Long, aspect: Float, detectEvery: Int) {
        val gr = grabber ?: return
        val tr = tracker?.takeIf { trackingActive }
        val sg = segmenter?.takeIf { segmentationActive }

        // 1) Consume a finished detection.
        if (tr != null && tr.pollNew(detection)) tracks.onDetection(detection, detection.timestampNs / 1e9)

        // 2) Async readback started on an earlier frame → hand it to the consumers.
        if (gr.isAsync && gr.hasPending) {
            val stale = nowNs - gr.pendingTimestampNs > STALE_READBACK_NS
            if (gr.pendingReady()) {
                val faceOk = pendingForFace && tr != null && tr.canAccept()
                val segOk = pendingForSegment && sg != null && sg.canAccept()
                if (faceOk || segOk) {
                    val first = if (faceOk) tr!!.acquireInput(gr.width, gr.height) else sg!!.acquireInput(gr.width, gr.height)
                    if (gr.collect(first)) {
                        // Copy for the segmenter before either task owns the buffer (they read it on their threads).
                        if (faceOk && segOk) copyInto(first, sg!!.acquireInput(gr.width, gr.height), gr.width * gr.height * 4)
                        if (faceOk) tr!!.commit(gr.width, gr.height, aspect, gr.pendingTimestampNs)
                        if (segOk) sg!!.commit(gr.width, gr.height, gr.pendingTimestampNs)
                    }
                } else if (stale) {
                    gr.discardPending()
                }
            } else if (stale) {
                gr.discardPending()
            }
        }

        // 3) Start a new readback when a consumer is due and ready.
        framesSinceDetect++
        framesSinceSegment++
        val dueFace = tr != null && tr.isReady && framesSinceDetect >= detectEvery
        val dueSeg = sg != null && sg.isReady && framesSinceSegment >= profile.segmentEveryFrames
        if (gr.hasPending || !(dueFace || dueSeg)) return
        gr.configure(input.width, input.height, profile.detectWidth)
        if (gr.isAsync) {
            if (dueFace) framesSinceDetect = 0
            if (dueSeg) framesSinceSegment = 0
            pendingForFace = dueFace
            pendingForSegment = dueSeg
            gr.startAsync(input.textureId, downsampleProgram(), nowNs)
        } else {
            val faceOk = dueFace && tr!!.canAccept()
            val segOk = dueSeg && sg!!.canAccept()
            if (!faceOk && !segOk) return
            val first = if (faceOk) tr!!.acquireInput(gr.width, gr.height) else sg!!.acquireInput(gr.width, gr.height)
            if (!gr.readSync(input.textureId, downsampleProgram(), first)) return
            if (faceOk && segOk) copyInto(first, sg!!.acquireInput(gr.width, gr.height), gr.width * gr.height * 4)
            if (faceOk) { framesSinceDetect = 0; tr!!.commit(gr.width, gr.height, aspect, nowNs) }
            if (segOk) { framesSinceSegment = 0; sg!!.commit(gr.width, gr.height, nowNs) }
        }
    }

    private fun copyInto(src: ByteBuffer, dst: ByteBuffer, bytes: Int) {
        src.position(0)
        src.limit(bytes)
        dst.position(0)
        dst.put(src)
        dst.position(0)
        src.clear()
    }

    private fun pollMask() {
        val sg = segmenter ?: return
        val staging = maskStaging ?: ByteBuffer.allocateDirect(SelfieSegmenterTracker.MAX_MASK_BYTES).order(ByteOrder.nativeOrder()).also { maskStaging = it }
        val packed = sg.pollNew(staging)
        if (packed != 0) maskTexture.upload(staging, packed ushr 16, packed and 0xFFFF)
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
            // Mesh programs are built lazily inside their stages: a rejected makeup or warp shader then only turns
            // that pass off instead of every face effect.
            topology = topo
            poses = Array(DetectionFrame.MAX_FACES) { PoseFit(loaded.model) }
            reshapeField = ReshapeField(loaded.model)
            lensWarpField = LensWarpField(loaded.model)
            meshWarp = MeshWarp(topo)
            meshPositions = Array(DetectionFrame.MAX_FACES) { FloatArray(topo.totalVertexCount * 3) }
            warpPositions = FloatArray(topo.totalVertexCount * 3)
            canonicalDisp = FloatArray(topo.meshVertexCount * 2)
            lensDisp = FloatArray(topo.meshVertexCount * 2)
            combinedDisp = FloatArray(topo.meshVertexCount * 2)
            lensDispFor = null
            reshapeValid = false
            meshGl = gl
            true
        } catch (t: Throwable) {
            Diagnostics.record(TAG, "Face mesh GPU setup failed; face effects disabled [${GlDiagnostics.rendererSummary()}]", t)
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

    /** Recomputes the warp-lens field when the lens changed (every frame for animated lenses). */
    private fun updateLensWarp(lens: Lens): Boolean {
        val field = lensWarpField ?: return false
        if (lensDispFor != lens || field.isAnimated(lens)) {
            field.compute(lens, lensTimeSec, lensDisp)
            lensDispFor = lens
        }
        return true
    }

    private fun combineWarp(beauty: Boolean, lens: Boolean): FloatArray = when {
        beauty && lens -> {
            for (i in combinedDisp.indices) combinedDisp[i] = canonicalDisp[i] + lensDisp[i]
            combinedDisp
        }
        lens -> lensDisp
        else -> canonicalDisp
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
        copy(source.textureId, target)
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

    /** Freckles & blush face paint, premultiplied-blended onto [target] in place (no copy). */
    private fun renderPaint(target: GlFramebuffer, aspect: Float, profile: QualityProfile) {
        val gl = meshGl ?: return
        val useDepth = beginMeshDepth(target, profile)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val p = paintProgram()
        setMeshCommon(p, aspect)
        p.bindTexture("uPaint", 0, paintTexture.id)
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            p.setFloat("uK", tracks.faces[s].presence)
            cullFor(s)
            gl.drawMesh(p, s, FaceMeshGl.SURFACE)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (useDepth) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun renderWarp(sourceTex: Int, target: GlFramebuffer, aspect: Float, profile: QualityProfile, disp: FloatArray) {
        val gl = meshGl ?: return
        val warp = meshWarp ?: return
        copy(sourceTex, target)
        val useDepth = beginMeshDepth(target, profile)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        val p = warpProgram()
        setMeshCommon(p, aspect)
        p.bindTexture("uTexture", 0, sourceTex)
        for (s in 0 until DetectionFrame.MAX_FACES) {
            if (!faceVisible[s]) continue
            warp.apply(meshPositions[s], disp, poses[s], tracks.faces[s].presence, warpPositions)
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

    private fun copy(sourceTex: Int, target: GlFramebuffer) {
        val p = copyProgram()
        target.bind()
        p.use()
        p.bindTexture("uTexture", 0, sourceTex)
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

    // ------------------------------------------------------------------------------------------------ effects passes

    /**
     * Live filter uniforms of a program containing `EffectsShaders.FILTER_BLOCK`. [effects] null disables the filter
     * (the program is shared with frames that do not merge it).
     */
    private fun setFilterUniforms(p: GlProgram, effects: EffectsState?, swipe: FilterSwipe?, unitA: Int, unitB: Int) {
        if (effects == null) {
            p.setFloat("uLutK", 0f)
            p.setFloat("uLutBK", 0f)
            p.setFloat("uSplitSide", 0f)
            return
        }
        val k = effects.filterIntensity.coerceIn(0, 100) / 100f
        val texA = if (k > 0f) luts.texture(effects.filter) else 0
        p.bindTexture("uLut", unitA, texA)
        p.setFloat("uLutK", if (texA != 0) k else 0f)
        if (swipe == null || swipe.target == effects.filter) {
            p.setFloat("uSplitSide", 0f)
            p.setFloat("uLutBK", 0f)
            p.bindTexture("uLutB", unitB, texA)
            return
        }
        val texB = luts.texture(swipe.target)
        p.bindTexture("uLutB", unitB, texB)
        // The incoming filter shows at the current intensity (full strength when the current one is at 0).
        val kB = if (effects.filterIntensity <= 0) 1f else k
        p.setFloat("uLutBK", if (texB != 0) kB else 0f)
        EffectsShaders.splitAxis(swipe.screenRotationCw, splitAxis)
        p.setVec3("uSplitAxis", splitAxis[0], splitAxis[1], splitAxis[2])
        val progress = swipe.progress.coerceIn(-1f, 1f)
        if (progress < 0f) {
            // Finger moving towards the start (left): the incoming filter appears from the right edge.
            p.setFloat("uSplitSide", 1f)
            p.setFloat("uSplitEdge", 1f + progress)
        } else {
            p.setFloat("uSplitSide", -1f)
            p.setFloat("uSplitEdge", progress)
        }
    }

    /**
     * Prepares the refined mask (guided upsampling of the segmentation mask at the smoothing resolution) and, for the
     * portrait blur, the bokeh texture. Returns the refined mask texture.
     */
    private fun prepareBackground(frameTex: Int, w: Int, h: Int, bg: BackgroundEffect, profile: QualityProfile): Int {
        // Guide: the frame at the mask's resolution.
        val mw = maskTexture.width.coerceAtLeast(16)
        val mh = maskTexture.height.coerceAtLeast(16)
        val guide = fbo(fxGuide, mw, mh).also { fxGuide = it }
        downsample(frameTex, guide)
        // Refined mask at the smoothing resolution.
        val rw = even((w * profile.smoothScale).roundToInt().coerceAtLeast(32))
        val rh = even((rw.toLong() * h / w).toInt().coerceAtLeast(32))
        val refined = fbo(fxMask, rw, rh).also { fxMask = it }
        maskRefineProgram().let { p ->
            refined.bind()
            p.use()
            p.bindTexture("uMask", 0, maskTexture.id)
            p.bindTexture("uGuide", 1, guide.textureId)
            p.bindTexture("uFrame", 2, frameTex)
            p.setVec2("uMaskTexel", 1f / mw, 1f / mh)
            p.setFloat("uRange", 38f)
            FullScreenQuad.draw(p)
        }
        if (bg is BackgroundEffect.Blur) {
            val qw = even((w / 4).coerceAtLeast(16))
            val qh = even((h / 4).coerceAtLeast(16))
            val prep = fbo(fxQuarterA, qw, qh).also { fxQuarterA = it }
            val blur = fbo(fxQuarterB, qw, qh).also { fxQuarterB = it }
            bokehPrepProgram().let { p ->
                prep.bind()
                p.use()
                p.bindTexture("uTexture", 0, frameTex)
                p.bindTexture("uMask", 1, refined.textureId)
                p.setVec2("uOffset", 1f / w, 1f / h)
                FullScreenQuad.draw(p)
            }
            val taps = profile.bokehTaps
            val bp = bokehProgram(taps)
            val strength = bg.strength.coerceIn(0, 100) / 100f
            // Radius: up to ~2.6 % of the frame height, isotropic.
            val radiusY = (0.006f + 0.02f * strength)
            bp.use()
            blur.bind()
            bp.bindTexture("uTexture", 0, prep.textureId)
            bp.setVec2("uRadius", radiusY * h / w, radiusY)
            FullScreenQuad.draw(bp)
            // Second, smaller disc for a smooth, creamy result.
            prep.bind()
            bp.bindTexture("uTexture", 0, blur.textureId)
            bp.setVec2("uRadius", radiusY * 0.45f * h / w, radiusY * 0.45f)
            FullScreenQuad.draw(bp)
        }
        return refined.textureId
    }

    /** Quarter-resolution blurred frame for Smooth Glow. */
    private fun glowTexture(frameTex: Int, w: Int, h: Int): Int {
        val qw = even((w / 4).coerceAtLeast(16))
        val qh = even((h / 4).coerceAtLeast(16))
        val g = fbo(fxGlow, qw, qh).also { fxGlow = it }
        val t = fbo(fxGlowTmp, qw, qh).also { fxGlowTmp = it }
        downsample(frameTex, g)
        val p = gaussianProgram()
        p.use()
        for (pass in 0 until 2) {
            t.bind()
            p.bindTexture("uTexture", 0, g.textureId)
            p.setVec2("uStep", 1.6f / qw, 0f)
            FullScreenQuad.draw(p)
            g.bind()
            p.bindTexture("uTexture", 0, t.textureId)
            p.setVec2("uStep", 0f, 1.6f / qh)
            FullScreenQuad.draw(p)
        }
        return g.textureId
    }

    private fun renderFinal(
        sourceTex: Int, target: GlFramebuffer, w: Int, h: Int, effects: EffectsState, swipe: FilterSwipe?,
        maskTex: Int, glowTex: Int, glowK: Float,
    ) {
        val p = finalProgram()
        target.bind()
        p.use()
        p.bindTexture("uTexture", 0, sourceTex)
        val bg = effects.background
        val mode = when {
            maskTex == 0 -> 0f
            bg is BackgroundEffect.Blur -> 1f
            bg is BackgroundEffect.Color -> 2f
            bg is BackgroundEffect.Gradient -> 3f
            bg is BackgroundEffect.Image && bgImageTexture.ensure(controls.backgroundBitmap) -> 4f
            else -> 0f
        }
        p.setFloat("uBgMode", mode)
        if (mode > 0f) {
            p.bindTexture("uMask", 1, maskTex)
            when (bg) {
                is BackgroundEffect.Blur -> p.bindTexture("uBgBlur", 2, fxQuarterA?.textureId ?: sourceTex)
                is BackgroundEffect.Color -> p.setVec4("uBgColorA", red(bg.argb), green(bg.argb), blue(bg.argb), 1f)
                is BackgroundEffect.Gradient -> {
                    p.setVec4("uBgColorA", red(bg.top), green(bg.top), blue(bg.top), 1f)
                    p.setVec4("uBgColorB", red(bg.bottom), green(bg.bottom), blue(bg.bottom), 1f)
                }
                is BackgroundEffect.Image -> {
                    p.bindTexture("uBgImage", 3, bgImageTexture.id)
                    // "Cover" fit; bitmap rows are top-down (t = 0 at the top), frame texcoords bottom-up.
                    val frameAspect = w.toFloat() / h
                    val imageAspect = bgImageTexture.width.toFloat() / bgImageTexture.height.coerceAtLeast(1)
                    var sx = 1f; var sy = 1f
                    if (imageAspect > frameAspect) sx = frameAspect / imageAspect else sy = imageAspect / frameAspect
                    val ox = (1f - sx) / 2f; val oy = (1f - sy) / 2f
                    p.setVec4("uBgImageRect", sx, -sy, ox, sy + oy)
                }
                BackgroundEffect.None -> Unit
            }
        }
        p.setFloat("uGlowK", if (glowTex != 0) glowK else 0f)
        if (glowTex != 0) p.bindTexture("uGlow", 4, glowTex)
        setFilterUniforms(p, effects, swipe, 5, 6)
        FullScreenQuad.draw(p)
    }

    private fun renderSprites(target: GlFramebuffer) {
        val drawer = spriteDrawer ?: SpriteDrawer(scene.batch.data.size).also { spriteDrawer = it }
        target.bind()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawer.draw(spriteProgram(), scene.batch, atlasTexture.id)
        GLES20.glDisable(GLES20.GL_BLEND)
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
        val beautyOn = beautyActive(state, controls.eyeColor)
        val effectsOn = !controls.effects.isNeutral || controls.filterSwipe != null
        if ((!beautyOn && !effectsOn) || controls.bypassAll) return
        if (controller.onFrame(processNanos, budgetNanos, nowNs / 1e9)) {
            RgLog.i(TAG, "Quality → ${controller.level} (avg ${"%.2f".format(controller.averageRatio)} of budget)")
            publishStatus(force = true, nowNs = nowNs)
        }
    }

    private fun publishStatus(force: Boolean, nowNs: Long) {
        if (!force && nowNs - lastStatusNs < STATUS_INTERVAL_NS) return
        val state = controls.state
        val eyeColorOn = controls.eyeColor.active
        val beautyOn = attached && !failed && !controls.bypassAll && state.enabled && (!state.isNeutral || eyeColorOn)
        val effectsOn = attached && !failed && !controls.bypassAll && !controls.effects.isNeutral
        val active = beautyOn || effectsOn
        val faces = if (active && trackingActive) tracks.visibleCount else 0
        val needsFace = beautyOn && (state.needsFaceTracking || eyeColorOn)
        val level = controller.level
        val tr = tracker
        val beautyStageOff = Stage.SKIN in disabledStages ||
            (cachedHasMakeup && Stage.MAKEUP in disabledStages) ||
            (!reshapeParams.isNeutral && Stage.WARP in disabledStages)
        val reason = when {
            failed -> BeautySuspendReason.GPU_ERROR
            !beautyOn -> null
            beautyStageOff -> BeautySuspendReason.GPU_ERROR
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
        if (!effectsOn && lastEffectsStatus != EffectsStatus()) {
            lastEffectsStatus = EffectsStatus()
            controls.onEffectsStatus(lastEffectsStatus)
        }
    }

    private fun publishEffectsStatus(effects: EffectsState, lensTracks: Boolean, anyFace: Boolean, wantsBackground: Boolean, bgReady: Boolean, nowNs: Long) {
        // Throttled before anything is built, so steady frames allocate nothing here.
        if (nowNs - lastEffectsStatusNs in 0 until EFFECTS_STATUS_INTERVAL_NS) return
        val sg = segmenter
        val tr = tracker
        val bgActive = effects.background.active
        val lens = effects.lens
        // Face lenses need the landmarker, the face mesh and the readback; say so instead of silently doing nothing.
        val lensUnavailable = lens != null && (
            lensStage(lens) in disabledStages ||
                (lens.needsFace && (tr == null || tr.isFailed || meshFailed || FaceAssets.failed || Stage.READBACK in disabledStages))
            )
        val gpuError = failed ||
            (lens != null && lensStage(lens) in disabledStages) ||
            (bgActive && (Stage.BACKGROUND in disabledStages || Stage.FINAL in disabledStages)) ||
            (BeautyMapping.filterActive(effects, null) && Stage.FINAL in disabledStages && Stage.SKIN in disabledStages)
        val status = EffectsStatus(
            lensNeedsFace = lensTracks && !anyFace && tr?.isReady == true && (tracks.visibleCount == 0),
            backgroundUnavailable = bgActive && (
                sg == null || sg.isFailed ||
                    Stage.READBACK in disabledStages || Stage.BACKGROUND in disabledStages || Stage.FINAL in disabledStages
                ),
            backgroundWarmingUp = wantsBackground && !bgReady && sg != null && !sg.isFailed,
            backgroundImageMissing = effects.background is BackgroundEffect.Image && controls.backgroundBitmap == null,
            lensUnavailable = lensUnavailable,
            effectsGpuError = gpuError,
        )
        lastEffectsStatusNs = nowNs
        if (status != lastEffectsStatus) {
            lastEffectsStatus = status
            controls.onEffectsStatus(status)
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

    // Programs are built lazily inside the stage that uses them: a program this GPU rejects throws
    // GlProgramException (recorded with its name and the driver log) and only that stage is switched off.
    private fun downsampleProgram() = downsampleProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.DOWNSAMPLE, "beauty.downsample").also { downsampleProgram = it }
    private fun copyProgram() = copyProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.COPY, "beauty.copy").also { copyProgram = it }
    private fun gaussianProgram() = gaussianProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.GAUSSIAN, "beauty.gaussian").also { gaussianProgram = it }
    private fun skinProgram() = skinProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.SKIN_MASK, "beauty.skinMask").also { skinProgram = it }
    private fun compositeProgram() = compositeProgram ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.COMPOSITE, "beauty.composite").also { compositeProgram = it }
    private fun maskRegionsProgram() = maskRegionsProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_REGIONS, "mesh.maskRegions").also { maskRegionsProgram = it }
    private fun maskSolidProgram() = maskSolidProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MASK_SOLID, "mesh.maskSolid").also { maskSolidProgram = it }
    private fun makeupProgram() = makeupProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, BeautyShaders.MAKEUP, "mesh.makeup").also { makeupProgram = it }
    private fun warpProgram() = warpProgram ?: GlProgram(BeautyShaders.WARP_VERTEX, BeautyShaders.WARP_FRAGMENT, "mesh.warp").also { warpProgram = it }
    private fun finalProgram() = finalProgram ?: GlProgram(BeautyShaders.VERTEX, EffectsShaders.FINAL, "effects.final").also { finalProgram = it }
    private fun maskRefineProgram() = maskRefineProgram ?: GlProgram(BeautyShaders.VERTEX, EffectsShaders.MASK_REFINE, "effects.maskRefine").also { maskRefineProgram = it }
    private fun bokehPrepProgram() = bokehPrepProgram ?: GlProgram(BeautyShaders.VERTEX, EffectsShaders.BOKEH_PREP, "effects.bokehPrep").also { bokehPrepProgram = it }
    private fun paintProgram() = paintProgram ?: GlProgram(BeautyShaders.MESH_VERTEX, EffectsShaders.PAINT, "effects.paint").also { paintProgram = it }
    private fun spriteProgram() = spriteProgram ?: GlProgram(EffectsShaders.SPRITE_VERTEX, EffectsShaders.SPRITE_FRAGMENT, "effects.sprite").also { spriteProgram = it }
    private fun bokehProgram(taps: Int): GlProgram {
        val existing = bokehProgram
        if (existing != null && bokehProgramTaps == taps) return existing
        existing?.release()
        bokehProgram = null
        return GlProgram(BeautyShaders.VERTEX, EffectsShaders.bokeh(taps), "effects.bokeh$taps").also {
            bokehProgram = it
            bokehProgramTaps = taps
        }
    }
    private fun bilateralProgram(radius: Int): GlProgram {
        val r = radius.coerceIn(1, MAX_BILATERAL_RADIUS)
        return bilateralPrograms[r] ?: GlProgram(BeautyShaders.VERTEX, BeautyShaders.bilateral(r), "beauty.bilateral$r").also { bilateralPrograms[r] = it }
    }

    private fun even(v: Int) = (v + 1) and 0x7FFFFFFE

    private fun red(c: Long) = ((c shr 16) and 0xFF) / 255f
    private fun green(c: Long) = ((c shr 8) and 0xFF) / 255f
    private fun blue(c: Long) = (c and 0xFF) / 255f

    companion object {
        private const val TAG = "Beauty"
        private const val MAX_BILATERAL_RADIUS = 6
        private const val DEFAULT_BUDGET_NS = 33_333_333L
        /** Consecutive frames failing outside every stage before the processor is bypassed. */
        private const val MAX_FRAME_FAILURES = 3
        /** Failures of one stage before it is switched off for the session (a rejected shader: immediately). */
        private const val MAX_STAGE_FAILURES = 2
        private const val STAGE_RETRY_NS = 1_500_000_000L
        private const val GL_CHECK_INTERVAL_NS = 3_000_000_000L
        private const val STATUS_INTERVAL_NS = 250_000_000L
        private const val EFFECTS_STATUS_INTERVAL_NS = 150_000_000L
        /** An async readback the consumers could not take within this time is dropped. */
        private const val STALE_READBACK_NS = 200_000_000L
        /** Landmark z (frame units) → NDC depth. */
        private const val DEPTH_SCALE = 1.5f
        /** Ring vertices sit this far (cm, scaled by the face) behind the oval so the face wins depth ties. */
        private const val RING_DEPTH_CM = 1.0f
    }
}
