package com.ravango.feature.ai

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.SectionHeader
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AiProviderId
import com.ravango.core.ui.shareText
import com.ravango.engine.ai.api.CapabilityState
import kotlinx.coroutines.launch

@Composable
fun AiStudioScreen(
    onBack: () -> Unit,
    onOpenTeleprompter: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaywall: () -> Unit,
    onSignIn: () -> Unit,
    onOpenProjects: () -> Unit,
    viewModel: AiStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.ai_saved)
    val replacedText = stringResource(R.string.ai_replaced)
    val failedText = stringResource(R.string.ai_save_failed)
    val copiedText = stringResource(R.string.ai_copied)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AiStudioEvent.OpenTeleprompter -> onOpenTeleprompter(event.scriptId)
                is AiStudioEvent.Saved -> snackbar.showSnackbar(if (event.replaced) replacedText else savedText)
                AiStudioEvent.SaveFailed -> snackbar.showSnackbar(failedText)
            }
        }
    }

    val tool = state.tool
    BackHandler(enabled = tool != null) { viewModel.closeTool() }

    AiStudioContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = { if (tool != null) viewModel.closeTool() else onBack() },
        onOpenTool = viewModel::openTool,
        onRestore = viewModel::restore,
        onOpenSettings = onOpenSettings,
        onSignIn = onSignIn,
        onGetCredits = onOpenPaywall,
        onOpenProjects = onOpenProjects,
        toolActions = AiToolActions(
            onInput = viewModel::setInput,
            onOptions = viewModel::updateOptions,
            onGenerate = viewModel::generate,
            onStop = viewModel::stop,
            onToggleVariant = viewModel::toggleVariant,
            onSave = viewModel::saveAsNewScript,
            onReplace = viewModel::replaceSourceScript,
            onCopy = { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("RavanGo", text)))
                    snackbar.showSnackbar(copiedText)
                }
            },
            onShare = { text -> context.shareTextSafely(text) },
        ),
    )

    if (state.showConsent) {
        val body = when (state.availability.provider) {
            AiProviderId.RAVANGO_GATEWAY -> R.string.ai_consent_gateway
            AiProviderId.ANTHROPIC_DIRECT -> R.string.ai_consent_anthropic
            AiProviderId.OPENAI_COMPATIBLE -> R.string.ai_consent_custom
        }
        RgConfirmDialog(
            title = stringResource(R.string.ai_consent_title),
            message = stringResource(body),
            confirmText = stringResource(R.string.ai_consent_accept),
            dismissText = stringResource(R.string.ai_consent_decline),
            onConfirm = viewModel::grantConsent,
            onDismiss = viewModel::dismissConsent,
        )
    }
}

/** Stateless AI Studio (hub or the open tool); rendered by screenshot tests with sample data. */
@Composable
internal fun AiStudioContent(
    state: AiStudioUiState,
    onBack: () -> Unit,
    onOpenTool: (AiTool) -> Unit,
    onRestore: (HistoryItem) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onGetCredits: () -> Unit,
    onOpenProjects: () -> Unit,
    toolActions: AiToolActions,
    snackbarHostState: SnackbarHostState? = null,
) {
    val tool = state.tool
    RgScreen(
        title = tool?.let { stringResource(it.title) } ?: stringResource(R.string.ai_studio_title),
        subtitle = if (tool == null) stringResource(R.string.ai_studio_subtitle) else stringResource(tool.group.title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        AnimatedContent(tool, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tool") { current ->
            if (current == null) {
                HubContent(
                    state = state,
                    padding = padding,
                    onOpenTool = onOpenTool,
                    onRestore = onRestore,
                    onOpenSettings = onOpenSettings,
                    onSignIn = onSignIn,
                    onGetCredits = onGetCredits,
                    onOpenProjects = onOpenProjects,
                )
            } else {
                ToolContent(
                    state = state,
                    padding = padding,
                    actions = toolActions,
                    onOpenSettings = onOpenSettings,
                    onSignIn = onSignIn,
                    onPaywall = onGetCredits,
                )
            }
        }
    }
}

private fun android.content.Context.shareTextSafely(text: String) {
    runCatching { shareText(text, getString(R.string.ai_share_title)) }
}

@Composable
private fun HubContent(
    state: AiStudioUiState,
    padding: PaddingValues,
    onOpenTool: (AiTool) -> Unit,
    onRestore: (HistoryItem) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onGetCredits: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val colors = RgTheme.colors
    val tints = mapOf(
        ToolGroup.WRITE to colors.pastelLavender,
        ToolGroup.IMPROVE to colors.pastelMint,
        ToolGroup.PUBLISH to colors.pastelPeach,
        ToolGroup.TRANSLATE to colors.pastelSky,
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + Spacing.sm, bottom = padding.calculateBottomPadding() + Spacing.huge),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "hero") { AiHero(state, onGetCredits) }
        if (!state.availability.configured) {
            item(key = "setup") { SetupCard(state.availability, onOpenSettings, onSignIn) }
        }
        for (group in ToolGroup.entries) {
            val tools = AiTool.entries.filter { it.group == group }
            item(key = "h_${group.name}") { SectionHeader(stringResource(group.title)) }
            toolGrid(tools, tints.getValue(group), onOpenTool)
        }
        item(key = "video") { VideoToolsCard(state.eyeContactState, onOpenProjects) }
        if (state.history.isNotEmpty()) {
            item(key = "h_history") { SectionHeader(stringResource(R.string.ai_history)) }
            items(state.history, key = { it.id }) { h ->
                RgListItem(
                    title = stringResource(h.tool.title),
                    subtitle = h.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(80),
                    icon = Icons.Rounded.History,
                    onClick = { onRestore(h) },
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
        }
    }
}

/** Two-column grid rows inside a LazyColumn. */
private fun LazyListScope.toolGrid(tools: List<AiTool>, tint: androidx.compose.ui.graphics.Color, onOpen: (AiTool) -> Unit) {
    tools.chunked(2).forEach { row ->
        item(key = "row_${row.first().name}") {
            if (row.size == 1) {
                WideToolTile(row.first(), tint, { onOpen(row.first()) }, Modifier.padding(horizontal = Spacing.gutter))
            } else {
                // Equal-height tiles per row, whatever the subtitle length.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    row.forEach { t -> ToolTile(t, tint, { onOpen(t) }, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun VideoToolsCard(eyeContact: CapabilityState, onOpenProjects: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.ai_video_title))
        RgCard(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            RgListItem(
                title = stringResource(R.string.ai_video_title),
                subtitle = stringResource(R.string.ai_video_body),
                icon = Icons.Rounded.VideoLibrary,
                iconBackground = RgTheme.colors.pastelSky,
            )
            Spacer(Modifier.height(Spacing.sm))
            VideoFeatureRow(stringResource(R.string.ai_video_captions))
            VideoFeatureRow(stringResource(R.string.ai_video_cuts))
            VideoFeatureRow(stringResource(R.string.ai_video_highlights))
            VideoFeatureRow(stringResource(R.string.ai_video_cleanup))
            VideoFeatureRow(
                stringResource(R.string.ai_video_eye),
                trailing = if (eyeContact == CapabilityState.AVAILABLE) null else stringResource(R.string.ai_video_eye_unavailable),
            )
            Spacer(Modifier.height(Spacing.md))
            RgSecondaryButton(stringResource(R.string.ai_video_open_projects), onOpenProjects, size = RgButtonSize.MEDIUM, modifier = Modifier.fillMaxWidth())
        }
    }
}
