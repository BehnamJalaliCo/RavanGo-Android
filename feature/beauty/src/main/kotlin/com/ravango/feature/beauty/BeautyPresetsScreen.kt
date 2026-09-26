package com.ravango.feature.beauty

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.ProFeature
import com.ravango.core.ui.R as UiR

/** Route entry: manage beauty presets (apply, rename, duplicate, delete, save current look). */
@Composable
internal fun BeautyPresetsRoute(
    onBack: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    viewModel: BeautyViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val requirePro by rememberUpdatedState(onRequirePro)
    val namer = rememberPresetNamer()
    val savedText = stringResource(R.string.beauty_preset_saved)
    val appliedFormat = stringResource(R.string.beauty_preset_applied)
    val deletedText = stringResource(R.string.beauty_preset_deleted)
    val renamedText = stringResource(R.string.beauty_preset_renamed)
    val failedText = stringResource(R.string.beauty_error_save)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is BeautyEvent.RequirePro -> requirePro(event.feature)
                is BeautyEvent.PresetSaved -> snackbar.showSnackbar(savedText)
                is BeautyEvent.PresetApplied -> snackbar.showSnackbar(appliedFormat.format(namer(event.preset)))
                BeautyEvent.PresetDeleted -> snackbar.showSnackbar(deletedText)
                BeautyEvent.PresetRenamed -> snackbar.showSnackbar(renamedText)
                BeautyEvent.SaveFailed -> snackbar.showSnackbar(failedText)
            }
        }
    }
    BeautyPresetsScreen(ui = ui, namer = namer, onBack = onBack, snackbar = snackbar, viewModel = viewModel, onRequirePro = onRequirePro)
}

private sealed interface PresetDialog {
    data object SaveCurrent : PresetDialog
    data class Rename(val presetId: String) : PresetDialog
    data class Duplicate(val presetId: String) : PresetDialog
    data class Delete(val presetId: String) : PresetDialog
}

@Composable
private fun BeautyPresetsScreen(
    ui: BeautyUiState,
    namer: (BeautyPreset) -> String,
    onBack: () -> Unit,
    snackbar: androidx.compose.material3.SnackbarHostState,
    viewModel: BeautyViewModel,
    onRequirePro: (ProFeature) -> Unit,
) {
    var dialogKey by rememberSaveable { mutableStateOf<String?>(null) }
    val dialog = remember(dialogKey) { decodeDialog(dialogKey) }
    val locale = currentLocale()
    val all = ui.presets.builtIn + ui.presets.mine
    fun preset(id: String) = all.firstOrNull { it.id == id }
    fun startCreate(key: String) {
        if (ui.canSaveMorePresets) dialogKey = key else onRequirePro(ProFeature.UNLIMITED_PRESETS)
    }

    RgScreen(
        title = stringResource(R.string.beauty_presets_title),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { padding ->
        if (!ui.presets.loaded) {
            LoadingState(Modifier.padding(padding))
            return@RgScreen
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.huge),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "save") {
                RgCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                    Text(stringResource(R.string.beauty_presets_current_title), style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
                    Spacer(Modifier.height(Spacing.xs))
                    val count = BeautyCatalog.activeCount(ui.state)
                    Text(adjustmentsText(count, locale), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
                    Spacer(Modifier.height(Spacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RgPrimaryButton(
                            text = stringResource(R.string.beauty_presets_save_current),
                            onClick = { startCreate(DIALOG_SAVE) },
                            icon = Icons.Rounded.BookmarkAdd,
                            size = RgButtonSize.MEDIUM,
                        )
                        if (!ui.canSaveMorePresets) {
                            Spacer(Modifier.width(Spacing.sm))
                            ProBadge(text = stringResource(R.string.beauty_pro))
                        }
                    }
                    if (!ui.unlimitedPresets) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            stringResource(
                                R.string.beauty_presets_limit,
                                ui.presets.mine.size.toString().localizeDigits(locale),
                                ui.entitlements.maxSavedPresets.toString().localizeDigits(locale),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = RgTheme.colors.textTertiary,
                        )
                    }
                }
            }

            item(key = "mine-header") { GroupTitle(stringResource(R.string.beauty_presets_mine)) }
            if (ui.presets.mine.isEmpty()) {
                item(key = "mine-empty") {
                    EmptyState(
                        icon = Icons.Rounded.Face,
                        title = stringResource(R.string.beauty_presets_empty_title),
                        message = stringResource(R.string.beauty_presets_empty_message),
                        modifier = Modifier.padding(vertical = 0.dp),
                    )
                }
            } else {
                items(ui.presets.mine, key = { it.id }) { p ->
                    PresetRow(
                        preset = p,
                        name = namer(p),
                        active = p.id == ui.activePresetId,
                        locked = ui.isPresetLocked(p),
                        locale = locale,
                        onApply = { viewModel.applyPreset(p) },
                        onRename = { dialogKey = "$DIALOG_RENAME${p.id}" },
                        onDuplicate = { startCreate("$DIALOG_DUPLICATE${p.id}") },
                        onDelete = { dialogKey = "$DIALOG_DELETE${p.id}" },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "builtin-header") { GroupTitle(stringResource(R.string.beauty_presets_builtin)) }
            items(ui.presets.builtIn, key = { it.id }) { p ->
                PresetRow(
                    preset = p,
                    name = namer(p),
                    active = p.id == ui.activePresetId,
                    locked = ui.isPresetLocked(p),
                    locale = locale,
                    onApply = { viewModel.applyPreset(p) },
                    onRename = null,
                    onDuplicate = { startCreate("$DIALOG_DUPLICATE${p.id}") },
                    onDelete = null,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    val target = when (dialog) {
        is PresetDialog.Rename -> preset(dialog.presetId)
        is PresetDialog.Duplicate -> preset(dialog.presetId)
        is PresetDialog.Delete -> preset(dialog.presetId)
        else -> null
    }
    if (ui.presets.loaded && dialog != null && dialog != PresetDialog.SaveCurrent && target == null) {
        // The preset disappeared (deleted elsewhere / synced): close the stale dialog.
        LaunchedEffect(dialogKey) { dialogKey = null }
    }
    when {
        dialog == PresetDialog.SaveCurrent -> PresetNameDialog(
            title = stringResource(R.string.beauty_save_preset),
            initial = "",
            onConfirm = { dialogKey = null; viewModel.saveCurrentAsPreset(it) },
            onDismiss = { dialogKey = null },
        )
        target == null -> Unit
        dialog is PresetDialog.Rename -> PresetNameDialog(
            title = stringResource(UiR.string.action_rename),
            initial = namer(target),
            onConfirm = { dialogKey = null; viewModel.renamePreset(target, it) },
            onDismiss = { dialogKey = null },
        )
        dialog is PresetDialog.Duplicate -> PresetNameDialog(
            title = stringResource(UiR.string.action_duplicate),
            initial = stringResource(R.string.beauty_preset_copy_suffix, namer(target)).take(MAX_PRESET_NAME),
            onConfirm = { dialogKey = null; viewModel.duplicatePreset(target, it) },
            onDismiss = { dialogKey = null },
        )
        dialog is PresetDialog.Delete -> RgConfirmDialog(
            title = stringResource(R.string.beauty_delete_preset_title),
            message = stringResource(R.string.beauty_delete_preset_message, namer(target)),
            confirmText = stringResource(UiR.string.action_delete),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = { dialogKey = null; viewModel.deletePreset(target) },
            onDismiss = { dialogKey = null },
            destructive = true,
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = RgTheme.colors.textSecondary,
        modifier = Modifier.padding(start = Spacing.gutter + Spacing.sm, end = Spacing.gutter, top = Spacing.md),
    )
}

@Composable
private fun PresetRow(
    preset: BeautyPreset,
    name: String,
    active: Boolean,
    locked: Boolean,
    locale: java.util.Locale,
    onApply: () -> Unit,
    onRename: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    RgCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).animateContentSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(RgTheme.colors.brandGradientSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (preset.builtIn) Icons.Rounded.AutoAwesome else Icons.Rounded.Face, null, tint = RgTheme.colors.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (locked) {
                        Spacer(Modifier.width(Spacing.xs))
                        ProBadge(text = stringResource(R.string.beauty_pro))
                    }
                }
                Text(adjustmentsText(BeautyCatalog.activeCount(preset.state), locale), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
            }
            if (active) {
                RgTag(stringResource(R.string.beauty_active), icon = Icons.Rounded.Check)
            } else {
                RgSecondaryButton(stringResource(UiR.string.action_apply), onApply, size = RgButtonSize.SMALL)
            }
            Box {
                RgIconButton(Icons.Rounded.MoreVert, stringResource(UiR.string.action_more), { menu = true }, container = androidx.compose.ui.graphics.Color.Transparent)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = RgTheme.colors.backgroundElevated) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.action_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onRename() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.action_duplicate)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.action_delete), color = RgTheme.colors.danger) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = RgTheme.colors.danger) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun adjustmentsText(count: Int, locale: java.util.Locale): String =
    if (count == 0) {
        stringResource(R.string.beauty_no_adjustments)
    } else {
        pluralStringResource(R.plurals.beauty_adjustments, count, count.toString().localizeDigits(locale))
    }

private const val DIALOG_SAVE = "save"
private const val DIALOG_RENAME = "rename:"
private const val DIALOG_DUPLICATE = "duplicate:"
private const val DIALOG_DELETE = "delete:"

/** Dialog state is saved as a string key so it survives configuration changes. */
private fun decodeDialog(key: String?): PresetDialog? = when {
    key == null -> null
    key == DIALOG_SAVE -> PresetDialog.SaveCurrent
    key.startsWith(DIALOG_RENAME) -> PresetDialog.Rename(key.removePrefix(DIALOG_RENAME))
    key.startsWith(DIALOG_DUPLICATE) -> PresetDialog.Duplicate(key.removePrefix(DIALOG_DUPLICATE))
    key.startsWith(DIALOG_DELETE) -> PresetDialog.Delete(key.removePrefix(DIALOG_DELETE))
    else -> null
}
