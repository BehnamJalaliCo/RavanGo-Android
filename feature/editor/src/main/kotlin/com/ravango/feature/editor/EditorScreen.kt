package com.ravango.feature.editor

import com.ravango.core.designsystem.motion.RgEnter
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.motion.RgExit
import com.ravango.core.designsystem.motion.SharedKeys
import com.ravango.core.designsystem.motion.rgSharedBounds
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AspectRatio
import com.ravango.core.designsystem.theme.Radius
import com.ravango.feature.editor.ui.timecode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.State
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.media.WaveformProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.ProFeature
import com.ravango.core.ui.HardwareKeyHandler
import com.ravango.core.ui.message
import com.ravango.engine.editor.export.EditorError
import com.ravango.feature.editor.preview.PreviewPane
import com.ravango.feature.editor.preview.Transport
import com.ravango.feature.editor.timeline.EditorTimeline
import com.ravango.feature.editor.timeline.TimelineActions
import com.ravango.feature.editor.tools.ToolPanelHost
import com.ravango.feature.editor.tools.ToolRail
import com.ravango.feature.editor.tools.TransitionSheet
import com.ravango.feature.editor.ui.localized

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onExport: (projectId: String) -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    onOpenSettings: () -> Unit,
    vm: EditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playhead = vm.playheadUs.collectAsStateWithLifecycle()
    val isPlaying = vm.isPlaying.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    var transitionFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is EditorEvent.RequirePro -> onRequirePro(e.feature)
                EditorEvent.OpenExport -> onExport(vm.projectId)
                EditorEvent.OpenSettings -> onOpenSettings()
                is EditorEvent.ProjectCreated -> Unit
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }
    HardwareKeyHandler { e ->
        if (e.action != KeyEvent.ACTION_DOWN) return@HardwareKeyHandler false
        when (e.keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { vm.togglePlay(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { vm.stepFrame(false); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.stepFrame(true); true }
            else -> false
        }
    }
    BackHandler(enabled = state.tool != null || state.selection != Selection.None) {
        if (state.tool != null) vm.openTool(null) else vm.select(Selection.None)
    }

    val message = state.message
    val messageText = message?.let { messageText(it) }
    LaunchedEffect(message?.id) {
        if (message != null && messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.consumeMessage(message.id)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> vm.importMedia(uris) }
    val launchPicker = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    StudioTheme {
        EditorContent(
            state = state,
            actions = vm,
            playhead = playhead,
            isPlaying = isPlaying,
            thumbnails = vm.thumbnails,
            waveforms = vm.waveforms,
            snackbarHostState = snackbar,
            onBack = { vm.onStop(); onBack() },
            onAddMedia = launchPicker,
            onTransition = { transitionFor = it },
            surface = { PlayerSurface(player = vm.preview.player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW) },
        )
        transitionFor?.let { clipId ->
            val clip = state.document.mainTrack.firstOrNull { it.id == clipId }
            if (clip == null) transitionFor = null
            else TransitionSheet(clip.transitionOut, onApply = { t, d -> vm.setTransition(clipId, t, d) }, onApplyAll = vm::setTransitionForAll, onDismiss = { transitionFor = null })
        }
    }
}

/** Stateless editor body (top bar, preview, transport, timeline, tool panel and rail, busy overlay). */
@Composable
internal fun EditorContent(
    state: EditorUiState,
    actions: EditorActions,
    playhead: State<Long>,
    isPlaying: State<Boolean>,
    thumbnails: ThumbnailProvider,
    waveforms: WaveformProvider,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onTransition: (clipId: String) -> Unit,
    surface: @Composable () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val vm = actions
    // Opened from a project card: the card grows into the whole editor (container transform).
    val sharedKey = state.projectId.takeIf { it.isNotEmpty() }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (sharedKey != null) Modifier.rgSharedBounds(SharedKeys.project(sharedKey), RoundedCornerShape(Radius.lg)) else Modifier)
            .background(RgTheme.colors.background),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            EditorTopBar(state, onBack = onBack, vm = vm)
            when {
                state.loading -> LoadingState(Modifier.weight(1f))
                state.notFound -> EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(R.string.editor_not_found_title),
                    message = stringResource(R.string.editor_not_found_message),
                    modifier = Modifier.weight(1f),
                    actionText = stringResource(R.string.editor_back),
                    onAction = onBack,
                )
                state.isEmpty -> EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.editor_empty_title),
                    message = stringResource(R.string.editor_empty_message),
                    modifier = Modifier.weight(1f),
                    actionText = stringResource(R.string.editor_import_media),
                    onAction = onAddMedia,
                )
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    PreviewPane(
                        surface = surface,
                        document = state.document,
                        selection = state.selection,
                        overlaySizes = state.overlaySizes,
                        isPlaying = isPlaying,
                        building = state.previewBuilding,
                        error = state.previewError,
                        onTogglePlay = vm::togglePlay,
                        onTransform = vm::transformSelection,
                        onGestureEnd = vm::endGesture,
                        modifier = Modifier.fillMaxSize(),
                    )
                        TimePill(playhead, state.document.durationUs, Modifier.align(Alignment.BottomCenter).padding(Spacing.md))
                    }
                    Transport(
                        isPlaying = isPlaying,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onUndo = vm::undo,
                        onRedo = vm::redo,
                        onToggle = vm::togglePlay,
                        onStep = vm::stepFrame,
                        onSplit = vm::split,
                        onAddMedia = onAddMedia,
                    )
                    val timelineActions = remember(vm) {
                        TimelineActions(
                            onSeek = vm::seekTo,
                            onSelect = vm::select,
                            onTrimClip = vm::trimClip,
                            onMoveClip = vm::moveClip,
                            onTransition = onTransition,
                            onRetimeOverlay = vm::retimeOverlay,
                            onTrimAudio = vm::trimAudio,
                            onMoveAudio = vm::moveAudio,
                            onRetimeCue = vm::retimeCue,
                            onGestureEnd = vm::endGesture,
                        )
                    }
                    EditorTimeline(
                        document = state.document,
                        selection = state.selection,
                        playhead = playhead,
                        reversing = state.reversing,
                        thumbnails = thumbnails,
                        waveforms = waveforms,
                        actions = timelineActions,
                    )
                    ToolPanelHost(state, vm)
                    ToolRail(state, vm::openTool)
                }
            }
        }
        snackbarHostState?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) }
        AnimatedVisibility(state.busy != null, enter = RgEnter.fade(), exit = RgExit.fade()) {
            BusyOverlay(state, vm::cancelBusy)
        }
    }
}

@Composable
private fun EditorTopBar(state: EditorUiState, onBack: () -> Unit, vm: EditorActions) {
    // One 40dp control height across the bar; undo/redo live in the transport row so the title has room.
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RgIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.editor_back), onBack, glass = true, size = 40.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(state.projectTitle, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.sm))
        if (!state.loading && !state.notFound) {
            RgChip(
                localized(state.document.canvas.aspectRatio.label),
                selected = state.tool == EditorTool.CANVAS,
                onClick = { vm.openTool(EditorTool.CANVAS) },
                glass = true,
                icon = Icons.Rounded.AspectRatio,
                modifier = Modifier.height(40.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        RgPrimaryButton(
            stringResource(R.string.editor_export),
            vm::openExport,
            size = RgButtonSize.MEDIUM,
            enabled = !state.loading && state.document.mainTrack.isNotEmpty() && state.busy == null,
        )
    }
}

/** Current time / duration, always left-to-right like the timeline. */
@Composable
private fun TimePill(playhead: State<Long>, durationUs: Long, modifier: Modifier = Modifier) {
    Text(
        "\u2066" + timecode(playhead.value) + " / " + timecode(durationUs, tenths = false) + "\u2069",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

@Composable
private fun BusyOverlay(state: EditorUiState, onCancel: () -> Unit) {
    val busy = state.busy ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.widthIn(max = 320.dp).padding(Spacing.xl)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(busy.label), style = MaterialTheme.typography.titleMedium, color = Color.White)
                val p = busy.progress
                if (p != null) {
                    RgProgressBar(p, Modifier.fillMaxWidth())
                    Text(localized("${(p * 100).toInt()}") + "%", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                } else {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (busy.cancellable) RgTextButton(stringResource(R.string.editor_cancel), onClick = onCancel)
            }
        }
    }
}

@Composable
internal fun messageText(m: UiMessage): String? {
    val base = when {
        m.text != null -> stringResource(m.text)
        m.editorError != null -> stringResource(m.editorError.messageRes())
        m.errorKind != null -> m.errorKind.message()
        else -> null
    } ?: return null
    return if (m.errorKind != null && !m.detail.isNullOrBlank() && m.text == null) "$base\n${m.detail}" else base
}

fun EditorError.messageRes(): Int = when (this) {
    EditorError.ENCODER_UNSUPPORTED -> R.string.editor_err_encoder
    EditorError.DECODER_UNSUPPORTED -> R.string.editor_err_decoder
    EditorError.SOURCE_MISSING -> R.string.editor_err_source_missing
    EditorError.PERMISSION -> R.string.editor_err_permission
    EditorError.STORAGE_FULL -> R.string.editor_err_storage
    EditorError.PROCESSING -> R.string.editor_err_processing
    EditorError.AUDIO_PROCESSING -> R.string.editor_err_audio
    EditorError.MUXING -> R.string.editor_err_muxing
    EditorError.NOT_ALL_INTRA -> R.string.editor_err_reverse_unsupported
    EditorError.CANCELLED -> R.string.editor_err_cancelled
    EditorError.UNKNOWN -> R.string.editor_err_unknown
}
