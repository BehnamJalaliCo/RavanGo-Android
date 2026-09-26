package com.ravango.engine.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.ravango.core.common.crash.CrashGuard
import com.ravango.core.common.device.DeviceProfiler
import com.ravango.core.common.device.StorageInfo
import com.ravango.core.common.device.StorageSnapshot
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.device.ThermalMonitor
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.model.BitrateProfile
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.FlashMode
import com.ravango.core.model.LensFacing
import com.ravango.core.model.ManualControls
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.core.model.newId
import com.ravango.engine.audio.AudioEngine
import com.ravango.engine.audio.AudioRecordingHandle
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.capability.CameraInventory
import com.ravango.engine.camera.capability.CapabilityDetector
import com.ravango.engine.camera.capability.EncoderSupport
import com.ravango.engine.camera.capability.HdrOption
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.capability.VideoModeSelector
import com.ravango.engine.camera.encoder.BitrateCalculator
import com.ravango.engine.camera.encoder.VideoEncoderConfig
import com.ravango.engine.camera.gl.CameraRenderer
import com.ravango.engine.camera.gl.FrameGeometry
import com.ravango.engine.camera.gl.RenderGeometry
import com.ravango.engine.camera.muxer.RecordingFinalizer
import com.ravango.engine.camera.muxer.RecordingManifest
import com.ravango.engine.camera.recorder.ActiveRecordings
import com.ravango.engine.camera.recorder.VideoRecorder
import com.ravango.engine.camera.recovery.RecordingRecovery
import com.ravango.engine.camera.session.AfRequestMode
import com.ravango.engine.camera.session.CameraSession
import com.ravango.engine.camera.session.MeteringMath
import com.ravango.engine.camera.session.RequestParams
import com.ravango.engine.camera.session.SensorRect
import com.ravango.engine.camera.session.WhiteBalanceMath
import com.ravango.engine.render.GlFrameProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 implementation of [CameraEngine].
 *
 * Threads: a camera thread owns the Camera2 device/session and all session state; the GL thread (see
 * [CameraRenderer]) renders; encoders have their own threads. Lifecycle commands (start/stop/settings/lens) are
 * serialized through a single-lane coroutine queue so a fast stop→start never interleaves.
 */
@Singleton
class Camera2Engine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: CapabilityDetector,
    private val encoders: EncoderSupport,
    private val audioEngine: AudioEngine,
    private val storage: StorageInfo,
    thermalMonitor: ThermalMonitor,
    private val deviceProfiler: DeviceProfiler,
    private val crashGuard: CrashGuard,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CameraEngine {

    private val _inventory = MutableStateFlow<CameraInventory?>(null)
    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    private val _settings = MutableStateFlow<CameraSettings?>(null)
    private val _previewFrame = MutableStateFlow<PreviewFrame?>(null)
    private val _controls = MutableStateFlow(ManualControls())
    private val _focus = MutableStateFlow(FocusState())
    private val _liveExposure = MutableStateFlow(LiveExposure())
    private val _recording = MutableStateFlow(RecordingStatus())
    private val _stats = MutableStateFlow(PipelineStats())
    private val _thermal = MutableStateFlow(ThermalLevel.NORMAL)
    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 32)

    override val inventory: StateFlow<CameraInventory?> = _inventory.asStateFlow()
    override val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()
    override val state: StateFlow<CameraState> = _state.asStateFlow()
    override val settings: StateFlow<CameraSettings?> = _settings.asStateFlow()
    override val previewFrame: StateFlow<PreviewFrame?> = _previewFrame.asStateFlow()
    override val controls: StateFlow<ManualControls> = _controls.asStateFlow()
    override val focus: StateFlow<FocusState> = _focus.asStateFlow()
    override val liveExposure: StateFlow<LiveExposure> = _liveExposure.asStateFlow()
    override val recording: StateFlow<RecordingStatus> = _recording.asStateFlow()
    override val stats: StateFlow<PipelineStats> = _stats.asStateFlow()
    override val thermal: StateFlow<ThermalLevel> = _thermal.asStateFlow()
    override val events: SharedFlow<CameraEvent> = _events.asSharedFlow()

    private val manager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("RgCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val sessionListener = object : CameraSession.Listener {
        override fun onStreaming(cameraId: String) = handleStreaming()
        override fun onCameraError(kind: CameraErrorKind, message: String?, recoverable: Boolean) = handleCameraError(kind, message, recoverable)
        override fun onResult(result: CameraSession.CaptureSnapshot) = handleResult(result)
    }
    private val rendererCallbacks = object : CameraRenderer.Callbacks {
        override fun onPreviewFrame(frame: PreviewFrame) {
            _previewFrame.value = frame
        }

        override fun onStats(stats: PipelineStats) {
            _stats.value = stats
        }

        override fun onEncoderSurfaceLost() {
            recorderEvents.onRecorderFailure(IllegalStateException("encoder surface lost"))
        }
    }
    private val session: CameraSession? = manager?.let { CameraSession(it, cameraHandler, sessionListener) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val commandMutex = Mutex()
    private val recordingMutex = Mutex()
    private val inventoryLock = Any()

    // --- camera-thread state ---
    private var started = false
    private var requested = CameraSettings()
    @Volatile private var effective: CameraSettings? = null
    private var activeCaps: CameraCapabilities? = null
    private var configuredCameraId: String? = null
    private var configuredStream: VideoSize? = null
    private var configuredFps = 0
    private var configuredStabilization: StabilizationMode? = null
    private var pendingLensZoom: Float? = null
    private var focusMode = FocusMode.CONTINUOUS
    private var afRegion: SensorRect? = null
    private var aeRegion: SensorRect? = null
    private var aeLocked = false
    private var retryAttempts = 0
    private var configFallbackUsed = false
    private var lastLivePublishMs = 0L
    private var lastAutoIso = 0
    private var lastAutoExposureNs = 0L
    private var lastColorTransform: ColorSpaceTransform? = null
    private val retryRunnable = Runnable { reopenAfterError() }

    // --- cross-thread ---
    @Volatile private var renderer: CameraRenderer? = null
    @Volatile private var cameraSurface: Surface? = null
    private val previewLock = Any()
    private var previewTarget: PreviewTarget? = null
    @Volatile private var processors: List<GlFrameProcessor> = emptyList()
    @Volatile private var deviceOrientation = 0

    // --- recording (guarded by recordingMutex) ---
    @Volatile private var recorder: VideoRecorder? = null
    @Volatile private var audioHandle: AudioRecordingHandle? = null
    @Volatile private var audioOnlyStartedAt = 0L
    @Volatile private var recordingConfig: ActiveCameraConfig? = null
    private var monitorJob: Job? = null
    @Volatile private var warnedHot = false
    @Volatile private var warnedLowStorage = false

    private data class PreviewTarget(val surface: Surface, val width: Int, val height: Int)

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            val st = _state.value
            if (started && st is CameraState.Error && st.retrying && cameraId == requestedCameraId()) {
                cameraHandler.removeCallbacks(retryRunnable)
                cameraHandler.post(retryRunnable)
            }
        }
    }

    private val recorderEvents = object : VideoRecorder.Events {
        override fun onRecorderFailure(error: Exception?) {
            RgLog.e(TAG, "recorder failure; stopping safely", error)
            _events.tryEmit(CameraEvent.Warning(CameraWarning.STOPPED_ENCODER_ERROR))
            commandScope.launch { stopRecording(StopReason.ENCODER_ERROR) }
        }

        override fun onRecorderWarning(warning: CameraWarning) {
            _events.tryEmit(CameraEvent.Warning(warning))
        }
    }

    init {
        appScope.launch { thermalMonitor.levels.collect { onThermal(it) } }
    }

    private val tier get() = deviceProfiler.profile.tier

    // =========================================================================================================
    // Capabilities
    // =========================================================================================================

    private fun ensureInventory(force: Boolean = false): CameraInventory = synchronized(inventoryLock) {
        val current = _inventory.value
        if (current != null && !force) return current
        detector.detectAll().also { _inventory.value = it }
    }

    override suspend fun detectCameras(forceRefresh: Boolean): CameraInventory = withContext(io) { ensureInventory(forceRefresh) }

    override fun validate(settings: CameraSettings): CameraSettings {
        val inv = _inventory.value ?: return settings
        val caps = resolveCamera(inv, settings) ?: return settings
        return VideoModeSelector.validate(settings, caps, tier)
    }

    private fun resolveCamera(inv: CameraInventory, settings: CameraSettings): CameraCapabilities? =
        settings.cameraId?.let { inv.camera(it) }?.takeIf { it.facing == settings.lensFacing }
            ?: inv.main(settings.lensFacing)
            ?: inv.main(if (settings.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT)
            ?: inv.cameras.firstOrNull()

    private fun requestedCameraId(): String? = _inventory.value?.let { resolveCamera(it, requested)?.cameraId }

    // =========================================================================================================
    // Lifecycle commands
    // =========================================================================================================

    override fun start(settings: CameraSettings) = command {
        onCamera {
            requested = settings
            if (!started) {
                started = true
                retryAttempts = 0
                runCatching { manager?.registerAvailabilityCallback(availability, cameraHandler) }
            }
            configure()
        }
    }

    override fun stop() = command {
        stopRecording(StopReason.LIFECYCLE)
        onCamera { closeInternal() }
    }

    override fun updateSettings(settings: CameraSettings) = command {
        onCamera {
            requested = settings
            if (started) configure()
        }
    }

    override fun retry() = command {
        onCamera {
            retryAttempts = 0
            configFallbackUsed = false
            cameraHandler.removeCallbacks(retryRunnable)
            if (started) {
                session?.close()
                configuredCameraId = null
                configure()
            }
        }
    }

    override fun selectLens(option: LensOption) = command {
        onCamera {
            val caps = activeCaps
            if (caps != null && caps.cameraId == option.cameraId) {
                _controls.update { it.copy(zoomRatio = option.zoomRatio.coerceIn(caps.zoomRange)) }
                pushRequest()
            } else if (!_recording.value.isActive) {
                requested = requested.copy(cameraId = option.cameraId, lensFacing = option.facing)
                pendingLensZoom = option.zoomRatio
                if (started) configure()
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        commandScope.launch {
            commandMutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RgLog.e(TAG, "camera command failed", e)
                }
            }
        }
    }

    private suspend fun <T> onCamera(block: () -> T): T = suspendCancellableCoroutine { cont ->
        cameraHandler.post {
            try {
                cont.resume(block())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    /** Camera thread. Opens, reconfigures or updates the session to match [requested]. */
    private fun configure() {
        val cameraSession = session ?: return setError(CameraErrorKind.NO_CAMERA, "camera service unavailable", recoverable = false)
        val inv = try {
            ensureInventory()
        } catch (e: Exception) {
            RgLog.e(TAG, "camera detection failed", e)
            return setError(CameraErrorKind.DEVICE, e.message, recoverable = true)
        }
        if (inv.isEmpty) return setError(CameraErrorKind.NO_CAMERA, null, recoverable = false)
        val recordingCfg = recordingConfig
        var caps = resolveCamera(inv, requested) ?: return setError(CameraErrorKind.NO_CAMERA, null, recoverable = false)
        if (recordingCfg != null && caps.cameraId != recordingCfg.cameraId) {
            // Never switch cameras under a running recording.
            caps = inv.camera(recordingCfg.cameraId) ?: caps
        }
        var validated = VideoModeSelector.validate(requested, caps, tier)
        if (recordingCfg != null) {
            validated = validated.copy(
                resolution = recordingCfg.resolution,
                frameRate = recordingCfg.frameRate,
                aspectRatio = recordingCfg.aspectRatio,
                codec = recordingCfg.codec,
                stabilization = recordingCfg.stabilization,
                mirrorFrontRecording = recordingCfg.mirrorRecording,
            )
        }
        if (caps.cameraId != activeCaps?.cameraId) {
            val zoom = (pendingLensZoom ?: 1f).coerceIn(caps.zoomRange)
            _controls.value = ManualControls(zoomRatio = zoom, kelvin = _controls.value.kelvin)
            focusMode = if (caps.autoFocus) FocusMode.CONTINUOUS else FocusMode.FIXED
            afRegion = null
            aeRegion = null
            aeLocked = false
            _focus.value = FocusState(mode = focusMode)
        }
        pendingLensZoom = null
        activeCaps = caps
        effective = validated
        _capabilities.value = caps
        _settings.value = validated

        val r = renderer ?: createRenderer() ?: return
        val surface = cameraSurface ?: return
        val stream = VideoModeSelector.streamSizeFor(validated.resolution, caps.streamSizes)
        val out = VideoModeSelector.outputSize(validated.resolution, validated.aspectRatio)
        r.setGeometry(
            RenderGeometry(
                bufferWidth = stream.width,
                bufferHeight = stream.height,
                sensorOrientation = caps.sensorOrientation,
                facing = caps.facing,
                outputWidth = out.width,
                outputHeight = out.height,
                mirror = caps.facing == LensFacing.FRONT,
                mirrorRecording = validated.mirrorFrontRecording,
                timestampRealtime = caps.timestampRealtime,
                fps = validated.frameRate,
            ),
        )
        r.budgetScale = budgetScale(_thermal.value)

        val params = buildParams()
        when {
            !cameraSession.isOpen || configuredCameraId != caps.cameraId -> {
                r.setBufferSize(stream.width, stream.height)
                rememberConfigured(caps.cameraId, stream, validated)
                if (_state.value !is CameraState.Error) _state.value = CameraState.Opening
                cameraSession.open(caps.cameraId, surface, params)
            }
            stream != configuredStream || validated.frameRate != configuredFps || validated.stabilization != configuredStabilization -> {
                r.setBufferSize(stream.width, stream.height)
                rememberConfigured(caps.cameraId, stream, validated)
                cameraSession.update(params)
                cameraSession.reconfigure()
            }
            else -> {
                cameraSession.update(params)
                if (_state.value is CameraState.Streaming) _state.value = CameraState.Streaming(activeConfig())
            }
        }
    }

    private fun rememberConfigured(cameraId: String, stream: VideoSize, settings: CameraSettings) {
        configuredCameraId = cameraId
        configuredStream = stream
        configuredFps = settings.frameRate
        configuredStabilization = settings.stabilization
    }

    private fun activeConfig(): ActiveCameraConfig {
        val caps = activeCaps!!
        val s = effective!!
        val stream = configuredStream ?: VideoModeSelector.streamSizeFor(s.resolution, caps.streamSizes)
        return ActiveCameraConfig(
            cameraId = caps.cameraId,
            facing = caps.facing,
            streamSize = stream,
            resolution = s.resolution,
            output = VideoModeSelector.outputSize(s.resolution, s.aspectRatio),
            frameRate = s.frameRate,
            codec = s.codec,
            aspectRatio = s.aspectRatio,
            stabilization = s.stabilization,
            hdr = s.hdr,
            mirrorPreview = caps.facing == LensFacing.FRONT,
            mirrorRecording = caps.facing == LensFacing.FRONT && s.mirrorFrontRecording,
        )
    }

    private fun createRenderer(): CameraRenderer? {
        val r = CameraRenderer(rendererCallbacks)
        val surface = try {
            r.start()
        } catch (t: Throwable) {
            RgLog.e(TAG, "GL pipeline failed to start", t)
            runCatching { r.release() }
            setError(CameraErrorKind.DEVICE, t.message, recoverable = false)
            return null
        }
        cameraSurface = surface
        renderer = r
        r.setDeviceOrientation(deviceOrientation)
        r.setFrameProcessors(processors)
        synchronized(previewLock) { previewTarget }?.let { r.setPreviewSurface(it.surface, it.width, it.height) }
        return r
    }

    private fun closeInternal() {
        started = false
        cameraHandler.removeCallbacks(retryRunnable)
        runCatching { manager?.unregisterAvailabilityCallback(availability) }
        session?.close()
        configuredCameraId = null
        configuredStream = null
        renderer?.release()
        renderer = null
        cameraSurface = null
        _state.value = CameraState.Closed
        _previewFrame.value = null
    }

    private fun setError(kind: CameraErrorKind, message: String?, recoverable: Boolean) {
        _state.value = CameraState.Error(kind, message, retrying = recoverable && started)
        if (recoverable && started) scheduleRetry()
    }

    private fun scheduleRetry() {
        retryAttempts++
        if (retryAttempts > MAX_RETRIES) {
            val st = _state.value
            if (st is CameraState.Error) _state.value = st.copy(retrying = false)
            return
        }
        val delayMs = minOf(8_000L, 500L shl retryAttempts)
        cameraHandler.removeCallbacks(retryRunnable)
        cameraHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun reopenAfterError() {
        if (!started || _state.value !is CameraState.Error) return
        configuredCameraId = null
        configure()
    }

    // =========================================================================================================
    // CameraSession.Listener (camera thread)
    // =========================================================================================================

    private fun handleStreaming() {
        if (!started) return
        retryAttempts = 0
        _state.value = CameraState.Streaming(activeConfig())
    }

    private fun handleCameraError(kind: CameraErrorKind, message: String?, recoverable: Boolean) {
        if (!started) return
        configuredCameraId = null
        val rec = _recording.value
        if (rec.isActive && rec.captureMode != CaptureMode.AUDIO_ONLY) {
            _events.tryEmit(CameraEvent.Warning(CameraWarning.STOPPED_CAMERA_ERROR))
            commandScope.launch { stopRecording(StopReason.CAMERA_ERROR) }
        }
        if (kind == CameraErrorKind.CONFIGURATION && !configFallbackUsed) {
            configFallbackUsed = true
            val eff = effective
            if (eff != null && (eff.resolution.shortSide > 1080 || eff.frameRate > 30 || eff.stabilization == StabilizationMode.PREVIEW_OPTIMIZED)) {
                RgLog.w(TAG, "session rejected ${eff.resolution.label}@${eff.frameRate}; falling back to 1080p30")
                requested = requested.copy(
                    resolution = VideoSize(1920, 1080),
                    frameRate = 30,
                    stabilization = if (eff.stabilization == StabilizationMode.PREVIEW_OPTIMIZED) StabilizationMode.STANDARD else eff.stabilization,
                )
                session?.close()
                configure()
                return
            }
        }
        session?.close()
        setError(kind, message, recoverable)
    }

    private fun handleResult(result: CameraSession.CaptureSnapshot) {
        val c = _controls.value
        if (c.iso == null && c.shutterNs == null) {
            if (result.iso > 0) lastAutoIso = result.iso
            if (result.exposureNs > 0) lastAutoExposureNs = result.exposureNs
        }
        if (c.whiteBalance != WhiteBalanceMode.MANUAL_KELVIN) result.colorTransform?.let { lastColorTransform = it }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLivePublishMs < LIVE_PUBLISH_MS) return
        lastLivePublishMs = now
        val live = LiveExposure(
            iso = result.iso.takeIf { it > 0 },
            exposureTimeNs = result.exposureNs.takeIf { it > 0 },
            focusDistanceDiopters = result.focusDistance,
        )
        if (live != _liveExposure.value) _liveExposure.value = live
        val af = when (result.afState) {
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> AfStatus.SCANNING
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED, CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> AfStatus.FOCUSED
            CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> AfStatus.FAILED
            else -> AfStatus.INACTIVE
        }
        if (_focus.value.afStatus != af) _focus.update { it.copy(afStatus = af) }
    }

    // =========================================================================================================
    // Preview surface, orientation, processors
    // =========================================================================================================

    override fun setPreviewSurface(surface: Surface, width: Int, height: Int) {
        synchronized(previewLock) { previewTarget = PreviewTarget(surface, width, height) }
        renderer?.setPreviewSurface(surface, width, height)
    }

    override fun clearPreviewSurface(surface: Surface) {
        synchronized(previewLock) { if (previewTarget?.surface === surface) previewTarget = null }
        renderer?.clearPreviewSurface(surface)
    }

    override fun setDeviceOrientation(degrees: Int) {
        val snapped = FrameGeometry.snap(degrees)
        deviceOrientation = snapped
        renderer?.setDeviceOrientation(snapped)
    }

    override fun setFrameProcessors(processors: List<GlFrameProcessor>) {
        this.processors = processors.toList()
        renderer?.setFrameProcessors(this.processors)
    }

    // =========================================================================================================
    // Manual controls (camera thread)
    // =========================================================================================================

    private fun control(block: (CameraCapabilities) -> Unit) {
        cameraHandler.post {
            val caps = activeCaps ?: return@post
            block(caps)
            pushRequest()
        }
    }

    private fun pushRequest() {
        val s = session ?: return
        if (s.isStreaming) s.update(buildParams())
    }

    override fun setZoom(ratio: Float) = control { caps ->
        _controls.update { it.copy(zoomRatio = ratio.coerceIn(caps.zoomRange)) }
    }

    override fun focusAndMeter(x: Float, y: Float) {
        cameraHandler.post {
            val caps = activeCaps ?: return@post
            val mapping = renderer?.mapping?.get() ?: return@post
            val stream = configuredStream ?: return@post
            if (!caps.tapToFocus && !caps.tapToMeter) return@post
            val (bx, by) = FrameGeometry.displayPointToBuffer(
                x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), mapping.previewRotationCw, mapping.rotationCw, mapping.crop, mapping.mirror,
            )
            val zoom = _controls.value.zoomRatio
            val crop = if (caps.zoomRatioApi) null else MeteringMath.cropRegionForZoom(caps.activeArray, zoom)
            val fov = MeteringMath.streamFieldOfView(caps.activeArray, crop, stream.width.toFloat() / stream.height)
            val rect = MeteringMath.meteringRect(bx, by, fov, caps.activeArray)
            afRegion = if (caps.maxAfRegions > 0) rect else null
            aeRegion = if (caps.maxAeRegions > 0) rect else null
            aeLocked = false
            val manualFocus = _controls.value.focusDistanceDiopters != null
            if (caps.tapToFocus && !manualFocus) focusMode = FocusMode.POINT
            _controls.update { it.copy(aeAfLocked = false) }
            _focus.value = FocusState(mode = focusMode, pointX = x, pointY = y, afStatus = AfStatus.SCANNING, aeLocked = false)
            pushRequest()
            if (focusMode == FocusMode.POINT) session?.triggerAutoFocus()
        }
    }

    override fun resetFocusAndMetering() = control { caps ->
        afRegion = null
        aeRegion = null
        aeLocked = false
        focusMode = when {
            _controls.value.focusDistanceDiopters != null -> FocusMode.MANUAL
            caps.autoFocus -> FocusMode.CONTINUOUS
            else -> FocusMode.FIXED
        }
        _controls.update { it.copy(aeAfLocked = false) }
        _focus.value = FocusState(mode = focusMode)
    }

    override fun setAeAfLock(locked: Boolean) {
        cameraHandler.post {
            val caps = activeCaps ?: return@post
            aeLocked = locked && caps.aeLockAvailable
            var trigger = false
            if (locked) {
                if (caps.autoFocus && _controls.value.focusDistanceDiopters == null) {
                    trigger = focusMode == FocusMode.CONTINUOUS
                    focusMode = FocusMode.LOCKED
                }
            } else if (focusMode == FocusMode.LOCKED) {
                focusMode = if (afRegion != null) FocusMode.POINT else FocusMode.CONTINUOUS
            }
            _controls.update { it.copy(aeAfLocked = locked) }
            _focus.update { it.copy(mode = focusMode, aeLocked = locked) }
            pushRequest()
            if (trigger) session?.triggerAutoFocus()
        }
    }

    override fun setExposureCompensation(index: Int) = control { caps ->
        _controls.update { it.copy(exposureCompensation = index.coerceIn(caps.exposureCompensationRange)) }
    }

    override fun setIso(iso: Int?) = control { caps ->
        val range = caps.isoRange
        _controls.update { it.copy(iso = if (range != null) iso?.coerceIn(range) else null) }
    }

    override fun setShutter(exposureTimeNs: Long?) = control { caps ->
        val range = caps.exposureTimeRangeNs
        _controls.update { it.copy(shutterNs = if (range != null) exposureTimeNs?.coerceIn(range) else null) }
    }

    override fun setFocusDistance(diopters: Float?) = control { caps ->
        val value = if (caps.manualFocus) diopters?.coerceIn(0f, caps.minFocusDistanceDiopters) else null
        _controls.update { it.copy(focusDistanceDiopters = value) }
        focusMode = when {
            value != null -> FocusMode.MANUAL
            caps.autoFocus -> FocusMode.CONTINUOUS
            else -> FocusMode.FIXED
        }
        afRegion = null
        _focus.update { it.copy(mode = focusMode, pointX = null, pointY = null) }
    }

    override fun setWhiteBalance(mode: WhiteBalanceMode, kelvin: Int) = control { caps ->
        val m = if (mode in caps.whiteBalanceModes) mode else WhiteBalanceMode.AUTO
        _controls.update { it.copy(whiteBalance = m, kelvin = kelvin.coerceIn(WhiteBalanceMath.MIN_KELVIN, WhiteBalanceMath.MAX_KELVIN)) }
    }

    override fun resetManualControls() = control { caps ->
        _controls.value = ManualControls(zoomRatio = _controls.value.zoomRatio, kelvin = _controls.value.kelvin)
        afRegion = null
        aeRegion = null
        aeLocked = false
        focusMode = if (caps.autoFocus) FocusMode.CONTINUOUS else FocusMode.FIXED
        _focus.value = FocusState(mode = focusMode)
    }

    /** Camera thread. Resolves settings + controls + 3A state into the repeating request. */
    private fun buildParams(): RequestParams {
        val caps = activeCaps ?: return RequestParams()
        val s = effective ?: return RequestParams()
        val c = _controls.value
        val fps = s.frameRate.coerceAtLeast(1)
        val frameDuration = 1_000_000_000L / fps
        val isoRange = caps.isoRange
        val expRange = caps.exposureTimeRangeNs
        val manual = caps.manualExposure && (c.iso != null || c.shutterNs != null) && isoRange != null && expRange != null
        val iso = if (manual) (c.iso ?: lastAutoIso.takeIf { it > 0 } ?: maxOf(100, isoRange!!.first)).coerceIn(isoRange!!) else null
        val exposure = if (manual) (c.shutterNs ?: lastAutoExposureNs.takeIf { it > 0 } ?: (frameDuration / 2)).coerceIn(expRange!!) else null
        val zoom = c.zoomRatio.coerceIn(caps.zoomRange)
        val afMode = when {
            c.focusDistanceDiopters != null && caps.manualFocus -> AfRequestMode.OFF
            !caps.autoFocus -> AfRequestMode.NONE
            focusMode == FocusMode.POINT || focusMode == FocusMode.LOCKED -> AfRequestMode.AUTO
            else -> AfRequestMode.CONTINUOUS
        }
        val kelvin = c.whiteBalance == WhiteBalanceMode.MANUAL_KELVIN && caps.manualKelvin
        val awbMode = com.ravango.engine.camera.capability.CapabilityDetector.WB_MAPPING
            .firstOrNull { it.first == c.whiteBalance && it.first in caps.whiteBalanceModes }?.second
            ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        return RequestParams(
            fpsRange = VideoModeSelector.aeRangeFor(fps, caps.fpsRanges),
            stabilization = s.stabilization,
            eisAvailable = caps.electronicStabilization,
            oisAvailable = caps.opticalStabilization,
            hdrScene = s.hdr && HdrOption.SCENE_MODE in caps.hdrOptions,
            torch = s.flash == FlashMode.TORCH && caps.flashAvailable,
            zoomRatio = zoom,
            zoomRatioApi = caps.zoomRatioApi,
            cropRegion = if (!caps.zoomRatioApi && zoom > 1.001f) MeteringMath.cropRegionForZoom(caps.activeArray, zoom) else null,
            afMode = afMode,
            focusDistance = c.focusDistanceDiopters?.coerceIn(0f, caps.minFocusDistanceDiopters) ?: 0f,
            afRegion = if (caps.maxAfRegions > 0) afRegion else null,
            aeRegion = if (caps.maxAeRegions > 0) aeRegion else null,
            aeLock = aeLocked,
            aeLockAvailable = caps.aeLockAvailable,
            evIndex = c.exposureCompensation.coerceIn(caps.exposureCompensationRange),
            manualIso = iso,
            manualExposureNs = exposure,
            frameDurationNs = frameDuration,
            awbMode = awbMode,
            awbGains = if (kelvin) WhiteBalanceMath.rggbGains(c.kelvin) else null,
            colorTransform = if (kelvin) lastColorTransform else null,
        )
    }

    // =========================================================================================================
    // Thermal
    // =========================================================================================================

    private fun budgetScale(level: ThermalLevel): Float = when (level) {
        ThermalLevel.NORMAL -> 1f
        ThermalLevel.WARM -> 0.85f
        ThermalLevel.HOT -> 0.6f
        ThermalLevel.CRITICAL -> 0.4f
    }

    private fun onThermal(level: ThermalLevel) {
        val previous = _thermal.value
        _thermal.value = level
        renderer?.budgetScale = budgetScale(level)
        val rec = _recording.value
        when (level) {
            ThermalLevel.CRITICAL -> if (rec.isActive && rec.captureMode != CaptureMode.AUDIO_ONLY) {
                RgLog.w(TAG, "thermal critical; stopping recording gracefully")
                _events.tryEmit(CameraEvent.Warning(CameraWarning.STOPPED_THERMAL))
                commandScope.launch { stopRecording(StopReason.THERMAL) }
            }
            ThermalLevel.HOT -> if (rec.isActive && !warnedHot) {
                warnedHot = true
                _events.tryEmit(CameraEvent.Warning(CameraWarning.THERMAL_HOT))
            }
            ThermalLevel.WARM -> if (previous == ThermalLevel.NORMAL) _events.tryEmit(CameraEvent.Warning(CameraWarning.THERMAL_WARM))
            ThermalLevel.NORMAL -> Unit
        }
    }

    // =========================================================================================================
    // Recording
    // =========================================================================================================

    override fun estimateRecordableSeconds(settings: CameraSettings, withAudio: Boolean): Long {
        val out = VideoModeSelector.outputSize(settings.resolution, settings.aspectRatio)
        val video = BitrateCalculator.videoBitrate(out.width, out.height, settings.frameRate, settings.codec, settings.bitrateProfile)
        val audio = if (withAudio) BitrateCalculator.audioBitrate(DEFAULT_AUDIO_KBPS) else 0
        return storage.snapshot(storage.recordingsDir).recordableSeconds(BitrateCalculator.totalBitsPerSecond(video, audio))
    }

    override suspend fun startRecording(mode: CaptureMode, audioBitrateKbps: Int): Outcome<Unit> = recordingMutex.withLock {
        if (_recording.value.isActive || _recording.value.phase == RecordingPhase.FINALIZING) {
            return@withLock Outcome.Failure(ErrorKind.INVALID_INPUT, "already recording")
        }
        try {
            if (mode == CaptureMode.AUDIO_ONLY) startAudioOnly(audioBitrateKbps) else startVideo(mode, audioBitrateKbps)
        } catch (e: CancellationException) {
            _recording.value = RecordingStatus()
            throw e
        } catch (e: Exception) {
            RgLog.e(TAG, "startRecording failed", e)
            _recording.value = RecordingStatus()
            Outcome.Failure(ErrorKind.UNKNOWN, e.message, e)
        }
    }

    private suspend fun startVideo(mode: CaptureMode, audioKbps: Int): Outcome<Unit> {
        val config = (_state.value as? CameraState.Streaming)?.config
            ?: return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "camera not ready")
        val r = renderer ?: return Outcome.Failure(ErrorKind.NOT_SUPPORTED, "pipeline not ready")
        val out = config.output
        val profile = effective?.bitrateProfile ?: BitrateProfile.HIGH
        val withAudio = mode == CaptureMode.VIDEO_WITH_AUDIO
        val audioBps = if (withAudio) BitrateCalculator.audioBitrate(audioKbps) else 0
        var codec = config.codec
        var videoBps = BitrateCalculator.videoBitrate(out.width, out.height, config.frameRate, codec, profile, encoders.bitrateRange(codec))
        val snapshot = withContext(io) { storage.snapshot(storage.recordingsDir) }
        if (snapshot.totalBytes > 0 && snapshot.recordableSeconds(BitrateCalculator.totalBitsPerSecond(videoBps, audioBps)) < MIN_START_SECONDS) {
            return Outcome.Failure(ErrorKind.STORAGE_FULL)
        }
        _recording.value = RecordingStatus(
            phase = RecordingPhase.STARTING,
            captureMode = mode,
            videoBitrate = videoBps,
            audioBitrate = audioBps,
            output = out,
            frameRate = config.frameRate,
        )
        recordingConfig = config
        val rec = withContext(io) {
            val id = newId()
            val dir = File(storage.recordingsDir, RecordingRecovery.DIR_PREFIX + id).apply { mkdirs() }
            val name = "RavanGo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            ActiveRecordings.add(id)
            fun create(c: VideoCodec, bps: Int): Pair<VideoRecorder, Surface> {
                val manifest = RecordingManifest(
                    id = id, createdAt = System.currentTimeMillis(), finalName = name, captureMode = mode,
                    width = out.width, height = out.height, frameRate = config.frameRate, videoMime = c.mimeType, hasAudio = withAudio,
                )
                val encoderName = encoders.encoderFor(c, out.width, out.height, config.frameRate)?.name
                val recorder = VideoRecorder(
                    dir, manifest, VideoEncoderConfig(c, out.width, out.height, config.frameRate, bps, encoderName),
                    audioBps, withAudio, audioEngine, recorderEvents,
                )
                return try {
                    recorder to recorder.start()
                } catch (e: Exception) {
                    recorder.stop()
                    throw e
                }
            }
            val (recorder, surface) = try {
                create(codec, videoBps)
            } catch (e: Exception) {
                if (codec == VideoCodec.HEVC) {
                    RgLog.w(TAG, "HEVC encoder unavailable; falling back to H.264", e)
                    codec = VideoCodec.H264
                    videoBps = BitrateCalculator.videoBitrate(out.width, out.height, config.frameRate, codec, profile, encoders.bitrateRange(codec))
                    create(codec, videoBps)
                } else {
                    dir.deleteRecursively()
                    ActiveRecordings.remove(id)
                    throw e
                }
            }
            if (!r.startEncoding(surface, out.width, out.height, recorder.clock)) {
                recorder.stop()
                dir.deleteRecursively()
                ActiveRecordings.remove(id)
                throw IllegalStateException("could not attach the encoder surface")
            }
            crashGuard.beginSection(RecordingRecovery.SECTION, dir.absolutePath)
            recorder
        }
        recorder = rec
        warnedHot = false
        warnedLowStorage = false
        _recording.update { it.copy(phase = RecordingPhase.RECORDING, videoBitrate = videoBps) }
        startMonitor()
        RgLog.i(TAG, "recording ${out.width}x${out.height}@${config.frameRate} $codec ${videoBps / 1000}kbps audio=$withAudio")
        return Outcome.Success(Unit)
    }

    private suspend fun startAudioOnly(audioKbps: Int): Outcome<Unit> {
        val audioBps = BitrateCalculator.audioBitrate(audioKbps)
        val snapshot = withContext(io) { storage.snapshot(storage.recordingsDir) }
        if (snapshot.totalBytes > 0 && snapshot.recordableSeconds(audioBps.toLong()) < MIN_START_SECONDS) {
            return Outcome.Failure(ErrorKind.STORAGE_FULL)
        }
        val name = "RavanGo_audio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val file = File(storage.recordingsDir, name)
        val handle = withContext(io) { audioEngine.startAudioOnlyRecording(file) }
        audioHandle = handle
        audioOnlyStartedAt = System.currentTimeMillis()
        warnedLowStorage = false
        _recording.value = RecordingStatus(phase = RecordingPhase.RECORDING, captureMode = CaptureMode.AUDIO_ONLY, audioBitrate = audioBps)
        startMonitor()
        return Outcome.Success(Unit)
    }

    override fun pauseRecording() {
        if (_recording.value.phase != RecordingPhase.RECORDING) return
        recorder?.pause() ?: audioHandle?.pause()
        _recording.update { it.copy(phase = RecordingPhase.PAUSED) }
    }

    override fun resumeRecording() {
        if (_recording.value.phase != RecordingPhase.PAUSED) return
        recorder?.resume() ?: audioHandle?.resume()
        _recording.update { it.copy(phase = RecordingPhase.RECORDING) }
    }

    override suspend fun stopRecording(reason: StopReason): RecordedMedia? = withContext(NonCancellable) {
        recordingMutex.withLock { stopLocked(reason) }
    }

    private suspend fun stopLocked(reason: StopReason): RecordedMedia? {
        val status = _recording.value
        if (!status.isActive) return null
        _recording.update { it.copy(phase = RecordingPhase.FINALIZING) }
        monitorJob?.cancel()
        monitorJob = null
        val media = withContext(io) {
            val audio = audioHandle
            val video = recorder
            when {
                audio != null -> finishAudioOnly(audio, reason)
                video != null -> finishVideo(video, status, reason)
                else -> null
            }
        }
        recorder = null
        audioHandle = null
        recordingConfig = null
        _recording.value = RecordingStatus()
        if (media != null) {
            _events.tryEmit(CameraEvent.RecordingFinished(media))
        } else {
            _events.tryEmit(CameraEvent.RecordingFailed(ErrorKind.UNKNOWN, "nothing was recorded"))
        }
        // A camera switch requested during recording can apply now.
        if (reason == StopReason.USER) command { onCamera { if (started) configure() } }
        return media
    }

    private fun finishVideo(rec: VideoRecorder, status: RecordingStatus, reason: StopReason): RecordedMedia? {
        renderer?.stopEncoding()
        rec.stop()
        val manifest = rec.manifest
        val finalized = RecordingFinalizer.finalize(rec.dir, manifest, storage.recordingsDir)
        ActiveRecordings.remove(manifest.id)
        if (finalized == null) {
            // Segments (if any) were kept for recovery; only clear the marker when nothing is left to recover.
            if (!rec.dir.exists()) crashGuard.endSection(RecordingRecovery.SECTION)
            return null
        }
        crashGuard.endSection(RecordingRecovery.SECTION)
        return RecordedMedia(
            file = finalized.file,
            captureMode = if (manifest.hasAudio) status.captureMode else CaptureMode.VIDEO_ONLY,
            mimeType = "video/mp4",
            durationUs = finalized.durationUs,
            width = manifest.width,
            height = manifest.height,
            frameRate = manifest.frameRate,
            sizeBytes = finalized.file.length(),
            hasAudio = manifest.hasAudio,
            aspectRatio = recordingConfig?.aspectRatio,
            stopReason = reason,
        )
    }

    private suspend fun finishAudioOnly(handle: AudioRecordingHandle, reason: StopReason): RecordedMedia? {
        val file = runCatching { handle.stop() }.onFailure { RgLog.e(TAG, "audio-only stop failed", it) }.getOrNull() ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return RecordedMedia(
            file = file,
            captureMode = CaptureMode.AUDIO_ONLY,
            mimeType = "audio/mp4",
            durationUs = handle.durationUs.value,
            width = 0,
            height = 0,
            frameRate = 0,
            sizeBytes = file.length(),
            hasAudio = true,
            aspectRatio = null,
            stopReason = reason,
            createdAt = audioOnlyStartedAt,
        )
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = appScope.launch(io) {
            var snapshot = storage.snapshot(storage.recordingsDir)
            var lastCheck = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                val status = _recording.value
                if (status.phase != RecordingPhase.RECORDING && status.phase != RecordingPhase.PAUSED) break
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck >= STORAGE_CHECK_MS) {
                    snapshot = storage.snapshot(storage.recordingsDir)
                    lastCheck = now
                }
                val rec = recorder
                val audio = audioHandle
                val duration = rec?.durationUs ?: audio?.durationUs?.value ?: 0L
                val bytes = rec?.bytesWritten ?: audio?.file?.length() ?: 0L
                val bps = BitrateCalculator.totalBitsPerSecond(status.videoBitrate, status.audioBitrate)
                val remaining = if (snapshot.totalBytes > 0) snapshot.recordableSeconds(bps) else Long.MAX_VALUE
                _recording.update { if (it.isActive) it.copy(durationUs = duration, bytesWritten = bytes, remainingSeconds = remaining) else it }
                if (snapshot.totalBytes > 0 && snapshot.availableBytes < StorageSnapshot.RESERVE_BYTES) {
                    RgLog.w(TAG, "storage below reserve; stopping recording")
                    _events.tryEmit(CameraEvent.Warning(CameraWarning.STOPPED_LOW_STORAGE))
                    commandScope.launch { stopRecording(StopReason.LOW_STORAGE) }
                    break
                }
                if (remaining < LOW_STORAGE_WARN_SECONDS && !warnedLowStorage) {
                    warnedLowStorage = true
                    _events.tryEmit(CameraEvent.Warning(CameraWarning.LOW_STORAGE))
                }
            }
        }
    }

    private companion object {
        const val TAG = "CameraEngine"
        const val MAX_RETRIES = 6
        const val LIVE_PUBLISH_MS = 250L
        const val MONITOR_INTERVAL_MS = 250L
        const val STORAGE_CHECK_MS = 2_000L
        const val MIN_START_SECONDS = 10L
        const val LOW_STORAGE_WARN_SECONDS = 120L
        const val DEFAULT_AUDIO_KBPS = 192
    }
}
