package com.ravango.feature.scripts.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatTextdirectionRToL
import androidx.compose.material.icons.rounded.Schedule
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatDurationMs
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.motion.SharedKeys
import com.ravango.core.designsystem.motion.rgFadeThrough
import com.ravango.core.designsystem.motion.rgSharedBounds
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.ContentDirection
import com.ravango.core.ui.detectDirection
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import com.ravango.engine.teleprompter.prompterInlineContent
import com.ravango.engine.teleprompter.rememberPrompterAnnotatedString
import com.ravango.feature.scripts.R
import kotlinx.coroutines.launch
import com.ravango.core.ui.R as UiR

@Composable
internal fun ScriptEditorRoute(
    onBack: () -> Unit,
    onNavigate: (EditorNavAction, String) -> Unit,
    onOpenPaywall: (feature: String?) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ScriptEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAi by rememberSaveable { mutableStateOf(false) }
    var showOverlayRationale by remember { mutableStateOf(false) }
    var pendingFloatingId by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Returning from the overlay-permission screen: continue launching the floating prompter.
        val id = pendingFloatingId
        if (id != null && FloatingPrompterStarter.canDrawOverlays(context)) {
            pendingFloatingId = null
            FloatingPrompterStarter.start(context, id)
        }
    }
    BackHandler {
        viewModel.flush()
        onBack()
    }

    val emptyMessage = stringResource(R.string.scripts_editor_empty_action)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditorEvent.OpenPaywall -> onOpenPaywall(null)
                EditorEvent.EmptyScript -> scope.launch { snackbar.showSnackbar(emptyMessage) }
                is EditorEvent.Navigate -> when (event.action) {
                    EditorNavAction.FLOATING -> when {
                        !state.floatingIsPro -> onOpenPaywall(com.ravango.core.model.ProFeature.FLOATING_PROMPTER.name)
                        !FloatingPrompterStarter.canDrawOverlays(context) -> {
                            pendingFloatingId = event.scriptId
                            showOverlayRationale = true
                        }
                        else -> FloatingPrompterStarter.start(context, event.scriptId)
                    }
                    else -> onNavigate(event.action, event.scriptId)
                }
            }
        }
    }

    ScriptEditorContent(
        state = state,
        stats = stats,
        snackbarHostState = snackbar,
        onBack = { viewModel.flush(); onBack() },
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onPreview = viewModel::setPreview,
        onNavigate = viewModel::navigate,
        onTitleChange = viewModel::onTitleChange,
        onBodyChange = viewModel::onBodyChange,
        onDirection = viewModel::setDirection,
        onEdit = viewModel::applyEdit,
        onAi = { showAi = true },
    )

    if (showAi) {
        AiAssistSheet(
            state = aiState,
            hasSelection = !state.body.selection.collapsed,
            onRun = viewModel::runAi,
            onTone = viewModel::setAiTone,
            onCancel = viewModel::cancelAi,
            onReplace = viewModel::replaceWithAi,
            onInsertBelow = viewModel::insertAiBelow,
            onDiscard = viewModel::clearAiResult,
            onOpenSettings = { showAi = false; viewModel.flush(); onOpenSettings() },
            onOpenAiStudio = { showAi = false; viewModel.navigate(EditorNavAction.AI_STUDIO) },
            onDismiss = { showAi = false },
        )
    }
    if (showOverlayRationale) {
        RgConfirmDialog(
            title = stringResource(R.string.scripts_editor_overlay_title),
            message = stringResource(R.string.scripts_editor_overlay_message),
            confirmText = stringResource(UiR.string.permission_open_settings),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = {
                showOverlayRationale = false
                runCatching { context.startActivity(FloatingPrompterStarter.overlaySettingsIntent(context)) }
            },
            onDismiss = { showOverlayRationale = false; pendingFloatingId = null },
        )
    }
}

/** Stateless script editor (rendered by screenshot tests with sample data). */
@Composable
internal fun ScriptEditorContent(
    state: EditorUiState,
    stats: EditorStats,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPreview: (Boolean) -> Unit,
    onNavigate: (EditorNavAction) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (TextFieldValue) -> Unit,
    onDirection: (ContentDirection) -> Unit,
    onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onAi: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    RgScreen(
        title = stringResource(if (state.scriptId == null && !state.loading) R.string.scripts_editor_new_title else R.string.scripts_editor_title),
        subtitle = saveStatusText(state.saveStatus),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (!state.loading && !state.notFound) {
                RgIconButton(
                    if (state.preview) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                    stringResource(if (state.preview) R.string.scripts_editor_edit_mode else R.string.scripts_editor_preview_mode),
                    { onPreview(!state.preview) },
                    selected = state.preview,
                )
                MoreMenu(state.floatingIsPro, onNavigate)
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.scripts_editor_not_found_title),
                message = stringResource(R.string.scripts_editor_not_found_message),
                actionText = stringResource(UiR.string.action_back),
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> EditorBody(
                state = state,
                stats = stats,
                onUndo = onUndo,
                onRedo = onRedo,
                onTitleChange = onTitleChange,
                onBodyChange = onBodyChange,
                onDirection = onDirection,
                onEdit = onEdit,
                onNavigate = onNavigate,
                onAi = onAi,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun saveStatusText(status: SaveStatus): String? = when (status) {
    SaveStatus.IDLE -> null
    SaveStatus.PENDING, SaveStatus.SAVING -> stringResource(R.string.scripts_editor_saving)
    SaveStatus.SAVED -> stringResource(R.string.scripts_editor_saved)
    SaveStatus.FAILED -> stringResource(R.string.scripts_editor_save_failed)
}

@Composable
private fun MoreMenu(floatingIsPro: Boolean, onAction: (EditorNavAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RgIconButton(Icons.Rounded.MoreVert, stringResource(UiR.string.action_more), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(Icons.Rounded.Tune, stringResource(R.string.scripts_editor_prompter_settings)) { open = false; onAction(EditorNavAction.PROMPTER_SETTINGS) }
            MenuItem(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.scripts_editor_floating), trailing = if (floatingIsPro) null else ({ ProBadge() })) {
                open = false; onAction(EditorNavAction.FLOATING)
            }
            MenuItem(Icons.Rounded.AutoAwesome, stringResource(R.string.scripts_editor_ai_studio)) { open = false; onAction(EditorNavAction.AI_STUDIO) }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(icon, null, tint = RgTheme.colors.accent) },
        trailingIcon = trailing,
        onClick = onClick,
    )
}

@Composable
private fun EditorBody(
    state: EditorUiState,
    stats: EditorStats,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (TextFieldValue) -> Unit,
    onDirection: (ContentDirection) -> Unit,
    onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onNavigate: (EditorNavAction) -> Unit,
    onAi: () -> Unit,
    modifier: Modifier,
) {
    val colors = RgTheme.colors
    val ui = LocalLayoutDirection.current
    val contentDirection = when (state.direction) {
        ContentDirection.RTL -> LayoutDirection.Rtl
        ContentDirection.LTR -> LayoutDirection.Ltr
        ContentDirection.AUTO -> detectDirection(state.body.text) ?: detectDirection(state.title) ?: ui
    }
    val textDirection = when (state.direction) {
        ContentDirection.RTL -> TextDirection.Rtl
        ContentDirection.LTR -> TextDirection.Ltr
        ContentDirection.AUTO -> if (contentDirection == LayoutDirection.Rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr
    }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 18.sp,
        lineHeight = 31.sp,
        color = colors.textPrimary,
        textDirection = textDirection,
    )

    // Opened from a script card: the card's bounds grow into this body (container transform).
    val sharedKey = remember { state.scriptId }
    val reduceMotion = RgTheme.reduceMotion
    Column(
        modifier
            .fillMaxSize()
            .then(if (sharedKey != null) Modifier.rgSharedBounds(SharedKeys.script(sharedKey), RoundedCornerShape(Radius.lg)) else Modifier)
            .imePadding(),
    ) {
        // Title
        CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xs)) {
                if (state.title.isEmpty()) {
                    Text(stringResource(R.string.scripts_editor_title_hint), style = MaterialTheme.typography.headlineSmall, color = colors.textTertiary)
                }
                BasicTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.textPrimary, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().testTag("script_title"),
                )
            }
        }
        // Stats + direction
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.md, top = Spacing.xs, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatPill(Icons.Rounded.Schedule, formatDurationMs(stats.durationMs))
            StatPill(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.scripts_editor_words, stats.words.toString().localizeDigits()))
            Spacer(Modifier.weight(1f))
            DirectionMenu(state.direction, onDirection)
        }
        HorizontalDivider(color = colors.outline)

        AnimatedContent(
            targetState = state.preview,
            transitionSpec = { rgFadeThrough(reduceMotion, clipSize = true) },
            modifier = Modifier.weight(1f),
            label = "editor-mode",
        ) { preview ->
            CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                if (preview) {
                    MarkupPreview(state.body.text, bodyStyle)
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    ) {
                        if (state.body.text.isEmpty()) {
                            Text(stringResource(R.string.scripts_editor_body_hint), style = bodyStyle.copy(color = colors.textTertiary))
                        }
                        BasicTextField(
                            value = state.body,
                            onValueChange = onBodyChange,
                            textStyle = bodyStyle,
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().testTag("script_body"),
                        )
                    }
                }
            }
        }

        MarkupToolbar(
            enabled = !state.preview,
            direction = contentDirection,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onUndo = onUndo,
            onRedo = onRedo,
            onEdit = onEdit,
            onAi = onAi,
            onPrompter = { onNavigate(EditorNavAction.PROMPTER) },
            onRecord = { onNavigate(EditorNavAction.RECORD) },
        )
    }
}

@Composable
private fun directionShortLabel(direction: ContentDirection): String = stringResource(
    when (direction) {
        ContentDirection.AUTO -> R.string.scripts_editor_direction_auto
        ContentDirection.RTL -> R.string.scripts_editor_direction_rtl
        ContentDirection.LTR -> R.string.scripts_editor_direction_ltr
    },
)

@Composable
private fun MarkupPreview(text: String, style: TextStyle) {
    val colors = RgTheme.colors
    val parsed = remember(text) { ScriptMarkup.parse(text) }
    val annotated = rememberPrompterAnnotatedString(parsed, colors.textPrimary, colors.accent, 400)
    val inline = remember(colors.accent) { prompterInlineContent(colors.accent) }
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
    ) {
        if (text.isBlank()) {
            Text(stringResource(R.string.scripts_editor_preview_empty), style = style.copy(color = colors.textTertiary))
        } else {
            BasicText(annotated, style = style, inlineContent = inline, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MarkupToolbar(
    enabled: Boolean,
    direction: LayoutDirection,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEdit: ((androidx.compose.ui.text.input.TextFieldValue) -> androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onAi: () -> Unit,
    onPrompter: () -> Unit,
    onRecord: () -> Unit,
) {
    val colors = RgTheme.colors
    val haptics = rememberHaptics()
    val sectionPlaceholder = stringResource(R.string.scripts_editor_section_placeholder)
    val textPlaceholder = stringResource(R.string.scripts_editor_text_placeholder)
    val notePlaceholder = stringResource(R.string.scripts_editor_note_placeholder)
    val pauseToken = if (direction == LayoutDirection.Rtl) "[مکث]" else "[pause]"
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.backgroundElevated)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = colors.outline)
        // Formatting: history first, then markup tools. Each control keeps a 48dp touch height.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryButton(Icons.AutoMirrored.Rounded.Undo, stringResource(UiR.string.action_undo), canUndo, onUndo)
            HistoryButton(Icons.AutoMirrored.Rounded.Redo, stringResource(UiR.string.action_redo), canRedo, onRedo)
            Box(Modifier.padding(horizontal = Spacing.xs).size(width = 1.dp, height = 24.dp).background(colors.outline))
            ToolButton(Icons.Rounded.Title, stringResource(R.string.scripts_editor_tool_section), enabled = enabled) {
                haptics.perform(HapticEvent.TICK); onEdit { MarkupEdits.toggleSection(it, sectionPlaceholder) }
            }
            ToolButton(Icons.Rounded.Highlight, stringResource(R.string.scripts_editor_tool_highlight), enabled = enabled) {
                haptics.perform(HapticEvent.TICK); onEdit { MarkupEdits.wrap(it, "==", "==", textPlaceholder) }
            }
            ToolButton(Icons.Rounded.FormatBold, stringResource(R.string.scripts_editor_tool_emphasis), enabled = enabled) {
                haptics.perform(HapticEvent.TICK); onEdit { MarkupEdits.wrap(it, "**", "**", textPlaceholder) }
            }
            ToolButton(Icons.Rounded.PauseCircle, stringResource(R.string.scripts_editor_tool_pause), enabled = enabled) {
                haptics.perform(HapticEvent.TICK); onEdit { MarkupEdits.insertToken(it, pauseToken) }
            }
            ToolButton(Icons.AutoMirrored.Rounded.StickyNote2, stringResource(R.string.scripts_editor_tool_note), enabled = enabled) {
                haptics.perform(HapticEvent.TICK); onEdit { MarkupEdits.wrap(it, "[[", "]]", notePlaceholder) }
            }
        }
        // Actions: AI on the start side, record + teleprompter (primary) on the end side.
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Compact AI button, record, then the primary teleprompter action taking the remaining width (48dp row).
            RgSecondaryButton(stringResource(R.string.scripts_editor_ai_short), onAi, icon = Icons.Rounded.AutoAwesome, size = RgButtonSize.LARGE)
            RgIconButton(Icons.Rounded.Videocam, stringResource(R.string.scripts_editor_record), onRecord, size = 48.dp, tint = colors.record, container = colors.pastelRose)
            RgPrimaryButton(stringResource(R.string.scripts_editor_open_prompter), onPrompter, icon = Icons.Rounded.Slideshow, size = RgButtonSize.LARGE, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = RgTheme.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).pressable(enabled = enabled, haptic = HapticEvent.TICK, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) colors.textPrimary else colors.textTertiary.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatPill(icon: ImageVector, text: String) {
    val colors = RgTheme.colors
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(Radius.pill)).background(colors.surfaceMuted).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
    }
}

/** Compact text-direction picker: a chip showing the current mode, with a menu of the three options. */
@Composable
private fun DirectionMenu(selected: ContentDirection, onSelect: (ContentDirection) -> Unit) {
    val colors = RgTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .pressable { open = true }
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FormatTextdirectionRToL, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(directionShortLabel(selected), style = MaterialTheme.typography.labelLarge, color = colors.accent, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.scripts_editor_direction), tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ContentDirection.entries.forEach { d ->
                DropdownMenuItem(
                    text = { Text(directionShortLabel(d), style = MaterialTheme.typography.bodyMedium, color = if (d == selected) colors.accent else colors.textPrimary) },
                    trailingIcon = if (d == selected) ({ Icon(Icons.Rounded.Check, null, tint = colors.accent) }) else null,
                    onClick = { onSelect(d); open = false },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = RgTheme.colors
    Box(Modifier.height(48.dp).pressable(enabled = enabled, haptic = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.surfaceMuted)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (enabled) colors.textPrimary else colors.textTertiary
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}
