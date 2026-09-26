package com.ravango.feature.camera

import android.net.Uri
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.device.StorageInfo
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.data.repository.TemplateRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.AudioSettings
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.Entitlements
import com.ravango.core.model.LensFacing
import com.ravango.core.model.ProFeature
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.CameraRoute
import com.ravango.engine.audio.AudioEngine
import com.ravango.engine.beauty.BeautyEngine
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.CameraEffects
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.camera.CameraEngine
import com.ravango.engine.camera.CameraEvent
import com.ravango.engine.camera.RecordingPhase
import com.ravango.engine.camera.RecordingStatus
import com.ravango.engine.camera.StopReason
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.encoder.BitrateCalculator
import com.ravango.engine.camera.recovery.RecordingRecovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: CameraEngine,
    private val audioEngine: AudioEngine,
    private val beautyEngine: BeautyEngine,
    private val cameraEffects: CameraEffects,
    private val preferences: PreferencesDataSource,
    private val projects: ProjectRepository,
    private val scripts: ScriptRepository,
    private val templates: TemplateRepository,
    private val entitlementProvider: EntitlementProvider,
    private val saver: RecordingSaver,
    private val recovery: RecordingRecovery,
    private val storage: StorageInfo,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val args = savedStateHandle.toRoute<CameraRoute>()

    private val _state = MutableStateFlow(CameraUiState(mode = if (args.audioOnly) StudioMode.AUDIO else StudioMode.VIDEO))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    /** ~30 Hz; read it in draw lambdas only. */
    val audioLevel: StateFlow<AudioLevel> = audioEngine.level

    /**
     * The live recording clock (duration, bytes, remaining time). Kept out of [state] on purpose: only the recording
     * HUD reads it, so the rest of the studio does not recompose on every tick.
     */
    val recordingClock: StateFlow<RecordingStatus> = engine.recording

    private val _messages = Channel<StudioMessage>(Channel.BUFFERED)
    val messages: Flow<StudioMessage> = _messages.receiveAsFlow()

    private val scriptId = MutableStateFlow(args.scriptId)
    private val prompterVisible = MutableStateFlow(true)

    private var foreground = false
    private var cameraGranted = false
    private var micGranted = false
    private var cameraRunning = false
    private var audioPreviewRunning = false
    private var countdownJob: Job? = null
    private var storageJob: Job? = null

    init {
        bindPreviewBypass(beautyEngine.previewBypass)
        saver.target = SaveTarget(args.projectId, args.scriptId, args.templateId, _state.value.settings.aspectRatio)
        viewModelScope.launch {
            saver.events.collect { e ->
                _state.update {
                    it.copy(
                        postRecord = when (e) {
                            SaveEvent.Saving -> PostRecordState.Saving
                            is SaveEvent.Saved -> PostRecordState.Saved(e.take)
                            is SaveEvent.Failed -> PostRecordState.Failed(e.kind)
                        },
                    )
                }
            }
        }
        viewModelScope.launch { initialize() }
        collectEngine()
        collectAudio()
        collectPrompter()
        viewModelScope.launch {
            entitlementProvider.entitlements.collect { e ->
                _state.update { it.copy(entitlements = e) }
                reapplyEffectsGating(e)
                if (_state.value.initialized) reapplyGating(e)
            }
        }
        viewModelScope.launch {
            projects.observeRecent(1).collect { list ->
                val p = list.firstOrNull()
                _state.update { it.copy(lastProjectId = p?.id, lastThumbnailPath = p?.thumbnailPath) }
            }
        }
        viewModelScope.launch { importRecovered() }
    }

    private suspend fun initialize() {
        var settings = preferences.cameraSettings.first()
        args.templateId?.let { templates.template(it) }?.let { t ->
            settings = settings.copy(aspectRatio = t.aspectRatio, frameRate = t.cameraFrameRate)
        }
        settings = if (args.audioOnly) {
            settings.copy(captureMode = CaptureMode.AUDIO_ONLY)
        } else if (settings.captureMode == CaptureMode.AUDIO_ONLY) {
            settings.copy(captureMode = CaptureMode.VIDEO_WITH_AUDIO)
        } else {
            settings
        }
        runCatching { engine.detectCameras() }.onFailure { RgLog.e(TAG, "camera detection failed", it) }
        val inventory = engine.inventory.value
        val validated = sanitize(settings, entitlementProvider.entitlements.value)
        _state.update { it.copy(initialized = true, settings = validated, noCamera = inventory?.isEmpty != false) }
        applyActivation()
        refreshStorageEstimate()
    }

    private fun collectEngine() {
        viewModelScope.launch {
            engine.state.collect { s -> _state.update { it.copy(cameraState = s) } }
        }
        viewModelScope.launch {
            combine(engine.inventory, engine.capabilities) { inv, caps -> inv to caps }.collect { (inv, caps) ->
                val facing = caps?.facing ?: _state.value.settings.lensFacing
                _state.update {
                    it.copy(
                        capabilities = caps,
                        lenses = inv?.lenses?.get(facing).orEmpty(),
                        facings = inv?.facings.orEmpty(),
                        noCamera = inv != null && inv.isEmpty,
                    )
                }
            }
        }
        viewModelScope.launch {
            engine.settings.collect { effective ->
                // The engine may have fallen back (e.g. a session rejected 4K60): reflect it.
                if (effective != null && _state.value.mode == StudioMode.VIDEO) {
                    _state.update { it.copy(settings = effective.copy(captureMode = it.settings.captureMode)) }
                }
            }
        }
        viewModelScope.launch { engine.controls.collect { c -> _state.update { it.copy(controls = c) } } }
        viewModelScope.launch { engine.focus.collect { f -> _state.update { it.copy(focus = f) } } }
        viewModelScope.launch { engine.liveExposure.collect { l -> _state.update { it.copy(live = l) } } }
        viewModelScope.launch { engine.previewFrame.collect { p -> _state.update { it.copy(previewFrame = p) } } }
        // PipelineStats are not mirrored into the UI state (nothing shows them; they would recompose the studio
        // every second). Diagnostics read engine.stats directly.
        viewModelScope.launch { engine.thermal.collect { t -> _state.update { it.copy(thermal = t) } } }
        viewModelScope.launch {
            // Only phase-level changes reach the UI state; the ticking clock is [recordingClock].
            engine.recording
                .distinctUntilChanged { a, b -> a.phase == b.phase && a.captureMode == b.captureMode && a.output == b.output && a.frameRate == b.frameRate }
                .collect { r -> _state.update { it.copy(recording = r, lensTrayOpen = if (r.isActive) false else it.lensTrayOpen) } }
        }
        viewModelScope.launch { cameraEffects.effects.collect { e -> _state.update { it.copy(effects = e) } } }
        viewModelScope.launch { cameraEffects.effectsStatus.collect { e -> _state.update { it.copy(effectsStatus = e) } } }
        viewModelScope.launch { cameraEffects.hasBackgroundImage.collect { h -> _state.update { it.copy(hasBackgroundImage = h) } } }
        viewModelScope.launch {
            beautyEngine.status.map { it.faceCount }.distinctUntilChanged().collect { n -> _state.update { it.copy(facesTracked = n) } }
        }
        viewModelScope.launch {
            engine.recording.map { it.phase }.distinctUntilChanged().collect { phase ->
                runCatching { beautyEngine.setRecording(phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED || phase == RecordingPhase.STARTING) }
                if (phase == RecordingPhase.IDLE) refreshStorageEstimate()
            }
        }
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is CameraEvent.RecordingFinished -> Unit // saved by RecordingSaver
                    is CameraEvent.RecordingFailed -> {
                        _state.update { it.copy(postRecord = null) }
                        _messages.trySend(StudioMessage.NothingRecorded)
                    }
                    is CameraEvent.Warning -> _messages.trySend(StudioMessage.Warning(event.warning))
                }
            }
        }
    }

    private fun collectAudio() {
        viewModelScope.launch {
            preferences.audioSettings.collect { s ->
                _state.update { it.copy(audioSettings = s) }
                applyAudioSettings(s, _state.value.entitlements)
            }
        }
        viewModelScope.launch { audioEngine.inputs.collect { l -> _state.update { it.copy(audioInputs = l) } } }
        viewModelScope.launch { audioEngine.activeInput.collect { d -> _state.update { it.copy(activeInput = d) } } }
        viewModelScope.launch { audioEngine.monitoringAvailable.collect { m -> _state.update { it.copy(monitoringAvailable = m) } } }
    }

    private fun applyAudioSettings(s: AudioSettings, e: Entitlements) {
        val effective = s.copy(
            noiseReduction = s.noiseReduction && e.has(ProFeature.AUDIO_NOISE_REDUCTION),
            monitoring = s.monitoring && audioEngine.monitoringAvailable.value,
        )
        runCatching { audioEngine.updateSettings(effective) }.onFailure { RgLog.w(TAG, "audio settings not applied", it) }
    }

    private fun collectPrompter() {
        viewModelScope.launch {
            val scriptFlow = scriptId.flatMapLatest { id -> if (id == null) flowOf(null) else scripts.observeScript(id) }
            combine(scriptFlow, preferences.prompterDefaults, prompterVisible) { script, defaults, visible ->
                script?.let {
                    val base = it.prompterSettings ?: defaults
                    // In the studio the recording countdown replaces the prompter's own countdown when synced.
                    val effective = if (base.syncWithRecording) base.copy(countdownSeconds = 0) else base
                    PrompterUi(it, effective, visible)
                }
            }.collect { p -> _state.update { it.copy(prompter = p) } }
        }
    }

    private suspend fun importRecovered() {
        runCatching {
            recovery.recoverInterrupted()
            val recovered = recovery.consumeRecovered()
            var count = 0
            for (r in recovered) if (saver.saveRecovered(r) != null) count++
            if (count > 0) _messages.trySend(StudioMessage.Recovered(count))
        }.onFailure { RgLog.e(TAG, "recovery import failed", it) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Activation (lifecycle + permissions)
    // ---------------------------------------------------------------------------------------------------------

    fun setActivation(foreground: Boolean, cameraGranted: Boolean, micGranted: Boolean) {
        val wentBackground = this.foreground && !foreground
        this.foreground = foreground
        this.cameraGranted = cameraGranted
        this.micGranted = micGranted
        if (wentBackground) {
            countdownJob?.cancel()
            _state.update { it.copy(countdown = null) }
            if (_state.value.isRecording) viewModelScope.launch { engine.stopRecording(StopReason.LIFECYCLE) }
        }
        applyActivation()
    }

    private fun applyActivation() {
        val s = _state.value
        if (!s.initialized) return
        val wantCamera = foreground && cameraGranted && s.mode == StudioMode.VIDEO && !s.noCamera
        if (wantCamera && !cameraRunning) {
            runCatching { engine.setFrameProcessors(listOf(beautyEngine.processor)) }
                .onFailure { RgLog.w(TAG, "beauty processor unavailable", it) }
            engine.start(s.settings)
            cameraRunning = true
        } else if (!wantCamera && cameraRunning) {
            engine.stop()
            cameraRunning = false
        }
        val wantAudio = foreground && micGranted && s.needsMic
        if (wantAudio && !audioPreviewRunning) {
            runCatching { audioEngine.startPreview() }.onFailure { RgLog.w(TAG, "audio preview failed", it) }
            audioPreviewRunning = true
        } else if (!wantAudio && audioPreviewRunning && !s.isRecording) {
            runCatching { audioEngine.stopPreview() }
            audioPreviewRunning = false
        }
    }

    fun setDeviceOrientation(degrees: Int) = engine.setDeviceOrientation(degrees)
    fun setPreviewSurface(surface: Surface, width: Int, height: Int) = engine.setPreviewSurface(surface, width, height)
    fun clearPreviewSurface(surface: Surface) = engine.clearPreviewSurface(surface)

    // ---------------------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------------------

    private fun sanitize(settings: CameraSettings, e: Entitlements): CameraSettings {
        var s = settings
        if (s.resolution.shortSide > 1080 && !e.has(ProFeature.RECORD_4K)) s = s.copy(resolution = VideoSize(1920, 1080))
        if (s.frameRate > 30 && !e.has(ProFeature.RECORD_HIGH_FPS)) s = s.copy(frameRate = 30)
        if (s.codec == VideoCodec.HEVC && !e.has(ProFeature.RECORD_HEVC)) s = s.copy(codec = VideoCodec.H264)
        val captureMode = s.captureMode
        return engine.validate(s).copy(captureMode = captureMode)
    }

    private fun reapplyGating(e: Entitlements) {
        val current = _state.value.settings
        val sanitized = sanitize(current, e)
        if (sanitized != current && !_state.value.isRecording) {
            _state.update { it.copy(settings = sanitized) }
            if (cameraRunning) engine.updateSettings(sanitized)
        }
        applyAudioSettings(_state.value.audioSettings, e)
    }

    /** Applies a user change: gate, validate, persist, and reconfigure the camera. */
    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val current = _state.value.settings
        val next = sanitize(transform(current), _state.value.entitlements)
        if (next == current) return
        _state.update { it.copy(settings = next) }
        if (cameraRunning) engine.updateSettings(next)
        viewModelScope.launch {
            preferences.updateCameraSettings {
                // Persist the user's choice (not template/audio-only overrides of this session).
                next.copy(captureMode = if (next.captureMode == CaptureMode.AUDIO_ONLY) it.captureMode else next.captureMode)
            }
        }
        refreshStorageEstimate()
    }

    fun setMode(mode: StudioMode) {
        if (_state.value.isRecording || _state.value.mode == mode) return
        val captureMode = if (mode == StudioMode.AUDIO) CaptureMode.AUDIO_ONLY else _state.value.videoCaptureMode
        _state.update { it.copy(mode = mode, settings = it.settings.copy(captureMode = captureMode), proControlsOpen = false) }
        applyActivation()
        refreshStorageEstimate()
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (_state.value.isRecording) return
        if (mode == CaptureMode.AUDIO_ONLY) {
            setMode(StudioMode.AUDIO)
            return
        }
        if (_state.value.mode == StudioMode.AUDIO) {
            _state.update { it.copy(mode = StudioMode.VIDEO) }
        }
        updateSettings { it.copy(captureMode = mode) }
        applyActivation()
    }

    fun flipCamera() {
        val s = _state.value
        if (s.isRecording) return
        val next = if (s.settings.lensFacing == LensFacing.FRONT) LensFacing.BACK else LensFacing.FRONT
        if (next !in s.facings) return
        updateSettings { it.copy(lensFacing = next, cameraId = null) }
    }

    private fun refreshStorageEstimate() {
        storageJob?.cancel()
        storageJob = viewModelScope.launch {
            val s = _state.value
            val seconds = withContext(io) {
                if (s.mode == StudioMode.AUDIO) {
                    storage.snapshot(storage.recordingsDir).recordableSeconds(BitrateCalculator.audioBitrate(s.audioSettings.audioBitrateKbps).toLong())
                } else {
                    engine.estimateRecordableSeconds(s.settings, s.settings.captureMode == CaptureMode.VIDEO_WITH_AUDIO)
                }
            }
            _state.update { it.copy(storageEstimateSeconds = seconds) }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Camera controls
    // ---------------------------------------------------------------------------------------------------------

    fun selectLens(option: LensOption) = engine.selectLens(option)
    fun setZoom(ratio: Float) = engine.setZoom(ratio)
    fun focusAt(x: Float, y: Float) = engine.focusAndMeter(x, y)
    fun lockAt(x: Float, y: Float) {
        engine.focusAndMeter(x, y)
        engine.setAeAfLock(true)
    }
    fun unlockFocus() = engine.setAeAfLock(false)
    fun resetFocus() = engine.resetFocusAndMetering()
    fun setExposureCompensation(index: Int) = engine.setExposureCompensation(index)
    fun setIso(iso: Int?) = engine.setIso(iso)
    fun setShutter(ns: Long?) = engine.setShutter(ns)
    fun setFocusDistance(diopters: Float?) = engine.setFocusDistance(diopters)
    fun setWhiteBalance(mode: WhiteBalanceMode, kelvin: Int = _state.value.controls.kelvin) = engine.setWhiteBalance(mode, kelvin)
    fun resetManualControls() = engine.resetManualControls()

    /** Before/after hold: the preview shows the unprocessed frame, the recording keeps the effects. */
    fun setCompare(showOriginal: Boolean) {
        runCatching { beautyEngine.setCompareMode(showOriginal, affectsRecording = false) }
        engine.setPreviewBypass(showOriginal)
        _state.update { it.copy(comparing = showOriginal) }
    }

    /** Mirrors an effect engine's preview-bypass flag (e.g. `BeautyEngine.previewBypass`) into the camera pipeline. */
    fun bindPreviewBypass(bypass: Flow<Boolean>) {
        viewModelScope.launch { bypass.distinctUntilChanged().collect { engine.setPreviewBypass(it || _state.value.comparing) } }
    }

    fun openSheet(sheet: StudioSheet) = _state.update { it.copy(sheet = sheet, proControlsOpen = false, lensTrayOpen = false) }
    fun closeSheet() = _state.update { it.copy(sheet = StudioSheet.NONE) }
    fun toggleProControls() = _state.update { it.copy(proControlsOpen = !it.proControlsOpen, sheet = StudioSheet.NONE, lensTrayOpen = false) }

    // ---------------------------------------------------------------------------------------------------------
    // Lenses, live filters, background effects (Pro gating: see [EffectsGating])
    // ---------------------------------------------------------------------------------------------------------

    fun toggleLensTray() {
        if (_state.value.isRecording) return
        _state.update { it.copy(lensTrayOpen = !it.lensTrayOpen, proControlsOpen = false, sheet = StudioSheet.NONE) }
    }

    fun closeLensTray() = _state.update { it.copy(lensTrayOpen = false) }

    /**
     * Applies [lens] (null = none). A Pro lens without the entitlement is not applied (the carousel shows it as
     * locked and offers the paywall); returns whether it was applied.
     */
    fun selectLens(lens: Lens?): Boolean {
        if (lens != null && !EffectsGating.lensAllowed(lens, _state.value.entitlements)) {
            if (cameraEffects.effects.value.lens != null) cameraEffects.setLens(null)
            return false
        }
        if (cameraEffects.effects.value.lens != lens) cameraEffects.setLens(lens)
        return true
    }

    /** Filters a swipe cycles through: every free filter, plus Pro ones when entitled. */
    fun swipeableFilters(): List<LiveFilter> = EffectsGating.swipeable(_state.value.entitlements)

    fun setFilter(filter: LiveFilter): Boolean {
        if (!EffectsGating.filterAllowed(filter, _state.value.entitlements)) return false
        cameraEffects.setFilter(filter)
        // Warm up the neighbours so the next swipe is instant.
        val list = swipeableFilters()
        val i = list.indexOf(filter)
        if (i >= 0) cameraEffects.prefetch(listOf(list[(i + 1) % list.size], list[(i - 1 + list.size) % list.size]))
        return true
    }

    fun setFilterIntensity(value: Int) = cameraEffects.setFilterIntensity(value)

    /** Live split preview while the finger drags across the preview (no state change until committed). */
    fun previewFilterSwipe(swipe: FilterSwipe?) = cameraEffects.setFilterSwipe(swipe)

    fun setBackground(effect: BackgroundEffect): Boolean {
        if (!EffectsGating.backgroundAllowed(effect, _state.value.entitlements)) return false
        cameraEffects.setBackground(effect)
        return true
    }

    fun onBackgroundPhotoPicked(uri: Uri) {
        if (!EffectsGating.backgroundAllowed(BackgroundEffect.Image, _state.value.entitlements)) return
        viewModelScope.launch {
            if (cameraEffects.setBackgroundImage(uri)) {
                cameraEffects.setBackground(BackgroundEffect.Image)
            } else {
                _messages.trySend(StudioMessage.BackgroundPhotoFailed)
            }
        }
    }

    /** Removes the lens, filter and background effect. */
    fun clearEffects() {
        cameraEffects.setLens(null)
        cameraEffects.setFilter(LiveFilter.NONE)
        cameraEffects.setBackground(BackgroundEffect.None)
    }

    /** A downgrade (or expired trial) must not keep Pro effects running. */
    private fun reapplyEffectsGating(e: Entitlements) {
        val fx = cameraEffects.effects.value
        fx.lens?.let { if (!EffectsGating.lensAllowed(it, e)) cameraEffects.setLens(null) }
        if (!EffectsGating.filterAllowed(fx.filter, e)) cameraEffects.setFilter(LiveFilter.NONE)
        if (!EffectsGating.backgroundAllowed(fx.background, e)) cameraEffects.setBackground(BackgroundEffect.None)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Audio
    // ---------------------------------------------------------------------------------------------------------

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch { preferences.updateAudioSettings(transform) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Teleprompter
    // ---------------------------------------------------------------------------------------------------------

    fun onScriptPicked(id: String) {
        scriptId.value = id
        prompterVisible.value = true
    }

    fun setPrompterVisible(visible: Boolean) {
        prompterVisible.value = visible
    }

    // ---------------------------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------------------------

    /** Big button / shutter key: start (with timer), cancel a countdown, or stop. */
    fun onRecordPressed() {
        val s = _state.value
        when {
            s.recording.phase == RecordingPhase.FINALIZING || s.recording.phase == RecordingPhase.STARTING -> Unit
            s.isRecording -> stopRecording()
            countdownJob?.isActive == true -> {
                countdownJob?.cancel()
                _state.update { it.copy(countdown = null) }
            }
            else -> startWithTimer()
        }
    }

    private fun startWithTimer() {
        val s = _state.value
        if (s.mode == StudioMode.VIDEO && s.cameraState !is com.ravango.engine.camera.CameraState.Streaming) return
        if (s.mode == StudioMode.AUDIO && !micGranted) return
        val seconds = s.settings.timerSeconds
        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _state.update { it.copy(countdown = i) }
                delay(1_000)
            }
            _state.update { it.copy(countdown = null) }
            startRecordingNow()
        }
    }

    private suspend fun startRecordingNow() {
        publishTarget()
        val s = _state.value
        val mode = when {
            s.mode == StudioMode.AUDIO -> CaptureMode.AUDIO_ONLY
            s.settings.captureMode == CaptureMode.VIDEO_WITH_AUDIO && !micGranted -> {
                _messages.trySend(StudioMessage.MicUnavailableVideoOnly)
                CaptureMode.VIDEO_ONLY
            }
            else -> s.settings.captureMode
        }
        when (val result = engine.startRecording(mode, s.audioSettings.audioBitrateKbps)) {
            is Outcome.Failure -> _messages.trySend(StudioMessage.Error(result.kind))
            is Outcome.Success -> _state.update { it.copy(sheet = StudioSheet.NONE, proControlsOpen = false) }
        }
    }

    fun stopRecording() {
        viewModelScope.launch { engine.stopRecording(StopReason.USER) }
    }

    fun pauseOrResume() {
        when (_state.value.recording.phase) {
            RecordingPhase.RECORDING -> engine.pauseRecording()
            RecordingPhase.PAUSED -> engine.resumeRecording()
            else -> Unit
        }
    }

    private fun currentTarget() = SaveTarget(
        projectId = saver.target?.projectId ?: args.projectId,
        scriptId = _state.value.prompter?.script?.id ?: args.scriptId,
        templateId = args.templateId,
        aspectRatio = _state.value.settings.aspectRatio,
    )

    private fun publishTarget() {
        saver.target = currentTarget()
    }

    fun dismissPostRecord() = _state.update { it.copy(postRecord = null) }

    fun retryCamera() = engine.retry()

    override fun onCleared() {
        countdownJob?.cancel()
        publishTarget()
        // Closing the studio finalizes a running take; RecordingSaver saves it in the app scope.
        appScope.launch {
            engine.stopRecording(StopReason.LIFECYCLE)
            engine.stop()
            engine.setFrameProcessors(emptyList())
        }
        runCatching { audioEngine.stopPreview() }
        runCatching { beautyEngine.setCompareMode(false) }
        runCatching { beautyEngine.setRecording(false) }
        // Like Snapchat, the studio opens without a lens next time; filter and background are remembered.
        runCatching { cameraEffects.setFilterSwipe(null) }
        runCatching { cameraEffects.setLens(null) }
    }

    private companion object {
        const val TAG = "CameraVM"
    }
}
