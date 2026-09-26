@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ravango.feature.camera

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.LocalReduceMotion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.FlashMode
import com.ravango.core.model.GridType
import com.ravango.core.model.ProFeature
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.WhiteBalanceMode
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
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.camera.CameraErrorKind
import com.ravango.engine.camera.CameraState
import com.ravango.engine.camera.CameraWarning
import com.ravango.engine.camera.FocusMode
import com.ravango.engine.camera.RecordingPhase
import com.ravango.engine.camera.RecordingStatus
import com.ravango.engine.camera.StopReason
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.capability.VideoModeSelector
import com.ravango.engine.teleprompter.PrompterController
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.beauty.BeautyPanel
import com.ravango.feature.camera.ui.AspectSheet
import com.ravango.feature.camera.ui.AudioSheet
import com.ravango.feature.camera.ui.CountdownOverlay
import com.ravango.feature.camera.ui.EffectsButton
import com.ravango.feature.camera.ui.EffectsSheet
import com.ravango.feature.camera.ui.EffectsTab
import com.ravango.feature.camera.ui.FilterIntensityCard
import com.ravango.feature.camera.ui.FilterNameBanner
import com.ravango.feature.camera.ui.FilterPill
import com.ravango.feature.camera.ui.FlipButton
import com.ravango.feature.camera.ui.LastTakeThumbnail
import com.ravango.feature.camera.ui.LensCarousel
import com.ravango.feature.camera.ui.LensChips
import com.ravango.feature.camera.ui.LensNameToast
import com.ravango.feature.camera.ui.LensTrayActions
import com.ravango.feature.camera.ui.LensTrayState
import com.ravango.feature.camera.ui.rememberLensTray
import com.ravango.feature.camera.ui.LensesButton
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
import com.ravango.feature.camera.ui.ShutterStyle
import com.ravango.feature.camera.ui.StudioGlass
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
import com.ravango.feature.camera.ui.rememberFilterSwipeController
import com.ravango.feature.camera.ui.rememberGravityAngle
import com.ravango.feature.camera.ui.rememberLensAtlas
import com.ravango.feature.camera.ui.videoModeLabel
import com.ravango.feature.camera.ui.zoomLabel
import kotlin.math.abs

/** UI-only inputs of the studio chrome (permissions, device orientation, overlays). */
@Immutable
internal data class StudioChrome(
    val iconRotation: Float = 0f,
    val cameraGranted: Boolean = true,
    val micGranted: Boolean = true,
    val micPermanentlyDenied: Boolean = false,
    val prompterShown: Boolean = false,
    /** Filter name fading in after a swipe, and its restart counter. */
    val bannerFilter: LiveFilter? = null,
    val bannerKey: Int = 0,
)

/** Every user intent of the studio chrome. Defaults are no-ops so previews and screenshot tests stay short. */
@Immutable
internal class StudioActions(
    val onClose: () -> Unit = {},
    val onOpenSheet: (StudioSheet) -> Unit = {},
    val onFlash: () -> Unit = {},
    val onTimer: () -> Unit = {},
    val onGrid: () -> Unit = {},
    val onPro: () -> Unit = {},
    val onPrompter: () -> Unit = {},
    val onCompare: (Boolean) -> Unit = {},
    val onUnlockFocus: () -> Unit = {},
    val onResetFocus: () -> Unit = {},
    val onZoomLens: (LensOption) -> Unit = {},
    val onRecord: () -> Unit = {},
    val onPauseResume: () -> Unit = {},
    val onOpenLastTake: () -> Unit = {},
    val onFlip: () -> Unit = {},
    val onMode: (StudioMode) -> Unit = {},
    val onRetryCamera: () -> Unit = {},
    val onToggleLenses: () -> Unit = {},
    val onLens: (Lens?) -> Unit = {},
    val onFilterIntensity: (Int) -> Unit = {},
    val onRequirePro: (ProFeature) -> Unit = {},
    val onExposure: (Int) -> Unit = {},
    val onIso: (Int?) -> Unit = {},
    val onShutter: (Long?) -> Unit = {},
    val onFocusDistance: (Float?) -> Unit = {},
    val onWhiteBalance: (WhiteBalanceMode, Int) -> Unit = { _, _ -> },
    val onStabilization: (StabilizationMode) -> Unit = {},
    val onHdr: (Boolean) -> Unit = {},
    val onResetManual: () -> Unit = {},
)

/** Camera Studio (stateful): wires the view model, permissions, lifecycle, sheets and hardware keys. */
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
    StudioFrame {
        StudioWindow()
        KeepScreenOn(true)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val level = viewModel.audioLevel.collectAsStateWithLifecycle()
        val clock = viewModel.recordingClock.collectAsStateWithLifecycle()
        val clipping = rememberClipLatch(level)
        val context = LocalContext.current
        val snackbar = rememberSnackbarHostState()
        val atlas = rememberLensAtlas()

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
            val s = viewModel.state.value
            val needsMicNow = !s.isRecording && s.needsMic && !micRequester.allGranted
            if (needsMicNow && micRequester.status == PermissionStatus.DENIED) {
                recordAfterMic = s.mode == StudioMode.VIDEO
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
        BackHandler(enabled = state.sheet != StudioSheet.NONE || state.proControlsOpen || state.isRecording || state.lensTrayOpen) {
            when {
                state.sheet != StudioSheet.NONE -> viewModel.closeSheet()
                state.proControlsOpen -> viewModel.toggleProControls()
                state.lensTrayOpen -> viewModel.closeLensTray()
                state.isRecording -> confirmLeave = true
            }
        }

        // --- live filter swipe on the preview ---
        val swipe = rememberFilterSwipeController(
            filters = viewModel::swipeableFilters,
            current = { viewModel.state.value.effects.filter },
            rotation = { viewModel.state.value.previewFrame?.rotationCw ?: 0 },
            preview = viewModel::previewFilterSwipe,
            commit = { viewModel.setFilter(it) },
        )

        var container by remember { mutableStateOf(IntSize.Zero) }
        val frame: Rect? = frameRect(state.previewFrame, container)
        val caps = state.capabilities
        val screenFlash = state.mode == StudioMode.VIDEO && state.settings.flash == FlashMode.SCREEN && caps?.screenFlash == true
        MaxBrightness(screenFlash)
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp

        val actions = remember(viewModel) {
            StudioActions(
                onClose = onClose,
                onOpenSheet = viewModel::openSheet,
                onFlash = {
                    val s = viewModel.state.value
                    val modes = s.capabilities?.let(VideoModeSelector::flashModes).orEmpty()
                    if (modes.isNotEmpty()) viewModel.updateSettings { it.copy(flash = modes[(modes.indexOf(it.flash) + 1) % modes.size]) }
                },
                onTimer = {
                    val options = VideoModeSelector.TIMER_OPTIONS
                    viewModel.updateSettings { it.copy(timerSeconds = options[(options.indexOf(it.timerSeconds) + 1).mod(options.size)]) }
                },
                onGrid = { viewModel.updateSettings { it.copy(grid = GridType.entries[(it.grid.ordinal + 1) % GridType.entries.size]) } },
                onPro = viewModel::toggleProControls,
                onPrompter = {
                    val p = viewModel.state.value.prompter
                    when {
                        p == null || p.visible -> onPickScript()
                        else -> viewModel.setPrompterVisible(true)
                    }
                },
                onCompare = viewModel::setCompare,
                onUnlockFocus = viewModel::unlockFocus,
                onResetFocus = viewModel::resetFocus,
                onZoomLens = viewModel::selectLens,
                onRecord = { currentOnRecord() },
                onPauseResume = viewModel::pauseOrResume,
                onOpenLastTake = { viewModel.state.value.lastProjectId?.let(onOpenEditor) },
                onFlip = viewModel::flipCamera,
                onMode = viewModel::setMode,
                onRetryCamera = viewModel::retryCamera,
                onToggleLenses = viewModel::toggleLensTray,
                onLens = { viewModel.selectLens(it) },
                onFilterIntensity = viewModel::setFilterIntensity,
                onRequirePro = onRequirePro,
                onExposure = viewModel::setExposureCompensation,
                onIso = viewModel::setIso,
                onShutter = viewModel::setShutter,
                onFocusDistance = viewModel::setFocusDistance,
                onWhiteBalance = viewModel::setWhiteBalance,
                onStabilization = { m -> viewModel.updateSettings { it.copy(stabilization = m) } },
                onHdr = { v -> viewModel.updateSettings { it.copy(hdr = v) } },
                onResetManual = viewModel::resetManualControls,
            )
        }

        // ADDED — looks in the lens tray (shared with the Beauty panel).
        val lensTray = rememberLensTray()
        val trayActions = remember(lensTray.viewModel) {
            LensTrayActions(
                onLook = lensTray.viewModel::applyLook,
                onClearLook = lensTray.viewModel::clearLook,
                onLookIntensity = lensTray.viewModel::setLookIntensity,
                onFilter = { f -> viewModel.setFilter(f) },
                onFilterIntensity = viewModel::setFilterIntensity,
                onToggleFavourite = lensTray.viewModel::toggleFavourite,
                onRecent = lensTray.viewModel::recordRecent,
                onRequirePro = onRequirePro,
            )
        }

        CameraStudioContent(
            state = state,
            chrome = StudioChrome(
                iconRotation = iconRotation,
                cameraGranted = cameraRequester.allGranted,
                micGranted = micRequester.allGranted,
                micPermanentlyDenied = micRequester.status == PermissionStatus.PERMANENTLY_DENIED,
                prompterShown = prompterShown,
                bannerFilter = swipe.banner,
                bannerKey = swipe.bannerKey,
            ),
            actions = actions,
            level = level,
            clipping = clipping,
            recordingClock = clock,
            atlas = atlas,
            snackbar = snackbar,
            surface = {
                if (state.mode == StudioMode.VIDEO) {
                    when {
                        !cameraRequester.allGranted -> PermissionGate(cameraRequester, stringResource(com.ravango.core.ui.R.string.permission_camera_title), stringResource(R.string.camera_permission_camera_message), Icons.Rounded.Videocam)
                        state.noCamera -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.Rounded.NoPhotography, stringResource(R.string.camera_no_camera_title), stringResource(R.string.camera_no_camera_message))
                        }
                        else -> StudioPreview(
                            settings = state.settings,
                            frame = state.previewFrame,
                            capabilities = caps,
                            controls = state.controls,
                            focus = state.focus,
                            showOverlays = state.isStreaming,
                            iconRotation = iconRotation,
                            onSurface = viewModel::setPreviewSurface,
                            onSurfaceGone = viewModel::clearPreviewSurface,
                            onContainerSize = { container = it },
                            onFocus = viewModel::focusAt,
                            onLock = viewModel::lockAt,
                            onZoom = viewModel::setZoom,
                            onExposure = viewModel::setExposureCompensation,
                            filterSwipeEnabled = state.isStreaming && !state.lensTrayOpen,
                            onFilterDrag = swipe::onDrag,
                            onFilterRelease = swipe::onRelease,
                        )
                    }
                    if (state.settings.showLevel && state.isStreaming) {
                        val angle = rememberGravityAngle(enabled = true)
                        LevelIndicator(angle)
                    }
                } else {
                    AudioStudio(
                        level = level,
                        clipping = clipping,
                        micRequester = micRequester,
                        inputName = state.activeInput?.name,
                        recordingClock = clock,
                        recording = state.isRecording,
                        iconRotation = iconRotation,
                    )
                }
                if (screenFlash) ScreenFlashOverlay(frame)
            },
            prompter = {
                if (prompterShown) PrompterOverlay(prompter!!, controller!!, topInset = 72.dp, onHide = { viewModel.setPrompterVisible(false) })
            },
            railTopPadding = if (prompterShown) screenHeight * 0.2f else 0.dp,
            tray = LensTrayState(looks = lensTray.looks, filter = state.effects.filter, filterIntensity = state.effects.filterIntensity),
            trayActions = trayActions,
        )

        // ---------------- sheets ----------------
        var effectsTab by rememberSaveable { mutableStateOf(EffectsTab.FILTERS) }
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) viewModel.onBackgroundPhotoPicked(uri)
        }
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
            StudioSheet.SETTINGS -> StudioSettingsSheet(caps, state.settings, state.isRecording, viewModel::updateSettings, viewModel::closeSheet)
            StudioSheet.AUDIO -> AudioSheet(
                captureMode = state.settings.captureMode,
                settings = state.audioSettings,
                inputs = state.audioInputs,
                activeInput = state.activeInput,
                monitoringAvailable = state.monitoringAvailable,
                micGranted = micRequester.allGranted,
                entitlements = state.entitlements,
                locked = state.isRecording,
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
            StudioSheet.EFFECTS -> EffectsSheet(
                tab = effectsTab,
                effects = state.effects,
                status = state.effectsStatus,
                entitlements = state.entitlements,
                hasBackgroundImage = state.hasBackgroundImage,
                onTab = { effectsTab = it },
                onFilter = { f -> if (viewModel.setFilter(f)) swipe.showBanner(f) },
                onIntensity = viewModel::setFilterIntensity,
                onBackground = { viewModel.setBackground(it) },
                onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRequirePro = { onRequirePro(EffectsGating.FEATURE) },
                onClearAll = viewModel::clearEffects,
                onDismiss = viewModel::closeSheet,
            )
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

/**
 * Always-dark studio theme that keeps the app's reduce-motion preference (the shared StudioTheme resets it), so
 * screenshot captures and users who reduce motion get still UI.
 */
@Composable
internal fun StudioFrame(content: @Composable () -> Unit) {
    val reduceMotion = RgTheme.reduceMotion
    StudioTheme {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { content() }
    }
}

/**
 * Camera Studio chrome (stateless): top bar, tool rail, status pills, filter banner, bottom controls with the
 * shutter / lens carousel, over the camera [surface]. Fast-changing values (audio level, recording clock) arrive as
 * [State]s read only where drawn.
 */
@Composable
internal fun CameraStudioContent(
    state: CameraUiState,
    chrome: StudioChrome,
    actions: StudioActions,
    level: State<AudioLevel>,
    clipping: State<Boolean>,
    recordingClock: State<RecordingStatus>,
    atlas: ImageBitmap?,
    snackbar: SnackbarHostState,
    surface: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    prompter: @Composable () -> Unit = {},
    railTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    tray: LensTrayState = LensTrayState.LensesOnly,
    trayActions: LensTrayActions = LensTrayActions(),
) {
    val caps = state.capabilities
    val video = state.mode == StudioMode.VIDEO
    val locked = state.isRecording
    val rotation = chrome.iconRotation
    var showIntensity by remember { mutableStateOf(false) }
    val filterActive = state.effects.filter != LiveFilter.NONE

    Box(modifier.fillMaxSize().background(Color.Black)) {
        surface()
        prompter()
        if (video) FilterNameBanner(chrome.bannerFilter, chrome.bannerKey)

        // ---------------- top ----------------
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioTopBar(
                videoMode = if (video && caps != null) videoModeLabel(state.settings.resolution, state.settings.frameRate) else null,
                aspect = if (video) aspectLabel(state.settings.aspectRatio) else null,
                flash = state.settings.flash,
                flashEnabled = video && caps != null && VideoModeSelector.flashModes(caps).size > 1,
                timerSeconds = state.settings.timerSeconds,
                iconRotation = rotation,
                locked = locked,
                onClose = actions.onClose,
                onResolution = { actions.onOpenSheet(StudioSheet.RESOLUTION) },
                onFlash = actions.onFlash,
                onTimer = actions.onTimer,
                onAspect = { actions.onOpenSheet(StudioSheet.ASPECT) },
                onSettings = { actions.onOpenSheet(StudioSheet.SETTINGS) },
            )
            if (state.isRecording) RecordingHud(recordingClock, rotation)
            StatusPills(state, rotation, actions.onUnlockFocus, actions.onResetFocus)
            if (video && filterActive && !state.lensTrayOpen) {
                FilterPill(
                    filter = state.effects.filter,
                    intensity = state.effects.filterIntensity,
                    onClick = { actions.onOpenSheet(StudioSheet.EFFECTS) },
                    onLongPress = { showIntensity = true },
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }

        // ---------------- tool rail ----------------
        StudioToolRail(
            iconRotation = rotation,
            videoMode = video,
            micOn = state.settings.captureMode != CaptureMode.VIDEO_ONLY || !video,
            prompterActive = chrome.prompterShown,
            proOpen = state.proControlsOpen,
            showPro = caps != null && availableProTabs(caps).isNotEmpty(),
            grid = state.settings.grid,
            beautyActive = false,
            onBeauty = { actions.onOpenSheet(StudioSheet.BEAUTY) },
            onPro = actions.onPro,
            onGrid = actions.onGrid,
            onAudio = { actions.onOpenSheet(StudioSheet.AUDIO) },
            onPrompter = actions.onPrompter,
            onCompare = actions.onCompare,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, top = railTopPadding),
        )

        // ---------------- camera errors ----------------
        (state.cameraState as? CameraState.Error)?.let { error ->
            if (video && chrome.cameraGranted) {
                CameraErrorCard(error, onRetry = actions.onRetryCamera, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }

        CountdownOverlay(state.countdown, rotation)

        // ---------------- bottom ----------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                state.proControlsOpen && caps != null && video,
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
                        onExposure = actions.onExposure,
                        onIso = actions.onIso,
                        onShutter = actions.onShutter,
                        onFocus = actions.onFocusDistance,
                        onWhiteBalance = actions.onWhiteBalance,
                        onStabilization = actions.onStabilization,
                        onHdr = actions.onHdr,
                        onReset = actions.onResetManual,
                        onRequirePro = actions.onRequirePro,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            AnimatedVisibility(
                showIntensity && filterActive && video,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                FilterIntensityCard(
                    filter = state.effects.filter,
                    value = state.effects.filterIntensity,
                    onChange = actions.onFilterIntensity,
                    onIdle = { showIntensity = false },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val lensMode = video && state.lensTrayOpen && !state.isRecording
            // The lens under the shutter ring while browsing (may be a locked one that is not applied).
            var focusedLens by remember(lensMode) { mutableStateOf(state.effects.lens) }
            val focusedLocked = focusedLens?.let { !EffectsGating.lensAllowed(it, state.entitlements) } == true
            // In the tray, the carousel shows its own header (name, hints, strength).
            AnimatedVisibility(!lensMode && video && state.effects.lens != null && !state.isRecording, enter = fadeIn(), exit = fadeOut()) {
                LensNameToast(
                    lens = if (lensMode) focusedLens else state.effects.lens,
                    locked = lensMode && focusedLocked,
                    needsFace = state.effectsStatus.lensNeedsFace,
                    unavailable = state.effectsStatus.lensUnavailable && (!lensMode || focusedLens == state.effects.lens),
                )
            }
            if (video && state.isStreaming && !lensMode) {
                LensChips(state.lenses, state.controls.zoomRatio, caps?.cameraId, rotation, onSelect = actions.onZoomLens)
            }
            if (!state.isRecording && !lensMode) {
                StorageLine(state)
            }
            if (state.needsMic && chrome.micGranted && !lensMode) {
                LevelMeterBar(level, clipping, Modifier.width(144.dp), height = 4.dp)
            }

            val streaming = state.isStreaming
            val busy = state.recording.phase == RecordingPhase.STARTING || state.recording.phase == RecordingPhase.FINALIZING ||
                (video && !streaming && !state.isRecording) ||
                (!video && chrome.micPermanentlyDenied)
            AnimatedContent(
                targetState = lensMode,
                transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.96f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.98f)) },
                label = "shutterArea",
            ) { lenses ->
                if (lenses) {
                    LensCarousel(
                        applied = state.effects.lens,
                        entitlements = state.entitlements,
                        atlas = atlas,
                        onFocus = { lens ->
                            focusedLens = lens
                            actions.onLens(lens)
                        },
                        tray = tray,
                        trayActions = trayActions,
                        needsFace = state.effectsStatus.lensNeedsFace,
                        lensUnavailable = state.effectsStatus.lensUnavailable,
                    ) {
                        RecordButton(
                            recording = false,
                            busy = busy,
                            countingDown = state.countdown != null,
                            style = ShutterStyle.LENS,
                            onClick = {
                                if (focusedLocked) actions.onRequirePro(EffectsGating.FEATURE) else actions.onRecord()
                            },
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (state.isRecording) {
                                PauseButton(state.recording.phase == RecordingPhase.PAUSED, rotation, actions.onPauseResume)
                            } else {
                                LastTakeThumbnail(state.lastThumbnailPath, onClick = actions.onOpenLastTake)
                            }
                        }
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (video && !state.isRecording) {
                                EffectsButton(filterActive || state.effects.background.active, rotation) { actions.onOpenSheet(StudioSheet.EFFECTS) }
                            }
                        }
                        RecordButton(
                            recording = state.isRecording,
                            busy = busy,
                            countingDown = state.countdown != null,
                            style = if (video) ShutterStyle.VIDEO else ShutterStyle.AUDIO,
                            onClick = actions.onRecord,
                        )
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (video && !state.isRecording) LensesButton(state.effects.lens != null || tray.looks?.activeId != null, rotation, actions.onToggleLenses)
                        }
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (video && state.facings.size > 1) FlipButton(enabled = !state.isRecording, iconRotation = rotation, onClick = actions.onFlip)
                        }
                    }
                }
            }
            AnimatedVisibility(!state.isRecording && state.countdown == null && !lensMode) {
                RgSegmentedControl(
                    options = StudioMode.entries,
                    selected = state.mode,
                    onSelect = actions.onMode,
                    label = { stringResource(if (it == StudioMode.VIDEO) R.string.camera_mode_video else R.string.camera_mode_audio) },
                    glass = true,
                )
            }
            AnimatedVisibility(lensMode) {
                Text(
                    stringResource(R.string.camera_lenses_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .height(StudioGlass.Target)
                        .clip(RoundedCornerShape(50))
                        .background(StudioGlass.Fill)
                        .pressable(onClick = actions.onToggleLenses)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 232.dp))
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
    is StudioMessage.TakeLost -> context.getString(
        if (message.reason == StopReason.CAMERA_ERROR) R.string.camera_msg_take_lost_camera else R.string.camera_msg_take_lost_encoder,
    )
    StudioMessage.BackgroundPhotoFailed -> context.getString(R.string.camera_bg_photo_failed)
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
private fun PermissionGate(requester: PermissionRequester, title: String, message: String, icon: ImageVector) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
        // While the engine still retries (lighter request/stream) a rejected session is not yet "unsupported".
        CameraErrorKind.CONFIGURATION -> if (error.retrying) {
            R.string.camera_error_generic_title to R.string.camera_error_generic_message
        } else {
            R.string.camera_error_config_title to R.string.camera_error_config_message
        }
        else -> R.string.camera_error_generic_title to R.string.camera_error_generic_message
    }
    GlassSurface(modifier.fillMaxWidth(), tint = Color(0xE6141220)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Pill(stringResource(R.string.camera_focus_auto), StudioGlass.Fill, Color.White, null, onResetFocus)
        }
        if (state.thermal >= ThermalLevel.HOT) {
            Pill(stringResource(R.string.camera_thermal_hot), Palette.Rose400.copy(alpha = 0.92f), Color.Black, Icons.Rounded.Thermostat, null)
        }
        if (state.mode == StudioMode.VIDEO && abs(state.controls.zoomRatio - 1f) > 0.05f && state.lenses.size <= 1) {
            Pill(zoomLabel(state.controls.zoomRatio), StudioGlass.Fill, Color.White, null, null)
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color, icon: ImageVector?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.SemiBold)
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
        color = if (seconds < 300) Palette.Butter400 else Color.White.copy(alpha = 0.8f),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Audio-only mode: a large live level visual instead of the camera. */
@Composable
private fun AudioStudio(
    level: State<AudioLevel>,
    clipping: State<Boolean>,
    micRequester: PermissionRequester,
    inputName: String?,
    recordingClock: State<RecordingStatus>,
    recording: Boolean,
    iconRotation: Float,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
                    .size(224.dp)
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
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            LevelMeterBar(level, clipping, Modifier.width(240.dp), height = 8.dp)
            Spacer(Modifier.height(16.dp))
            if (recording) {
                Text(formatDuration(recordingClock.value.durationUs), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            if (inputName != null) {
                Text(inputName, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
