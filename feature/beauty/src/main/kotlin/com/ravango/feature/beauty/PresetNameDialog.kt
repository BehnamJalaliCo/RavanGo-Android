package com.ravango.feature.beauty

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.ui.R as UiR

/** Maximum preset name length (keeps chips and list rows tidy). */
internal const val MAX_PRESET_NAME = 40

/** Dialog asking for a preset name (save / rename / duplicate). */
@Composable
internal fun PresetNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    val colors = RgTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            RgTextField(
                value = name,
                onValueChange = { name = it.take(MAX_PRESET_NAME) },
                label = stringResource(R.string.beauty_preset_name),
                placeholder = stringResource(R.string.beauty_preset_name_placeholder),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.trim()) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            RgTextButton(stringResource(UiR.string.action_save), { onConfirm(name.trim()) }, enabled = valid)
        },
        dismissButton = {
            RgTextButton(stringResource(UiR.string.action_cancel), onDismiss, color = colors.textSecondary)
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
