package com.ravango.feature.scripts.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ScriptFolder
import com.ravango.feature.scripts.R
import com.ravango.feature.scripts.common.toArgbLong
import com.ravango.core.ui.R as UiR

sealed interface FolderDialogMode {
    data object Create : FolderDialogMode
    data class Rename(val folder: ScriptFolder) : FolderDialogMode
}

/** Pastel folder colors (match the design-system palette). */
internal val FolderColors: List<Long> = listOf(0xFF8B7CF6, 0xFFF7718F, 0xFFFFAE7A, 0xFF3CC4A4, 0xFF7DB8FF, 0xFFFFD166, 0xFFA6A2C0)

@Composable
internal fun FolderDialog(mode: FolderDialogMode, onConfirm: (name: String, color: Long) -> Unit, onDismiss: () -> Unit) {
    val initialName = (mode as? FolderDialogMode.Rename)?.folder?.name.orEmpty()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableLongStateOf((mode as? FolderDialogMode.Rename)?.folder?.colorArgb ?: FolderColors.first()) }
    val isCreate = mode == FolderDialogMode.Create
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RgTheme.colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        icon = { Icon(if (isCreate) Icons.Rounded.CreateNewFolder else Icons.Rounded.DriveFileRenameOutline, null, tint = RgTheme.colors.accent) },
        title = { Text(stringResource(if (isCreate) R.string.scripts_folder_new else R.string.scripts_folder_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                RgTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = stringResource(R.string.scripts_folder_name))
                if (isCreate) {
                    Text(stringResource(R.string.scripts_folder_color), style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textSecondary)
                    ColorSwatchRow(FolderColors.map { Color(it) }, Color(color), { color = it.toArgbLong() })
                }
            }
        },
        confirmButton = {
            RgTextButton(
                stringResource(if (isCreate) R.string.scripts_folder_create else UiR.string.action_save),
                { onConfirm(name.trim(), color) },
                enabled = name.isNotBlank(),
            )
        },
        dismissButton = { RgTextButton(stringResource(UiR.string.action_cancel), onDismiss, color = RgTheme.colors.textSecondary) },
    )
}
