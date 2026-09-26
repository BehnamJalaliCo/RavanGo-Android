package com.ravango.feature.editor.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.editor.R

/**
 * Undo/redo, frame stepping with play/pause in the middle, and split / add media. The row keeps its left-to-right
 * order in RTL (like the timeline it drives); only the undo/redo arrows follow the reading direction.
 */
@Composable
fun Transport(
    isPlaying: State<Boolean>,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggle: () -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onSplit: () -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CompositionLocalProvider(LocalLayoutDirection provides uiDirection) {
                    val undo = @Composable { RgIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.editor_undo), onUndo, size = 40.dp, iconSize = 20.dp, glass = true, enabled = canUndo) }
                    val redo = @Composable { RgIconButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.editor_redo), onRedo, size = 40.dp, iconSize = 20.dp, glass = true, enabled = canRedo) }
                    // Undo first in reading order.
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { undo(); redo() }
                }
            }
            RgIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.editor_prev_frame), { onStep(false) }, size = 40.dp, iconSize = 20.dp, glass = true)
            Spacer(Modifier.width(Spacing.sm))
            RgIconButton(
                if (isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (isPlaying.value) R.string.editor_pause else R.string.editor_play),
                onToggle, size = 52.dp, iconSize = 28.dp, selected = true,
            )
            Spacer(Modifier.width(Spacing.sm))
            RgIconButton(Icons.Rounded.SkipNext, stringResource(R.string.editor_next_frame), { onStep(true) }, size = 40.dp, iconSize = 20.dp, glass = true)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                RgIconButton(Icons.Rounded.ContentCut, stringResource(R.string.editor_split), onSplit, size = 40.dp, iconSize = 20.dp, glass = true)
                RgIconButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.editor_add_media), onAddMedia, size = 40.dp, iconSize = 20.dp, glass = true)
            }
        }
    }
}
