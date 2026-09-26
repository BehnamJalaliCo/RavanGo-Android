package com.ravango.feature.editor.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ravango.core.designsystem.motion.rgFadeThrough
import com.ravango.core.designsystem.theme.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ravango.core.designsystem.component.pressable
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
import com.ravango.feature.editor.EditorActions
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection

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
    // Rail items are plain icons + labels (a pill marks the open tool), so they read as navigation and the circular
    // buttons inside panels read as actions.
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    ) {
        orderedTools(state.selection).forEach { tool ->
            RailItem(tool.icon(), stringResource(tool.label()), selected = state.tool == tool) { onTool(tool) }
        }
    }
}

/** The panel for the active tool, animated in above the rail. */
@Composable
fun ToolPanelHost(state: EditorUiState, vm: EditorActions, modifier: Modifier = Modifier) {
    val reduceMotion = RgTheme.reduceMotion
    val panelColor = RgTheme.colors.surface
    AnimatedContent(
        targetState = state.tool,
        transitionSpec = { rgFadeThrough(reduceMotion, clipSize = true) },
        label = "toolPanel",
        modifier = modifier,
    ) { tool ->
        if (tool != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm)
                    .background(panelColor, RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
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

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = RgTheme.colors
    // Material-style indicator: the pill springs open from the icon's width when a tool is chosen.
    val reduceMotion = RgTheme.reduceMotion
    val pill by animateFloatAsState(if (selected) 1f else 0f, if (reduceMotion) snap() else Motion.spatialBouncy(), label = "railPill")
    val iconTint by animateColorAsState(if (selected) colors.onAccent else Color.White.copy(alpha = 0.9f), Motion.quick(), label = "railIcon")
    Column(
        Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(Radius.md))
            .pressable(shape = RoundedCornerShape(Radius.md), onClick = onClick)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .drawBehind {
                    if (pill <= 0.01f) return@drawBehind
                    val w = size.width * (0.45f + 0.55f * pill)
                    drawRoundRect(
                        colors.accent.copy(alpha = pill.coerceIn(0f, 1f)),
                        topLeft = Offset((size.width - w) / 2f, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
