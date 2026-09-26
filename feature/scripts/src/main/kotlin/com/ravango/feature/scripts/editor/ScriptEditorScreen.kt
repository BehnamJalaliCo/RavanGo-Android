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
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ravango.core.designsystem.component.RgSegmentedControl
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

    RgScreen(
        title = stringResource(if (state.scriptId == null && !state.loading) R.string.scripts_editor_new_title else R.string.scripts_editor_title),
        subtitle = saveStatusText(state.saveStatus),
        onBack = { viewModel.flush(); onBack() },
        snackbarHostState = snackbar,
        actions = {
            if (!state.loading && !state.notFound) {
                RgIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(UiR.string.action_undo), viewModel::undo, enabled = state.canUndo, size = 40.dp)
                RgIconButton(Icons.AutoMirrored.Rounded.Redo, stringResource(UiR.string.action_redo), viewModel::redo, enabled = state.canRedo, size = 40.dp)
                RgIconButton(
                    if (state.preview) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                    stringResource(if (state.preview) R.string.scripts_editor_edit_mode else R.string.scripts_editor_preview_mode),
                    { viewModel.setPreview(!state.preview) },
                    selected = state.preview,
                    size = 40.dp,
                )
                MoreMenu(state.floatingIsPro, viewModel::navigate)
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
                viewModel = viewModel,
                onAi = { showAi = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

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
        RgIconButton(Icons.Rounded.MoreVert, stringResource(UiR.string.action_more), { open = true }, size = 40.dp)
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
    viewModel: ScriptEditorViewModel,
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

    Column(modifier.fillMaxSize().imePadding()) {
        // Title
        CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xs)) {
                if (state.title.isEmpty()) {
                    Text(stringResource(R.string.scripts_editor_title_hint), style = MaterialTheme.typography.headlineSmall, color = colors.textTertiary)
                }
                BasicTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = colors.textPrimary, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Stats + direction
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.scripts_editor_stats,
                    stats.words.toString().localizeDigits(),
                    formatDurationMs(stats.durationMs),
                    stats.wordsPerMinute.toString().localizeDigits(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            RgSegmentedControl(
                options = ContentDirection.entries,
                selected = state.direction,
                onSelect = viewModel::setDirection,
                label = { directionShortLabel(it) },
            )
        }
        HorizontalDivider(color = colors.outline, modifier = Modifier.padding(top = Spacing.xs))

        AnimatedContent(
            targetState = state.preview,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
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
                            onValueChange = viewModel::onBodyChange,
                            textStyle = bodyStyle,
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        MarkupToolbar(
            enabled = !state.preview,
            direction = contentDirection,
            onEdit = viewModel::applyEdit,
            onAi = onAi,
            onPrompter = { viewModel.navigate(EditorNavAction.PROMPTER) },
            onRecord = { viewModel.navigate(EditorNavAction.RECORD) },
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Rounded.AutoAwesome, stringResource(R.string.scripts_editor_ai), highlighted = true, enabled = true) { onAi() }
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
            Spacer(Modifier.width(Spacing.sm))
            ToolButton(Icons.Rounded.Slideshow, stringResource(R.string.scripts_editor_open_prompter), enabled = true, highlighted = true) { onPrompter() }
            ToolButton(Icons.Rounded.Videocam, stringResource(R.string.scripts_editor_record), enabled = true) { onRecord() }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = RgTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (highlighted) colors.accentSoft else colors.surfaceMuted)
            .pressable(enabled = enabled, haptic = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (!enabled) colors.textTertiary else if (highlighted) colors.accent else colors.textPrimary
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
