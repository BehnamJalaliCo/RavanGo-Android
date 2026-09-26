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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.editor.R
import com.ravango.feature.editor.ui.timecode

/** Time readout, frame stepping, play/pause and a split shortcut. */
@Composable
fun Transport(
    playhead: State<Long>,
    durationUs: Long,
    isPlaying: State<Boolean>,
    onToggle: () -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onSplit: () -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        TimeReadout(playhead, durationUs, Modifier.weight(1f))
        // Media transport controls represent the time axis and are not mirrored in RTL.
        RgIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.editor_prev_frame), { onStep(false) }, size = 38.dp, iconSize = 20.dp, glass = true)
        Spacer(Modifier.width(Spacing.xs))
        RgIconButton(
            if (isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(if (isPlaying.value) R.string.editor_pause else R.string.editor_play),
            onToggle, size = 46.dp, iconSize = 26.dp, selected = true,
        )
        Spacer(Modifier.width(Spacing.xs))
        RgIconButton(Icons.Rounded.SkipNext, stringResource(R.string.editor_next_frame), { onStep(true) }, size = 38.dp, iconSize = 20.dp, glass = true)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            RgIconButton(Icons.Rounded.ContentCut, stringResource(R.string.editor_split), onSplit, size = 38.dp, iconSize = 18.dp, glass = true)
            Spacer(Modifier.width(Spacing.xs))
            RgIconButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.editor_add_media), onAddMedia, size = 38.dp, iconSize = 18.dp, glass = true)
        }
    }
}

@Composable
private fun TimeReadout(playhead: State<Long>, durationUs: Long, modifier: Modifier) {
    Text(
        timecode(playhead.value) + " / " + timecode(durationUs, tenths = false),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.85f),
        modifier = modifier,
        maxLines = 1,
    )
}
