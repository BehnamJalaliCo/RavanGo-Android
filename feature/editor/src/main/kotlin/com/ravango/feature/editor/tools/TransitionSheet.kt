package com.ravango.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.Transition
import com.ravango.core.model.TransitionType
import com.ravango.feature.editor.R
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.formatSeconds
import kotlin.math.roundToLong

fun TransitionType.label(): Int = when (this) {
    TransitionType.NONE -> R.string.editor_tr_none
    TransitionType.FADE_BLACK -> R.string.editor_tr_fade_black
    TransitionType.FADE_WHITE -> R.string.editor_tr_fade_white
    TransitionType.CROSS_ZOOM -> R.string.editor_tr_cross_zoom
    TransitionType.ZOOM_IN -> R.string.editor_tr_zoom_in
    TransitionType.ZOOM_OUT -> R.string.editor_tr_zoom_out
    TransitionType.SLIDE_LEFT -> R.string.editor_tr_slide_left
    TransitionType.SLIDE_RIGHT -> R.string.editor_tr_slide_right
    TransitionType.SLIDE_UP -> R.string.editor_tr_slide_up
    TransitionType.BLUR -> R.string.editor_tr_blur
    TransitionType.SPIN -> R.string.editor_tr_spin
}

/** Picks the transition between a clip and the next one. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TransitionSheet(current: Transition, onApply: (TransitionType, Long) -> Unit, onApplyAll: (TransitionType, Long) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(current.type) }
    var seconds by remember { mutableFloatStateOf((current.durationUs / 1e6f).coerceIn(0.2f, 2f)) }
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.editor_transition), dark = true) {
        RgChipRow(TransitionType.entries.toList(), type, { type = it }, { stringResource(it.label()) }, glass = true, contentPadding = PaddingValues(horizontal = Spacing.gutter))
        if (type != TransitionType.NONE) {
            ValueSlider(stringResource(R.string.editor_duration), seconds, { seconds = it }, 0.2f..2f, formatSeconds((seconds * 1e6).roundToLong(), stringResource(R.string.editor_unit_seconds)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RgTextButton(stringResource(R.string.editor_apply_all), onClick = {
                onApplyAll(type, (seconds * 1e6).roundToLong())
                onDismiss()
            })
            RgPrimaryButton(stringResource(R.string.editor_apply), {
                onApply(type, (seconds * 1e6).roundToLong())
                onDismiss()
            }, size = RgButtonSize.MEDIUM, modifier = Modifier.weight(1f))
        }
    }
}
