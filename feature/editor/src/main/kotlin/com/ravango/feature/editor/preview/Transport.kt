package com.ravango.feature.editor.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.component.softShadow
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.RgTheme
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
                    val undo = @Composable { GhostButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.editor_undo), onUndo, enabled = canUndo) }
                    val redo = @Composable { GhostButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.editor_redo), onRedo, enabled = canRedo) }
                    // Undo first in reading order.
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { undo(); redo() }
                }
            }
            GhostButton(Icons.Rounded.SkipPrevious, stringResource(R.string.editor_prev_frame), { onStep(false) })
            Spacer(Modifier.width(Spacing.md))
            PlayButton(isPlaying, onToggle)
            Spacer(Modifier.width(Spacing.md))
            GhostButton(Icons.Rounded.SkipNext, stringResource(R.string.editor_next_frame), { onStep(true) })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                GhostButton(Icons.Rounded.ContentCut, stringResource(R.string.editor_split), onSplit)
                GhostButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.editor_add_media), onAddMedia)
            }
        }
    }
}

/** Quiet secondary transport action: icon only, no container, full 48dp touch target. */
@Composable
private fun GhostButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
    RgIconButton(icon, contentDescription, onClick, size = 40.dp, iconSize = 22.dp, container = Color.Transparent, tint = Color.White.copy(alpha = 0.88f), enabled = enabled)
}

/** The transport's one emphasised control: brand-blue disc with a soft glow; the play/pause glyph morphs. */
@Composable
private fun PlayButton(isPlaying: State<Boolean>, onToggle: () -> Unit) {
    val colors = RgTheme.colors
    val playing = isPlaying.value
    val reduceMotion = RgTheme.reduceMotion
    Box(
        Modifier
            .size(52.dp)
            .softShadow(10.dp, CircleShape, colors.accentGlow)
            .clip(CircleShape)
            .background(colors.ctaGradient)
            .pressable(shape = CircleShape, haptic = HapticEvent.CONFIRM, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                else (scaleIn(spring(dampingRatio = 0.6f, stiffness = 700f), initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(100)))
            },
            label = "playIcon",
        ) { p ->
            Icon(
                if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (p) R.string.editor_pause else R.string.editor_play),
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
