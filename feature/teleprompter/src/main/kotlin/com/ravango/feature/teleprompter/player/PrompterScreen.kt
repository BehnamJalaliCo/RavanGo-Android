package com.ravango.feature.teleprompter.player

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatDurationMs
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.ProFeature
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.ui.HardwareKeyHandler
import com.ravango.core.ui.KeepScreenOn
import com.ravango.core.ui.MaxBrightness
import com.ravango.engine.teleprompter.PrompterController
import com.ravango.engine.teleprompter.PrompterPhase
import com.ravango.engine.teleprompter.PrompterSnapshot
import com.ravango.engine.teleprompter.TeleprompterView
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.teleprompter.R
import com.ravango.feature.teleprompter.floating.rememberFloatingPrompterLauncher
import com.ravango.feature.teleprompter.input.PrompterKeyAction
import com.ravango.feature.teleprompter.input.PrompterKeyMapper
import kotlinx.coroutines.delay

@Composable
internal fun PrompterRoute(
    onBack: () -> Unit,
    onOpenSettings: (scriptId: String) -> Unit,
    onRecord: (scriptId: String) -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    viewModel: PrompterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StudioTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                PrompterUiState.Loading -> LoadingState()
                PrompterUiState.NotFound -> EmptyState(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.prompter_not_found_title),
                    message = stringResource(R.string.prompter_not_found_message),
                    actionText = stringResource(com.ravango.core.ui.R.string.action_back),
                    onAction = onBack,
                    modifier = Modifier.align(Alignment.Center),
                )
                is PrompterUiState.Ready -> PrompterContent(s, viewModel, onBack, onOpenSettings, onRecord, onRequirePro)
            }
        }
    }
}

@Composable
private fun PrompterContent(
    state: PrompterUiState.Ready,
    viewModel: PrompterViewModel,
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onRecord: (String) -> Unit,
    onRequirePro: (ProFeature) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val settings = state.settings
    val script = state.script
    val controller = rememberPrompterController(script.body, settings, state.startOffset)
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val sections by controller.sections.collectAsStateWithLifecycle()

    ImmersiveSystemBars()
    KeepScreenOn(state.keepScreenOn)
    var maxBrightness by rememberSaveable { mutableStateOf(false) }
    MaxBrightness(maxBrightness)
    var rotationLocked by rememberSaveable { mutableStateOf(false) }
    OrientationLock(rotationLocked)

    // Auto-hiding controls: visible while idle/paused, fade out 2.8 s after the last touch while scrolling.
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val poke: () -> Unit = { controlsVisible = true; interaction++ }
    val running = snapshot.phase == PrompterPhase.SCROLLING || snapshot.phase == PrompterPhase.COUNTDOWN
    LaunchedEffect(running, interaction, snapshot.held) {
        if (running && !snapshot.held) {
            delay(2_800)
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    // Persist the reading position on pause / finish / leaving, so the next session can resume.
    val currentSnapshot by rememberUpdatedState(snapshot)
    fun persistPosition() {
        val snap = controller.snapshot.value
        when (snap.phase) {
            PrompterPhase.FINISHED -> viewModel.saveReadingPosition(0)
            PrompterPhase.IDLE -> if (snap.readingCharOffset > 0) viewModel.saveReadingPosition(snap.readingCharOffset)
            else -> viewModel.saveReadingPosition(snap.readingCharOffset)
        }
    }
    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == PrompterPhase.PAUSED || snapshot.phase == PrompterPhase.FINISHED) persistPosition()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (currentSnapshot.phase == PrompterPhase.SCROLLING || currentSnapshot.phase == PrompterPhase.COUNTDOWN) controller.pause()
        persistPosition()
    }
    BackHandler {
        persistPosition()
        onBack()
    }

    // Live speed changes (buttons, keys, remotes) are saved back into the settings after a short debounce.
    LaunchedEffect(snapshot.wordsPerMinute) {
        val wpm = snapshot.wordsPerMinute
        if (wpm != settings.wordsPerMinute) {
            delay(600)
            viewModel.updateSettings { it.copy(wordsPerMinute = wpm) }
        }
    }

    // Hardware keys: volume buttons and Bluetooth remotes / page turners / keyboards.
    val mapper = remember { PrompterKeyMapper() }
    mapper.volumeEnabled = settings.volumeKeysControl
    mapper.remoteEnabled = settings.remoteControl
    mapper.volumeMode = state.volumeKeyMode
    HardwareKeyHandler(enabled = settings.volumeKeysControl || settings.remoteControl) { event ->
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (!isDown && event.action != KeyEvent.ACTION_UP) return@HardwareKeyHandler false
        val result = mapper.onKey(event.keyCode, isDown, event.repeatCount, event.isLongPress)
        when (val action = result.action) {
            PrompterKeyAction.Toggle -> { controller.toggle(); haptics.perform(HapticEvent.TOGGLE_ON) }
            is PrompterKeyAction.Speed -> controller.nudgeSpeed(action.deltaWpm)
            is PrompterKeyAction.Page -> controller.scrollBy(action.viewportFraction)
            null -> Unit
        }
        if (result.action != null) poke()
        result.consumed
    }

    var showSections by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }

    val launchFloating = rememberFloatingPrompterLauncher(
        isPro = state.floatingAvailable,
        onRequirePro = { onRequirePro(ProFeature.FLOATING_PROMPTER) },
        onStarted = {
            controller.pause()
            persistPosition()
            Toast.makeText(context, context.getString(R.string.prompter_floating_started), Toast.LENGTH_LONG).show()
        },
    )

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Observe (never consume) every touch so the controls reappear.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    poke()
                }
            },
    ) {
        TeleprompterView(
            text = script.body,
            settings = settings,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            startCharOffset = state.startOffset,
            interactive = true,
            onFontSizeChange = { size -> viewModel.updateSettings { it.copy(fontSizeSp = size) } },
        )

        // Minimal status while controls are hidden.
        AnimatedVisibility(
            visible = !controlsVisible && (settings.showProgress || settings.showRemainingTime),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MiniStatus(snapshot, settings)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopControls(
                title = script.title,
                onBack = { persistPosition(); onBack() },
                onSettings = { persistPosition(); controller.pause(); onOpenSettings(script.id) },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomControls(
                snapshot = snapshot,
                settings = settings,
                hasSections = sections.isNotEmpty(),
                rotationLocked = rotationLocked,
                maxBrightness = maxBrightness,
                floatingIsPro = state.floatingAvailable,
                controller = controller,
                onPoke = poke,
                onSettings = { transform -> viewModel.updateSettings(transform) },
                onToggleRotationLock = { rotationLocked = !rotationLocked },
                onToggleBrightness = { maxBrightness = !maxBrightness },
                onSections = { showSections = true },
                onStartPoint = { showStart = true },
                onFloating = { launchFloating(script.id) },
                onRecord = { persistPosition(); controller.pause(); onRecord(script.id) },
            )
        }

        if (state.resumeOffset > 0 && snapshot.phase == PrompterPhase.IDLE) {
            ResumeOffer(
                modifier = Modifier.align(Alignment.Center),
                onResume = {
                    viewModel.setStartOffset(state.resumeOffset)
                    controller.jumpToChar(state.resumeOffset)
                },
                onFromStart = { viewModel.consumeResumeOffer() },
            )
        }
    }

    if (showSections) {
        SectionsSheet(
            sections = sections,
            currentIndex = snapshot.currentSectionIndex,
            onSelect = { index ->
                controller.jumpToSection(index)
                showSections = false
            },
            onDismiss = { showSections = false },
        )
    }
    if (showStart) {
        StartPointSheet(
            body = script.body,
            direction = settings.direction,
            sections = sections,
            resumeOffset = script.startCharOffset.takeIf { it > 0 },
            onSelect = { offset ->
                viewModel.setStartOffset(offset)
                controller.jumpToChar(offset)
                showStart = false
            },
            onDismiss = { showStart = false },
        )
    }
}

@Composable
private fun MiniStatus(snapshot: PrompterSnapshot, settings: TeleprompterSettings) {
    Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        if (settings.showProgress) {
            RgProgressBar(snapshot.progress, height = 3.dp, trackColor = Color.White.copy(alpha = 0.12f))
        }
        if (settings.showRemainingTime) {
            Text(
                text = "−" + formatDurationMs(snapshot.remainingMs),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TopControls(title: String, onBack: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .safeDrawingPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RgIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(com.ravango.core.ui.R.string.action_back), onBack, glass = true)
        Spacer(Modifier.width(Spacing.md))
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RgIconButton(Icons.Rounded.Tune, stringResource(R.string.prompter_settings), onSettings, glass = true)
    }
}

@Composable
private fun BottomControls(
    snapshot: PrompterSnapshot,
    settings: TeleprompterSettings,
    hasSections: Boolean,
    rotationLocked: Boolean,
    maxBrightness: Boolean,
    floatingIsPro: Boolean,
    controller: PrompterController,
    onPoke: () -> Unit,
    onSettings: ((TeleprompterSettings) -> TeleprompterSettings) -> Unit,
    onToggleRotationLock: () -> Unit,
    onToggleBrightness: () -> Unit,
    onSections: () -> Unit,
    onStartPoint: () -> Unit,
    onFloating: () -> Unit,
    onRecord: () -> Unit,
) {
    val haptics = rememberHaptics()
    val playing = snapshot.phase == PrompterPhase.SCROLLING || snapshot.phase == PrompterPhase.COUNTDOWN
    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(Spacing.md), contentAlignment = Alignment.BottomCenter) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            shape = RoundedCornerShape(Radius.xl),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
            tint = Color(0xCC121020),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (settings.showProgress || settings.showRemainingTime) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDurationMs(snapshot.elapsedMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(Spacing.sm))
                        RgProgressBar(snapshot.progress, modifier = Modifier.weight(1f), height = 4.dp, trackColor = Color.White.copy(alpha = 0.12f))
                        Spacer(Modifier.width(Spacing.sm))
                        if (settings.showRemainingTime) {
                            Text("−" + formatDurationMs(snapshot.remainingMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                // Transport
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RgIconButton(Icons.Rounded.Replay, stringResource(R.string.prompter_restart), { onPoke(); controller.restart() }, glass = true)
                    RgIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.prompter_prev_section), { onPoke(); controller.previousSection() }, glass = true, enabled = hasSections)
                    RgIconButton(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (playing) R.string.prompter_pause else R.string.prompter_play),
                        {
                            onPoke()
                            haptics.perform(if (playing) HapticEvent.TOGGLE_OFF else HapticEvent.TOGGLE_ON)
                            controller.toggle()
                        },
                        size = 68.dp,
                        iconSize = 34.dp,
                        container = RgTheme.colors.accent,
                        tint = RgTheme.colors.onAccent,
                    )
                    RgIconButton(Icons.Rounded.SkipNext, stringResource(R.string.prompter_next_section), { onPoke(); controller.nextSection() }, glass = true, enabled = hasSections)
                    RgIconButton(Icons.AutoMirrored.Rounded.List, stringResource(R.string.prompter_sections), { onPoke(); onSections() }, glass = true, enabled = hasSections)
                }
                // Speed and size
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RgIconButton(Icons.Rounded.Remove, stringResource(R.string.prompter_speed_down), { onPoke(); controller.nudgeSpeed(-10) }, glass = true, size = 40.dp)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            snapshot.wordsPerMinute.toString().localizeDigits(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.prompter_wpm), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    }
                    RgIconButton(Icons.Rounded.Add, stringResource(R.string.prompter_speed_up), { onPoke(); controller.nudgeSpeed(10) }, glass = true, size = 40.dp)
                    Spacer(Modifier.width(Spacing.lg))
                    RgIconButton(Icons.Rounded.TextDecrease, stringResource(R.string.prompter_font_smaller), {
                        onPoke(); onSettings { it.copy(fontSizeSp = (it.fontSizeSp - 2f).coerceAtLeast(TeleprompterSettings.MIN_FONT_SP)) }
                    }, glass = true, size = 40.dp)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(settings.fontSizeSp.toInt().toString().localizeDigits(), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.prompter_font_size_short), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    }
                    RgIconButton(Icons.Rounded.TextIncrease, stringResource(R.string.prompter_font_larger), {
                        onPoke(); onSettings { it.copy(fontSizeSp = (it.fontSizeSp + 2f).coerceAtMost(TeleprompterSettings.MAX_FONT_SP)) }
                    }, glass = true, size = 40.dp)
                }
                if (settings.mirrorHorizontal || settings.mirrorVertical) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = RgTheme.colors.accent, modifier = Modifier.padding(end = Spacing.sm))
                        Text(stringResource(R.string.prompter_mirror_hint), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Tools
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    item {
                        RgChip(stringResource(R.string.prompter_mirror_h), settings.mirrorHorizontal, {
                            onPoke(); onSettings { it.copy(mirrorHorizontal = !it.mirrorHorizontal) }
                        }, icon = Icons.Rounded.Flip, glass = true)
                    }
                    item {
                        RgChip(stringResource(R.string.prompter_mirror_v), settings.mirrorVertical, {
                            onPoke(); onSettings { it.copy(mirrorVertical = !it.mirrorVertical) }
                        }, icon = Icons.Rounded.SwapVert, glass = true)
                    }
                    item { RgChip(stringResource(R.string.prompter_start_point), false, { onPoke(); onStartPoint() }, icon = Icons.Rounded.Flag, glass = true) }
                    item { RgChip(stringResource(R.string.prompter_rotation_lock), rotationLocked, { onPoke(); onToggleRotationLock() }, icon = Icons.Rounded.ScreenLockRotation, glass = true) }
                    item { RgChip(stringResource(R.string.prompter_brightness), maxBrightness, { onPoke(); onToggleBrightness() }, icon = Icons.Rounded.BrightnessHigh, glass = true) }
                    item {
                        val proBadge: (@Composable () -> Unit)? = if (floatingIsPro) null else { { ProBadge() } }
                        RgChip(
                            stringResource(R.string.prompter_floating),
                            false,
                            { onPoke(); onFloating() },
                            icon = Icons.Rounded.PictureInPictureAlt,
                            glass = true,
                            trailing = proBadge,
                        )
                    }
                    item { RgChip(stringResource(R.string.prompter_record), false, { onPoke(); onRecord() }, icon = Icons.Rounded.Videocam, glass = true) }
                }
            }
        }
    }
}

@Composable
private fun ResumeOffer(modifier: Modifier, onResume: () -> Unit, onFromStart: () -> Unit) {
    GlassSurface(modifier.padding(Spacing.xl).widthIn(max = 420.dp), tint = Color(0xE6141220)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.prompter_resume_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.prompter_resume_message), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgSecondaryButton(stringResource(R.string.prompter_from_beginning), onFromStart, size = RgButtonSize.MEDIUM)
                RgPrimaryButton(stringResource(R.string.prompter_resume), onResume, size = RgButtonSize.MEDIUM)
            }
        }
    }
}
