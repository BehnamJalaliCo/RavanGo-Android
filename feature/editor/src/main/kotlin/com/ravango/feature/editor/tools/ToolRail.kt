package com.ravango.feature.editor.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.BrandingWatermark
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.editor.EditorTool
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorViewModel
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.ActionRow
import com.ravango.feature.editor.ui.ToolAction

fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.EDIT -> Icons.Rounded.ContentCut
    EditorTool.CANVAS -> Icons.Rounded.AspectRatio
    EditorTool.FILTERS -> Icons.Rounded.FilterVintage
    EditorTool.ADJUST -> Icons.Rounded.Tune
    EditorTool.TEXT -> Icons.Rounded.TextFields
    EditorTool.STICKERS -> Icons.Rounded.EmojiEmotions
    EditorTool.OVERLAY -> Icons.Rounded.PictureInPicture
    EditorTool.LOGO -> Icons.AutoMirrored.Rounded.BrandingWatermark
    EditorTool.MUSIC -> Icons.Rounded.MusicNote
    EditorTool.VOICEOVER -> Icons.Rounded.Mic
    EditorTool.AUDIO -> Icons.Rounded.GraphicEq
    EditorTool.CAPTIONS -> Icons.Rounded.ClosedCaption
    EditorTool.AI -> Icons.Rounded.AutoAwesome
}

fun EditorTool.label(): Int = when (this) {
    EditorTool.EDIT -> R.string.editor_tool_edit
    EditorTool.CANVAS -> R.string.editor_tool_canvas
    EditorTool.FILTERS -> R.string.editor_tool_filters
    EditorTool.ADJUST -> R.string.editor_tool_adjust
    EditorTool.TEXT -> R.string.editor_tool_text
    EditorTool.STICKERS -> R.string.editor_tool_stickers
    EditorTool.OVERLAY -> R.string.editor_tool_overlay
    EditorTool.LOGO -> R.string.editor_tool_logo
    EditorTool.MUSIC -> R.string.editor_tool_music
    EditorTool.VOICEOVER -> R.string.editor_tool_voiceover
    EditorTool.AUDIO -> R.string.editor_tool_audio
    EditorTool.CAPTIONS -> R.string.editor_tool_captions
    EditorTool.AI -> R.string.editor_tool_ai
}

/** Context-aware ordering: tools relevant to the current selection come first. */
private fun orderedTools(selection: Selection): List<EditorTool> {
    val all = EditorTool.entries
    val first = when (selection) {
        is Selection.Clip -> listOf(EditorTool.EDIT, EditorTool.FILTERS, EditorTool.ADJUST, EditorTool.AUDIO, EditorTool.CANVAS)
        is Selection.Overlay -> listOf(EditorTool.TEXT, EditorTool.STICKERS, EditorTool.OVERLAY, EditorTool.LOGO)
        is Selection.Audio -> listOf(EditorTool.MUSIC, EditorTool.VOICEOVER)
        is Selection.Cue -> listOf(EditorTool.CAPTIONS)
        Selection.None -> emptyList()
    }
    return first + all.filterNot { it in first }
}

/** Bottom tool rail (horizontally scrolling). */
@Composable
fun ToolRail(state: EditorUiState, onTool: (EditorTool) -> Unit, modifier: Modifier = Modifier) {
    ActionRow(modifier.padding(vertical = Spacing.xs)) {
        orderedTools(state.selection).forEach { tool ->
            ToolAction(tool.icon(), stringResource(tool.label()), { onTool(tool) }, selected = state.tool == tool)
        }
    }
}

/** The panel for the active tool, animated in above the rail. */
@Composable
fun ToolPanelHost(state: EditorUiState, vm: EditorViewModel, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = state.tool,
        transitionSpec = { (fadeIn(tween(160)) + expandVertically()) togetherWith (fadeOut(tween(120)) + shrinkVertically()) },
        label = "toolPanel",
        modifier = modifier,
    ) { tool ->
        if (tool != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm)
                    .background(Color(0xFF1B1828), RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Spacing.sm),
            ) {
                when (tool) {
                    EditorTool.EDIT -> EditPanel(state, vm)
                    EditorTool.CANVAS -> CanvasPanel(state, vm)
                    EditorTool.FILTERS -> FiltersPanel(state, vm)
                    EditorTool.ADJUST -> AdjustPanel(state, vm)
                    EditorTool.TEXT -> TextPanel(state, vm)
                    EditorTool.STICKERS -> StickersPanel(state, vm)
                    EditorTool.OVERLAY -> OverlayPanel(state, vm)
                    EditorTool.LOGO -> LogoPanel(state, vm)
                    EditorTool.MUSIC -> MusicPanel(state, vm)
                    EditorTool.VOICEOVER -> VoiceOverPanel(state, vm)
                    EditorTool.AUDIO -> AudioPanel(state, vm)
                    EditorTool.CAPTIONS -> CaptionsPanel(state, vm)
                    EditorTool.AI -> AiPanel(state, vm)
                }
            }
        }
    }
}

@Composable
internal fun accent() = RgTheme.colors.accent
