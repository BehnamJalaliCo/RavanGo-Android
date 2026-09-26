@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ravango.feature.camera

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.FlashMode
import com.ravango.core.model.GridType
import com.ravango.core.model.ProFeature
import com.ravango.core.ui.AppPermission
import com.ravango.core.ui.HardwareKeyHandler
import com.ravango.core.ui.HardwareKeys
import com.ravango.core.ui.KeepScreenOn
import com.ravango.core.ui.MaxBrightness
import com.ravango.core.ui.PermissionRationaleCard
import com.ravango.core.ui.PermissionRequester
import com.ravango.core.ui.PermissionStatus
import com.ravango.core.ui.messageRes
import com.ravango.core.ui.rememberPermissionRequester
import com.ravango.core.ui.shareMedia
import com.ravango.engine.camera.CameraErrorKind
import com.ravango.engine.camera.CameraState
import com.ravango.engine.camera.CameraWarning
import com.ravango.engine.camera.FocusMode
import com.ravango.engine.camera.RecordingPhase
import com.ravango.engine.camera.capability.VideoModeSelector
import com.ravango.engine.teleprompter.PrompterController
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.beauty.BeautyPanel
import com.ravango.feature.camera.ui.AspectSheet
import com.ravango.feature.camera.ui.AudioSheet
import com.ravango.feature.camera.ui.CountdownOverlay
import com.ravango.feature.camera.ui.FlipButton
import com.ravango.feature.camera.ui.LastTakeThumbnail
import com.ravango.feature.camera.ui.LensChips
import com.ravango.feature.camera.ui.LevelIndicator
import com.ravango.feature.camera.ui.LevelMeterBar
import com.ravango.feature.camera.ui.PauseButton
import com.ravango.feature.camera.ui.PostRecordSheet
import com.ravango.feature.camera.ui.ProControlsPanel
import com.ravango.feature.camera.ui.PrompterOverlay
import com.ravango.feature.camera.ui.RecordButton
import com.ravango.feature.camera.ui.RecordingHud
import com.ravango.feature.camera.ui.ResolutionSheet
import com.ravango.feature.camera.ui.ScreenFlashOverlay
import com.ravango.feature.camera.ui.StudioPreview
import com.ravango.feature.camera.ui.StudioSettingsSheet
import com.ravango.feature.camera.ui.StudioToolRail
import com.ravango.feature.camera.ui.StudioTopBar
import com.ravango.feature.camera.ui.StudioWindow
import com.ravango.feature.camera.ui.aspectLabel
import com.ravango.feature.camera.ui.availableProTabs
import com.ravango.feature.camera.ui.frameRect
import com.ravango.feature.camera.ui.meterFraction
import com.ravango.feature.camera.ui.rememberClipLatch
import com.ravango.feature.camera.ui.rememberDeviceOrientation
import com.ravango.feature.camera.ui.rememberGravityAngle
import com.ravango.feature.camera.ui.videoModeLabel
import com.ravango.feature.camera.ui.zoomLabel
import com.ravango.core.designsystem.component.RgBottomSheet
import kotlin.math.abs

/** Camera Studio. */
@Composable
internal fun CameraStudioScreen(
    viewModel: CameraViewModel,
    pickedScriptId: String?,
    onPickedScriptConsumed: () -> Unit,
    onClose: () -> Unit,
    onOpenEditor: (projectId: String) -> Unit,
    onPickScript: () -> Unit,
    onOpenBeautyPresets: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
) {
    StudioTheme {
        StudioWindow()
        KeepScreenOn(true)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val level = viewModel.audioLevel.collectAsStateWithLifecycle()
        val clipping = rememberClipLatch(level)
        val context = LocalContext.current
        val snackbar = rememberSnackbarHostState()

        // --- permissions (just in time) ---
        var recordAfterMic by remember { mutableStateOf(false) }
        val cameraRequester = rememberPermissionRequester(AppPermission.CAMERA)
        val micRequester = rememberPermissionRequester(AppPermission.MICROPHONE) {
            if (recordAfterMic) {
                recordAfterMic = false
                viewModel.onRecordPressed()
            }
        }

        // --- lifecycle ---
        var foreground by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false }
        LaunchedEffect(foreground, cameraRequester.allGranted, micRequester.allGranted) {
            viewModel.setActivation(foreground, cameraRequester.allGranted, micRequester.allGranted)
        }
        LaunchedEffect(pickedScriptId) {
            if (pickedScriptId != null) {
                viewModel.onScriptPicked(pickedScriptId)
                onPickedScriptConsumed()
            }
        }

        // --- orientation: rotate controls, not the preview ---
        val orientation = rememberDeviceOrientation { viewModel.setDeviceOrientation(it) }
        LaunchedEffect(Unit) { viewModel.setDeviceOrientation(orientation.value) }
        val iconRotation by animateFloatAsState(
            when (orientation.value) {
                90 -> -90f
                180 -> 180f
                270 -> 90f
                else -> 0f
            },
            label = "iconRotation",
        )

        // --- teleprompter ---
        val prompter = state.prompter
        val controller: PrompterController? = prompter?.let { rememberPrompterController(it.script.body, it.settings, it.script.startCharOffset) }
        val prompterShown = prompter != null && prompter.visible && state.mode == StudioMode.VIDEO && controller != null
        LaunchedEffect(state.recording.phase, controller, prompterShown) {
            val c = controller ?: return@LaunchedEffect
            if (!prompterShown || prompter?.settings?.syncWithRecording != true) return@LaunchedEffect
            when (state.recording.phase) {
                RecordingPhase.RECORDING -> c.play()
                RecordingPhase.PAUSED, RecordingPhase.FINALIZING -> c.pause()
                else -> Unit
            }
        }

        // --- messages ---
        LaunchedEffect(Unit) {
            viewModel.messages.collect { snackbar.showSnackbar(messageText(context, it)) }
        }

        val onRecord: () -> Unit = {
            val needsMicNow = !state.isRecording && state.needsMic && !micRequester.allGranted
            if (needsMicNow && micRequester.status == PermissionStatus.DENIED) {
                recordAfterMic = state.mode == StudioMode.VIDEO
                micRequester.request()
            } else {
                viewModel.onRecordPressed()
            }
        }
        val currentOnRecord by rememberUpdatedState(onRecord)

        // --- hardware keys: prompter control or shutter ---
        HardwareKeyHandler { event ->
            handleKey(event, prompterShown, prompter?.settings?.volumeKeysControl == true, prompter?.settings?.remoteControl == true, controller, currentOnRecord)
        }

        var confirmLeave by remember { mutableStateOf(false) }
        BackHandler(enabled = state.sheet != StudioSheet.NONE || state.proControlsOpen || state.isRecording) {
            when {
                state.sheet != StudioSheet.NONE -> viewModel.closeSheet()
                state.proControlsOpen -> viewModel.toggleProControls()
                state.isRecording -> confirmLeave = true
            }
        }

        var container by remember { mutableStateOf(IntSize.Zero) }
        val frame: Rect? = frameRect(state.previewFrame, container)
        val caps = state.capabilities
        val screenFlash = state.mode == StudioMode.VIDEO && state.settings.flash == FlashMode.SCREEN && caps?.screenFlash == true
        MaxBrightness(screenFlash)
        val locked = state.isRecording
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // ---------------- main surface ----------------
            if (state.mode == StudioMode.VIDEO) {
                when {
                    !cameraRequester.allGranted -> PermissionGate(cameraRequester, stringResource(com.ravango.core.ui.R.string.permission_camera_title), stringResource(R.string.camera_permission_camera_message), Icons.Rounded.Videocam)
                    state.noCamera -> EmptyState(Icons.Rounded.NoPhotography, stringResource(R.string.camera_no_camera_title), stringResource(R.string.camera_no_camera_message), Modifier.align(Alignment.Center))
                    else -> StudioPreview(
                        settings = state.settings,
                        frame = state.previewFrame,
                        capabilities = caps,
                        controls = state.controls,
                        focus = state.focus,
                        showOverlays = state.cameraState is CameraState.Streaming,
                        iconRotation = iconRotation,
                        onSurface = viewModel::setPreviewSurface,
                        onSurfaceGone = viewModel::clearPreviewSurface,
                        onContainerSize = { container = it },
                        onFocus = viewModel::focusAt,
                        onLock = viewModel::lockAt,
                        onZoom = viewModel::setZoom,
                        onExposure = viewModel::setExposureCompensation,
                    )
                }
                if (state.settings.showLevel && state.cameraState is CameraState.Streaming) {
                    val angle = rememberGravityAngle(enabled = true)
                    LevelIndicator(angle)
                }
            } else {
                AudioStudio(
                    level = level,
                    clipping = clipping,
                    micRequester = micRequester,
                    inputName = state.activeInput?.name,
                    recordingDurationUs = if (state.isRecording) state.recording.durationUs else null,
                    iconRotation = iconRotation,
                )
            }

            if (screenFlash) ScreenFlashOverlay(frame)

            if (prompterShown) {
                PrompterOverlay(prompter!!, controller!!, topInset = 64.dp, onHide = { viewModel.setPrompterVisible(false) })
            }

            // ---------------- top ----------------
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioTopBar(
                    videoMode = if (state.mode == StudioMode.VIDEO && caps != null) videoModeLabel(state.settings.resolution, state.settings.frameRate) else null,
                    aspect = if (state.mode == StudioMode.VIDEO) aspectLabel(state.settings.aspectRatio) else null,
                    flash = state.settings.flash,
                    flashEnabled = state.mode == StudioMode.VIDEO && caps != null && VideoModeSelector.flashModes(caps).size > 1,
                    timerSeconds = state.settings.timerSeconds,
                    grid = state.settings.grid,
                    iconRotation = iconRotation,
                    locked = locked,
                    onClose = onClose,
                    onResolution = { viewModel.openSheet(StudioSheet.RESOLUTION) },
                    onFlash = {
                        val modes = caps?.let(VideoModeSelector::flashModes).orEmpty()
                        if (modes.isNotEmpty()) {
                            val next = modes[(modes.indexOf(state.settings.flash) + 1) % modes.size]
                            viewModel.updateSettings { it.copy(flash = next) }
                        }
                    },
                    onTimer = {
                        val options = VideoModeSelector.TIMER_OPTIONS
                        val next = options[(options.indexOf(state.settings.timerSeconds) + 1).mod(options.size)]
                        viewModel.updateSettings { it.copy(timerSeconds = next) }
                    },
                    onGrid = {
                        val next = GridType.entries[(state.settings.grid.ordinal + 1) % GridType.entries.size]
                        viewModel.updateSettings { it.copy(grid = next) }
                    },
                    onAspect = { viewModel.openSheet(StudioSheet.ASPECT) },
                    onSettings = { viewModel.openSheet(StudioSheet.SETTINGS) },
                )
                if (state.isRecording) RecordingHud(state.recording, iconRotation)
                StatusPills(state, iconRotation, onUnlock = viewModel::unlockFocus, onResetFocus = viewModel::resetFocus)
            }

            // ---------------- tool rail ----------------
            StudioToolRail(
                iconRotation = iconRotation,
                videoMode = state.mode == StudioMode.VIDEO,
                micOn = state.settings.captureMode != CaptureMode.VIDEO_ONLY || state.mode == StudioMode.AUDIO,
                prompterActive = prompterShown,
                proOpen = state.proControlsOpen,
                showPro = caps != null && availableProTabs(caps).isNotEmpty(),
                onBeauty = { viewModel.openSheet(StudioSheet.BEAUTY) },
                onPro = viewModel::toggleProControls,
                onAudio = { viewModel.openSheet(StudioSheet.AUDIO) },
                onPrompter = {
                    when {
                        prompter == null -> onPickScript()
                        prompter.visible -> onPickScript()
                        else -> viewModel.setPrompterVisible(true)
                    }
                },
                onCompare = viewModel::setCompare,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, top = if (prompterShown) screenHeight * 0.2f else 0.dp),
            )

            // ---------------- camera errors ----------------
            (state.cameraState as? CameraState.Error)?.let { error ->
                if (state.mode == StudioMode.VIDEO && cameraRequester.allGranted) {
                    CameraErrorCard(error, onRetry = viewModel::retryCamera, modifier = Modifier.align(Alignment.Center).padding(Spacing.gutter))
                }
            }

            CountdownOverlay(state.countdown, iconRotation)

            // ---------------- bottom ----------------
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(
                    state.proControlsOpen && caps != null && state.mode == StudioMode.VIDEO,
                    enter = slideInVertically { it / 2 } + fadeIn(),
                    exit = slideOutVertically { it / 2 } + fadeOut(),
                ) {
                    if (caps != null) {
                        ProControlsPanel(
                            caps = caps,
                            settings = state.settings,
                            controls = state.controls,
                            live = state.live,
                            entitlements = state.entitlements,
                            locked = locked,
                            onExposure = viewModel::setExposureCompensation,
                            onIso = viewModel::setIso,
                            onShutter = viewModel::setShutter,
                            onFocus = viewModel::setFocusDistance,
                            onWhiteBalance = viewModel::setWhiteBalance,
                            onStabilization = { m -> viewModel.updateSettings { it.copy(stabilization = m) } },
                            onHdr = { v -> viewModel.updateSettings { it.copy(hdr = v) } },
                            onReset = viewModel::resetManualControls,
                            onRequirePro = onRequirePro,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                if (state.mode == StudioMode.VIDEO && state.cameraState is CameraState.Streaming) {
                    LensChips(state.lenses, state.controls.zoomRatio, caps?.cameraId, iconRotation, onSelect = viewModel::selectLens)
                }
                if (!state.isRecording) {
                    StorageLine(state)
                }
                if (state.needsMic && micRequester.allGranted) {
                    LevelMeterBar(level, clipping, Modifier.width(140.dp), height = 5.dp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        if (state.isRecording) {
                            PauseButton(state.recording.phase == RecordingPhase.PAUSED, iconRotation, viewModel::pauseOrResume)
                        } else {
                            LastTakeThumbnail(state.lastThumbnailPath, onClick = { state.lastProjectId?.let(onOpenEditor) })
                        }
                    }
                    val streaming = state.cameraState is CameraState.Streaming
                    RecordButton(
                        recording = state.isRecording,
                        busy = state.recording.phase == RecordingPhase.STARTING || state.recording.phase == RecordingPhase.FINALIZING ||
                            (state.mode == StudioMode.VIDEO && !streaming && !state.isRecording) ||
                            (state.mode == StudioMode.AUDIO && micRequester.status == PermissionStatus.PERMANENTLY_DENIED),
                        countingDown = state.countdown != null,
                        audioMode = state.mode == StudioMode.AUDIO,
                        onClick = onRecord,
                    )
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        if (state.mode == StudioMode.VIDEO && state.facings.size > 1) {
                            FlipButton(enabled = !state.isRecording, iconRotation = iconRotation, onClick = viewModel::flipCamera)
                        }
                    }
                }
                AnimatedVisibility(!state.isRecording && state.countdown == null) {
                    RgSegmentedControl(
                        options = StudioMode.entries,
                        selected = state.mode,
                        onSelect = viewModel::setMode,
                        label = { stringResource(if (it == StudioMode.VIDEO) R.string.camera_mode_video else R.string.camera_mode_audio) },
                        glass = true,
                    )
                }
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 220.dp))
        }

        // ---------------- sheets ----------------
        when (state.sheet) {
            StudioSheet.RESOLUTION -> caps?.let {
                ResolutionSheet(
                    caps = it,
                    settings = state.settings,
                    entitlements = state.entitlements,
                    onSelect = { size, fps -> viewModel.updateSettings { s -> s.copy(resolution = size, frameRate = fps) } },
                    onCodec = { c -> viewModel.updateSettings { s -> s.copy(codec = c) } },
                    onQuality = { q -> viewModel.updateSettings { s -> s.copy(bitrateProfile = q) } },
                    onRequirePro = onRequirePro,
                    onDismiss = viewModel::closeSheet,
                )
            }
            StudioSheet.ASPECT -> AspectSheet(
                selected = state.settings.aspectRatio,
                onSelect = { a ->
                    viewModel.updateSettings { it.copy(aspectRatio = a) }
                    viewModel.closeSheet()
                },
                onDismiss = viewModel::closeSheet,
            )
            StudioSheet.SETTINGS -> StudioSettingsSheet(caps, state.settings, locked, viewModel::updateSettings, viewModel::closeSheet)
            StudioSheet.AUDIO -> AudioSheet(
                captureMode = state.settings.captureMode,
                settings = state.audioSettings,
                inputs = state.audioInputs,
                activeInput = state.activeInput,
                monitoringAvailable = state.monitoringAvailable,
                micGranted = micRequester.allGranted,
                entitlements = state.entitlements,
                locked = locked,
                level = level,
                clipping = clipping,
                onCaptureMode = viewModel::setCaptureMode,
                onChange = viewModel::updateAudio,
                onRequestMic = micRequester::request,
                onRequirePro = onRequirePro,
                onDismiss = viewModel::closeSheet,
            )
            StudioSheet.BEAUTY -> RgBottomSheet(onDismiss = viewModel::closeSheet, dark = true) {
                BeautyPanel(onOpenPresets = onOpenBeautyPresets, onRequirePro = onRequirePro)
            }
            StudioSheet.NONE -> Unit
        }

        state.postRecord?.let { post ->
            PostRecordSheet(
                state = post,
                onOpenEditor = { id ->
                    viewModel.dismissPostRecord()
                    onOpenEditor(id)
                },
                onRecordAnother = viewModel::dismissPostRecord,
                onShare = {
                    (post as? PostRecordState.Saved)?.take?.let { take -> shareTake(context, take) }
                },
                onDismiss = viewModel::dismissPostRecord,
            )
        }

        if (confirmLeave) {
            RgConfirmDialog(
                title = stringResource(R.string.camera_leave_title),
                message = stringResource(R.string.camera_leave_message),
                confirmText = stringResource(R.string.camera_leave_confirm),
                dismissText = stringResource(R.string.camera_keep_recording),
                onConfirm = {
                    confirmLeave = false
                    viewModel.stopRecording()
                    onClose()
                },
                onDismiss = { confirmLeave = false },
            )
        }
    }
}

private fun handleKey(
    event: KeyEvent,
    prompterShown: Boolean,
    volumeControlsPrompter: Boolean,
    remoteControlsPrompter: Boolean,
    controller: PrompterController?,
    onRecord: () -> Unit,
): Boolean {
    val code = event.keyCode
    val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
    val prompter = if (prompterShown) controller else null
    return when {
        code in HardwareKeys.VOLUME -> {
            if (down) {
                if (prompter != null && volumeControlsPrompter) {
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) prompter.toggle() else prompter.scrollBy(-0.25f)
                } else {
                    onRecord()
                }
            }
            true
        }
        prompter != null && remoteControlsPrompter && code in HardwareKeys.REMOTE_FORWARD -> { if (down) prompter.scrollBy(0.25f); true }
        prompter != null && remoteControlsPrompter && code in HardwareKeys.REMOTE_BACKWARD -> { if (down) prompter.scrollBy(-0.25f); true }
        prompter != null && remoteControlsPrompter && code in HardwareKeys.REMOTE_TOGGLE -> { if (down) prompter.toggle(); true }
        code == KeyEvent.KEYCODE_CAMERA || code in HardwareKeys.REMOTE_TOGGLE -> { if (down) onRecord(); true }
        else -> false
    }
}

private fun shareTake(context: Context, take: SavedTake) {
    val uri = take.publishedUri?.let(Uri::parse)
        ?: runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", take.file) }.getOrNull()
        ?: return
    context.shareMedia(uri, take.mimeType, context.getString(R.string.camera_share))
}

private fun messageText(context: Context, message: StudioMessage): String = when (message) {
    is StudioMessage.Error -> context.getString(message.kind.messageRes())
    StudioMessage.MicUnavailableVideoOnly -> context.getString(R.string.camera_msg_video_only)
    StudioMessage.NothingRecorded -> context.getString(R.string.camera_msg_nothing_recorded)
    is StudioMessage.Recovered -> context.resources.getQuantityString(R.plurals.camera_msg_recovered, message.count, message.count.toString().localizeDigits())
    is StudioMessage.Warning -> context.getString(
        when (message.warning) {
            CameraWarning.THERMAL_WARM -> R.string.camera_warn_warm
            CameraWarning.THERMAL_HOT -> R.string.camera_warn_hot
            CameraWarning.STOPPED_THERMAL -> R.string.camera_warn_stopped_thermal
            CameraWarning.LOW_STORAGE -> R.string.camera_warn_low_storage
            CameraWarning.STOPPED_LOW_STORAGE -> R.string.camera_warn_stopped_storage
            CameraWarning.STOPPED_CAMERA_ERROR -> R.string.camera_warn_stopped_camera
            CameraWarning.STOPPED_ENCODER_ERROR -> R.string.camera_warn_stopped_encoder
            CameraWarning.AUDIO_UNAVAILABLE -> R.string.camera_warn_audio_unavailable
            CameraWarning.AUDIO_FORMAT_CHANGED -> R.string.camera_warn_audio_format
        },
    )
}

@Composable
private fun PermissionGate(requester: PermissionRequester, title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxSize().padding(Spacing.gutter), contentAlignment = Alignment.Center) {
        PermissionRationaleCard(requester, title, message, icon = icon)
    }
}

@Composable
private fun CameraErrorCard(error: CameraState.Error, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val (title, message) = when (error.kind) {
        CameraErrorKind.IN_USE, CameraErrorKind.MAX_CAMERAS_IN_USE -> R.string.camera_error_in_use_title to R.string.camera_error_in_use_message
        CameraErrorKind.DISABLED -> R.string.camera_error_disabled_title to R.string.camera_error_disabled_message
        CameraErrorKind.PERMISSION -> R.string.camera_error_permission_title to R.string.camera_permission_camera_message
        CameraErrorKind.NO_CAMERA -> R.string.camera_no_camera_title to R.string.camera_no_camera_message
        CameraErrorKind.CONFIGURATION -> R.string.camera_error_config_title to R.string.camera_error_config_message
        else -> R.string.camera_error_generic_title to R.string.camera_error_generic_message
    }
    GlassSurface(modifier.fillMaxWidth(), tint = Color(0xE6141220)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            if (error.retrying) {
                Text(stringResource(R.string.camera_error_reconnecting), style = MaterialTheme.typography.labelLarge, color = Palette.Butter400)
            }
            if (error.kind != CameraErrorKind.NO_CAMERA && error.kind != CameraErrorKind.DISABLED) {
                RgPrimaryButton(stringResource(R.string.camera_retry), onRetry, size = RgButtonSize.MEDIUM)
            }
        }
    }
}

@Composable
private fun StatusPills(state: CameraUiState, iconRotation: Float, onUnlock: () -> Unit, onResetFocus: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.graphicsLayer { rotationZ = iconRotation }) {
        if (state.mode == StudioMode.VIDEO && (state.focus.aeLocked || state.focus.mode == FocusMode.LOCKED)) {
            Pill(stringResource(R.string.camera_ae_af_lock), Palette.Butter400, Color.Black, Icons.Rounded.LockOpen, onUnlock)
        } else if (state.mode == StudioMode.VIDEO && state.focus.mode == FocusMode.POINT) {
            Pill(stringResource(R.string.camera_focus_auto), Color.Black.copy(alpha = 0.45f), Color.White, null, onResetFocus)
        }
        if (state.thermal >= ThermalLevel.HOT) {
            Pill(stringResource(R.string.camera_thermal_hot), Palette.Rose400.copy(alpha = 0.9f), Color.Black, Icons.Rounded.Thermostat, null)
        }
        if (state.mode == StudioMode.VIDEO && abs(state.controls.zoomRatio - 1f) > 0.05f && state.lenses.size <= 1) {
            Pill(zoomLabel(state.controls.zoomRatio), Color.Black.copy(alpha = 0.45f), Color.White, null, null)
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun StorageLine(state: CameraUiState) {
    val seconds = state.storageEstimateSeconds ?: return
    if (seconds == Long.MAX_VALUE) return
    val amount = if (seconds >= 2 * 3600) {
        stringResource(R.string.camera_hours, (seconds / 3600).toString().localizeDigits())
    } else {
        stringResource(R.string.camera_minutes, (seconds / 60).toString().localizeDigits())
    }
    val text = if (state.mode == StudioMode.AUDIO) {
        stringResource(R.string.camera_storage_audio, amount)
    } else {
        stringResource(R.string.camera_storage_video, amount, videoModeLabel(state.settings.resolution, state.settings.frameRate))
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (seconds < 300) Palette.Butter400 else Color.White.copy(alpha = 0.75f),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Audio-only mode: a large live level visual instead of the camera. */
@Composable
private fun AudioStudio(
    level: State<AudioLevel>,
    clipping: State<Boolean>,
    micRequester: PermissionRequester,
    inputName: String?,
    recordingDurationUs: Long?,
    iconRotation: Float,
) {
    Box(Modifier.fillMaxSize().padding(Spacing.gutter), contentAlignment = Alignment.Center) {
        if (!micRequester.allGranted) {
            PermissionRationaleCard(
                micRequester,
                stringResource(com.ravango.core.ui.R.string.permission_mic_title),
                stringResource(R.string.camera_permission_mic_message),
                icon = Icons.Rounded.Mic,
            )
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { rotationZ = iconRotation }) {
            val accent = Palette.Lavender400
            Box(
                Modifier
                    .size(220.dp)
                    .drawBehind {
                        val l = level.value
                        val f = meterFraction(l.rmsDbfs)
                        val p = meterFraction(l.peakDbfs)
                        drawCircle(accent.copy(alpha = 0.12f + 0.2f * f), radius = size.minDimension / 2 * (0.55f + 0.45f * p))
                        drawCircle(accent.copy(alpha = 0.35f), radius = size.minDimension / 2 * (0.45f + 0.35f * f))
                        if (clipping.value) drawCircle(Palette.Record, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(accent).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            LevelMeterBar(level, clipping, Modifier.width(240.dp), height = 10.dp)
            Spacer(Modifier.height(Spacing.md))
            if (recordingDurationUs != null) {
                Text(formatDuration(recordingDurationUs), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            if (inputName != null) {
                Text(inputName, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
