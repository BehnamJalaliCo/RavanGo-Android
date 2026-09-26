package com.ravango.feature.scripts.library

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatDurationMs
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.core.model.ScriptSortOrder
import com.ravango.core.ui.resolve
import com.ravango.feature.scripts.R
import com.ravango.feature.scripts.common.formatShortDate
import com.ravango.feature.scripts.common.highlight
import com.ravango.feature.scripts.importing.ImportError
import com.ravango.feature.scripts.importing.ScriptImport
import kotlinx.coroutines.launch
import com.ravango.core.ui.R as UiR

@Composable
internal fun ScriptsRoute(
    onBack: () -> Unit,
    onOpenEditor: (scriptId: String) -> Unit,
    onNewScript: (folderId: String?) -> Unit,
    onOpenPrompter: (scriptId: String) -> Unit,
    onRecord: (scriptId: String) -> Unit,
    onPicked: (scriptId: String) -> Unit,
    viewModel: ScriptsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = rememberSnackbarHostState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var actionsFor by remember { mutableStateOf<Script?>(null) }
    var moveFor by remember { mutableStateOf<Script?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf<FolderDialogMode?>(null) }
    var folderMenu by remember { mutableStateOf<ScriptFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<ScriptFolder?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    val deletedMessage = stringResource(R.string.scripts_deleted)
    val undoLabel = stringResource(UiR.string.action_undo)
    val duplicatedMessage = stringResource(R.string.scripts_duplicated)
    val clipboardEmpty = stringResource(R.string.scripts_clipboard_empty)
    val importErrors = mapOf(
        ImportError.PDF_UNSUPPORTED to stringResource(R.string.scripts_import_pdf),
        ImportError.UNSUPPORTED_TYPE to stringResource(R.string.scripts_import_unsupported),
        ImportError.EMPTY to stringResource(R.string.scripts_import_empty),
        ImportError.TOO_LARGE to stringResource(R.string.scripts_import_too_large),
        ImportError.READ_FAILED to stringResource(R.string.scripts_import_failed),
    )
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ScriptsEvent.OpenEditor -> onOpenEditor(event.scriptId)
                is ScriptsEvent.Deleted -> scope.launch {
                    val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.script)
                }
                is ScriptsEvent.Duplicated -> scope.launch { snackbar.showSnackbar(duplicatedMessage) }
                is ScriptsEvent.ImportFailed -> scope.launch {
                    snackbar.showSnackbar(importErrors.getValue(event.error), duration = SnackbarDuration.Long)
                }
                ScriptsEvent.ClipboardEmpty -> scope.launch { snackbar.showSnackbar(clipboardEmpty) }
            }
        }
    }

    val untitled = stringResource(R.string.scripts_untitled)
    val copySuffix = stringResource(R.string.scripts_copy_suffix)

    RgScreen(
        title = stringResource(if (state.pickMode) R.string.scripts_pick_title else R.string.scripts_title),
        subtitle = if (state.items.isNotEmpty() && !state.loading) {
            stringResource(R.string.scripts_count, state.items.size.toString().localizeDigits())
        } else {
            null
        },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { SortMenu(state.sort, viewModel::setSort) },
        floatingActionButton = {
            if (!state.pickMode) {
                RgPrimaryButton(
                    text = stringResource(R.string.scripts_new),
                    onClick = { showCreate = true },
                    icon = Icons.Rounded.Add,
                    size = RgButtonSize.LARGE,
                    loading = state.importing,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                RgTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.scripts_search_hint),
                    leadingIcon = Icons.Rounded.Search,
                    trailing = if (state.query.isNotEmpty()) {
                        { RgIconButton(Icons.Rounded.Close, stringResource(UiR.string.action_close), { viewModel.setQuery("") }, size = 32.dp, iconSize = 18.dp) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                )
            }
            item {
                FilterRow(
                    state = state,
                    onSelect = viewModel::setFilter,
                    onNewFolder = { folderDialog = FolderDialogMode.Create },
                    onFolderLongPress = { folderMenu = it },
                )
            }
            when {
                state.loading -> item { LoadingState(Modifier.height(240.dp)) }
                state.items.isEmpty() -> item {
                    if (state.query.isNotBlank()) {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = stringResource(R.string.scripts_search_empty_title),
                            message = stringResource(R.string.scripts_search_empty_message, state.query),
                        )
                    } else if (state.filter != LibraryFilter.All) {
                        EmptyState(
                            icon = if (state.filter == LibraryFilter.Favorites) Icons.Rounded.StarBorder else Icons.Rounded.Folder,
                            title = stringResource(if (state.filter == LibraryFilter.Favorites) R.string.scripts_empty_favorites_title else R.string.scripts_empty_folder_title),
                            message = stringResource(if (state.filter == LibraryFilter.Favorites) R.string.scripts_empty_favorites_message else R.string.scripts_empty_folder_message),
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Rounded.Description,
                            title = stringResource(R.string.scripts_empty_title),
                            message = stringResource(R.string.scripts_empty_message),
                            actionText = if (state.pickMode) null else stringResource(R.string.scripts_new),
                            onAction = if (state.pickMode) null else ({ showCreate = true }),
                        )
                    }
                }
                else -> items(state.items, key = { it.script.id }) { item ->
                    val folder = state.folders.firstOrNull { it.id == item.script.folderId }
                    SwipeableScriptCard(
                        item = item,
                        folder = folder,
                        enabled = !state.pickMode,
                        wordsPerMinute = item.script.prompterSettings?.wordsPerMinute ?: state.wordsPerMinute,
                        untitled = untitled,
                        onClick = {
                            if (state.pickMode) onPicked(item.script.id) else onOpenEditor(item.script.id)
                        },
                        onLongClick = {
                            if (!state.pickMode) {
                                haptics.perform(HapticEvent.LONG_PRESS)
                                actionsFor = item.script
                            }
                        },
                        onFavorite = { viewModel.toggleFavorite(item.script) },
                        onDelete = { viewModel.delete(item.script) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    actionsFor?.let { script ->
        ScriptActionsSheet(
            script = script,
            onDismiss = { actionsFor = null },
            onOpenPrompter = { actionsFor = null; onOpenPrompter(script.id) },
            onRecord = { actionsFor = null; onRecord(script.id) },
            onEdit = { actionsFor = null; onOpenEditor(script.id) },
            onDuplicate = { actionsFor = null; viewModel.duplicate(script, copySuffix) },
            onMove = { actionsFor = null; moveFor = script },
            onFavorite = { actionsFor = null; viewModel.toggleFavorite(script) },
            onDelete = { actionsFor = null; viewModel.delete(script) },
        )
    }
    moveFor?.let { script ->
        MoveToFolderSheet(
            folders = state.folders,
            current = script.folderId,
            onSelect = { folderId -> viewModel.move(script, folderId); moveFor = null },
            onNewFolder = { moveFor = null; folderDialog = FolderDialogMode.Create },
            onDismiss = { moveFor = null },
        )
    }
    if (showCreate) {
        CreateSheet(
            onDismiss = { showCreate = false },
            onNew = { showCreate = false; onNewScript((state.filter as? LibraryFilter.Folder)?.id) },
            onImport = { showCreate = false; importLauncher.launch(ScriptImport.PICKER_MIME_TYPES) },
            onPaste = {
                showCreate = false
                viewModel.createFromText(readClipboard(context), untitled)
            },
        )
    }
    folderDialog?.let { mode ->
        FolderDialog(
            mode = mode,
            onConfirm = { name, color ->
                when (mode) {
                    FolderDialogMode.Create -> viewModel.createFolder(name, color)
                    is FolderDialogMode.Rename -> viewModel.renameFolder(mode.folder, name)
                }
                folderDialog = null
            },
            onDismiss = { folderDialog = null },
        )
    }
    folderMenu?.let { folder ->
        RgBottomSheetCompat(title = folder.name, onDismiss = { folderMenu = null }) {
            RgListItem(stringResource(UiR.string.action_rename), icon = Icons.Rounded.Edit, onClick = {
                folderMenu = null
                folderDialog = FolderDialogMode.Rename(folder)
            })
            RgListItem(
                stringResource(R.string.scripts_folder_delete),
                subtitle = stringResource(R.string.scripts_folder_delete_hint),
                icon = Icons.Rounded.Delete,
                iconTint = RgTheme.colors.danger,
                onClick = { folderMenu = null; deleteFolder = folder },
            )
        }
    }
    deleteFolder?.let { folder ->
        RgConfirmDialog(
            title = stringResource(R.string.scripts_folder_delete_title, folder.name),
            message = stringResource(R.string.scripts_folder_delete_hint),
            confirmText = stringResource(UiR.string.action_delete),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = { viewModel.deleteFolder(folder); deleteFolder = null },
            onDismiss = { deleteFolder = null },
            destructive = true,
        )
    }
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
private fun SortMenu(sort: ScriptSortOrder, onSort: (ScriptSortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RgIconButton(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.scripts_sort), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScriptSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Text(
                            sortLabel(order),
                            color = if (order == sort) RgTheme.colors.accent else RgTheme.colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = { onSort(order); open = false },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(order: ScriptSortOrder): String = stringResource(
    when (order) {
        ScriptSortOrder.UPDATED_DESC -> R.string.scripts_sort_updated
        ScriptSortOrder.CREATED_DESC -> R.string.scripts_sort_created
        ScriptSortOrder.TITLE_ASC -> R.string.scripts_sort_title
        ScriptSortOrder.LAST_OPENED_DESC -> R.string.scripts_sort_opened
    },
)

@Composable
private fun FilterRow(
    state: ScriptsUiState,
    onSelect: (LibraryFilter) -> Unit,
    onNewFolder: () -> Unit,
    onFolderLongPress: (ScriptFolder) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item { FilterChip(stringResource(R.string.scripts_filter_all), state.filter == LibraryFilter.All, null, { onSelect(LibraryFilter.All) }) }
        item {
            FilterChip(
                stringResource(R.string.scripts_filter_favorites), state.filter == LibraryFilter.Favorites, null,
                { onSelect(LibraryFilter.Favorites) }, icon = Icons.Rounded.Star,
            )
        }
        items(state.folders, key = { it.id }) { folder ->
            val selected = (state.filter as? LibraryFilter.Folder)?.id == folder.id
            FilterChip(folder.name, selected, Color(folder.colorArgb), { onSelect(LibraryFilter.Folder(folder.id)) }, onLongClick = { onFolderLongPress(folder) })
        }
        item { FilterChip(stringResource(R.string.scripts_folder_new), false, null, onNewFolder, icon = Icons.Rounded.CreateNewFolder) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    dot: Color?,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) colors.accent else colors.surface)
            .then(if (!selected) Modifier.border(1.dp, colors.outline, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(Spacing.sm))
        }
        if (icon != null) {
            Icon(icon, null, tint = if (selected) colors.onAccent else colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onAccent else colors.textPrimary, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableScriptCard(
    item: ScriptItem,
    folder: ScriptFolder?,
    enabled: Boolean,
    wordsPerMinute: Int,
    untitled: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        modifier = modifier.padding(horizontal = Spacing.gutter),
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.StartToEnd -> {
                    onFavorite()
                    scope.launch { state.reset() }
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = {
            val direction = state.dismissDirection
            val deleting = direction == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(if (deleting) RgTheme.colors.danger.copy(alpha = 0.18f) else RgTheme.colors.pastelButter)
                    .padding(horizontal = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        if (deleting) Icons.Rounded.Delete else if (item.script.isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star,
                        null,
                        tint = if (deleting) RgTheme.colors.danger else RgTheme.colors.warning,
                    )
                }
            }
        },
    ) {
        ScriptCard(item, folder, wordsPerMinute, untitled, onClick, onLongClick, onFavorite)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptCard(
    item: ScriptItem,
    folder: ScriptFolder?,
    wordsPerMinute: Int,
    untitled: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.lg)
    val script = item.script
    val matchBg = colors.pastelButter
    val uiDirection = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(Spacing.lg)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (folder != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(folder.colorArgb)))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                highlight(script.title.ifBlank { untitled }, item.titleMatches, matchBg),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (script.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                stringResource(if (script.isFavorite) R.string.scripts_unfavorite else R.string.scripts_favorite),
                tint = if (script.isFavorite) colors.warning else colors.textTertiary,
                modifier = Modifier.size(36.dp).clip(CircleShape).pressable(haptic = HapticEvent.TOGGLE_ON, onClick = onFavorite).padding(6.dp),
            )
        }
        if (item.preview.isNotBlank()) {
            val direction = script.direction.resolve(item.preview, uiDirection)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Text(
                    highlight(item.preview, item.previewMatches, matchBg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            stringResource(
                R.string.scripts_card_meta,
                item.words.toString().localizeDigits(),
                formatDurationMs(item.durationMs),
                wordsPerMinute.toString().localizeDigits(),
                formatShortDate(script.updatedAt),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ScriptActionsSheet(
    script: Script,
    onDismiss: () -> Unit,
    onOpenPrompter: () -> Unit,
    onRecord: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    RgBottomSheetCompat(title = script.title, onDismiss = onDismiss) {
        RgListItem(stringResource(R.string.scripts_action_prompter), icon = Icons.Rounded.Slideshow, onClick = onOpenPrompter)
        RgListItem(stringResource(R.string.scripts_action_record), icon = Icons.Rounded.Videocam, onClick = onRecord)
        RgListItem(stringResource(UiR.string.action_edit), icon = Icons.Rounded.Edit, onClick = onEdit)
        RgListItem(stringResource(UiR.string.action_duplicate), icon = Icons.Rounded.ContentCopy, onClick = onDuplicate)
        RgListItem(stringResource(R.string.scripts_action_move), icon = Icons.AutoMirrored.Rounded.DriveFileMove, onClick = onMove)
        RgListItem(
            stringResource(if (script.isFavorite) R.string.scripts_unfavorite else R.string.scripts_favorite),
            icon = if (script.isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star,
            onClick = onFavorite,
        )
        RgListItem(
            stringResource(UiR.string.action_delete),
            icon = Icons.Rounded.Delete,
            iconTint = RgTheme.colors.danger,
            iconBackground = RgTheme.colors.danger.copy(alpha = 0.12f),
            onClick = onDelete,
        )
    }
}

@Composable
private fun MoveToFolderSheet(
    folders: List<ScriptFolder>,
    current: String?,
    onSelect: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheetCompat(title = stringResource(R.string.scripts_action_move), onDismiss = onDismiss) {
        RgListItem(
            stringResource(R.string.scripts_no_folder),
            icon = Icons.Rounded.FolderOff,
            onClick = { onSelect(null) },
            trailing = if (current == null) ({ Icon(Icons.Rounded.Star, null, tint = RgTheme.colors.accent, modifier = Modifier.size(16.dp)) }) else null,
        )
        folders.forEach { folder ->
            RgListItem(
                folder.name,
                icon = Icons.Rounded.Folder,
                iconTint = Color(folder.colorArgb),
                iconBackground = Color(folder.colorArgb).copy(alpha = 0.16f),
                onClick = { onSelect(folder.id) },
                trailing = if (current == folder.id) ({ Icon(Icons.Rounded.Star, null, tint = RgTheme.colors.accent, modifier = Modifier.size(16.dp)) }) else null,
            )
        }
        RgListItem(stringResource(R.string.scripts_folder_new), icon = Icons.Rounded.CreateNewFolder, onClick = onNewFolder)
    }
}

@Composable
private fun CreateSheet(onDismiss: () -> Unit, onNew: () -> Unit, onImport: () -> Unit, onPaste: () -> Unit) {
    RgBottomSheetCompat(title = stringResource(R.string.scripts_new), onDismiss = onDismiss) {
        RgListItem(stringResource(R.string.scripts_create_blank), subtitle = stringResource(R.string.scripts_create_blank_hint), icon = Icons.Rounded.Edit, onClick = onNew)
        RgListItem(stringResource(R.string.scripts_create_import), subtitle = stringResource(R.string.scripts_create_import_hint), icon = Icons.Rounded.FileOpen, onClick = onImport)
        RgListItem(stringResource(R.string.scripts_create_paste), subtitle = stringResource(R.string.scripts_create_paste_hint), icon = Icons.Rounded.ContentPaste, onClick = onPaste)
    }
}

/** Bottom sheet wrapper with the list padding used across this screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RgBottomSheetCompat(title: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    RgBottomSheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.padding(horizontal = Spacing.md)) { content() }
    }
}
