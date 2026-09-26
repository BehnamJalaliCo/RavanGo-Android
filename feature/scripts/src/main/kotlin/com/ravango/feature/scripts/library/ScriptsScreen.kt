@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import com.ravango.core.designsystem.theme.Palette
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import com.ravango.feature.scripts.common.SkeletonBlock
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
import androidx.compose.material3.SnackbarHostState
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

    ScriptsContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onSort = viewModel::setSort,
        onQueryChange = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onNewFolder = { folderDialog = FolderDialogMode.Create },
        onFolderLongPress = { folderMenu = it },
        onCreate = { showCreate = true },
        onOpen = { script -> if (state.pickMode) onPicked(script.id) else onOpenEditor(script.id) },
        onLongPress = { script ->
            if (!state.pickMode) {
                haptics.perform(HapticEvent.LONG_PRESS)
                actionsFor = script
            }
        },
        onFavorite = viewModel::toggleFavorite,
        onDelete = viewModel::delete,
    )

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
        RgBottomSheet(onDismiss = { folderMenu = null }) {
            FolderMenuContent(
                folder = folder,
                onRename = { folderMenu = null; folderDialog = FolderDialogMode.Rename(folder) },
                onDelete = { folderMenu = null; deleteFolder = folder },
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

/** Stateless library screen (rendered by screenshot tests with sample data). */
@Composable
internal fun ScriptsContent(
    state: ScriptsUiState,
    onBack: () -> Unit,
    onSort: (ScriptSortOrder) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onNewFolder: () -> Unit,
    onFolderLongPress: (ScriptFolder) -> Unit,
    onCreate: () -> Unit,
    onOpen: (Script) -> Unit,
    onLongPress: (Script) -> Unit,
    onFavorite: (Script) -> Unit,
    onDelete: (Script) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val untitled = stringResource(R.string.scripts_untitled)
    val libraryEmpty = !state.loading && state.items.isEmpty() && state.query.isBlank() && state.filter == LibraryFilter.All
    RgScreen(
        title = stringResource(if (state.pickMode) R.string.scripts_pick_title else R.string.scripts_title),
        subtitle = if (state.items.isNotEmpty() && !state.loading) {
            stringResource(R.string.scripts_count, state.items.size.toString().localizeDigits())
        } else {
            null
        },
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = { if (!libraryEmpty) SortMenu(state.sort, onSort) },
        floatingActionButton = {
            if (!state.pickMode && !libraryEmpty) {
                RgPrimaryButton(
                    text = stringResource(R.string.scripts_new),
                    onClick = onCreate,
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
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                SearchBar(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.xs),
                )
            }
            item {
                FilterRow(
                    state = state,
                    onSelect = onFilter,
                    onNewFolder = onNewFolder,
                    onFolderLongPress = onFolderLongPress,
                )
            }
            when {
                state.loading -> items(3) { ScriptCardSkeleton(Modifier.padding(horizontal = Spacing.gutter)) }
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
                            onAction = if (state.pickMode) null else onCreate,
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
                        onClick = { onOpen(item.script) },
                        onLongClick = { onLongPress(item.script) },
                        onFavorite = { onFavorite(item.script) },
                        onDelete = { onDelete(item.script) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Pill search field (48dp) with a clear button that keeps a 48dp touch target. */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .padding(start = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.md))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.scripts_search_hint), style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(48.dp).clip(CircleShape).pressable { onQueryChange("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(UiR.string.action_close), tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
        } else {
            Spacer(Modifier.width(Spacing.lg))
        }
    }
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
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    // 36dp pill inside a 48dp-tall touch area.
    Box(
        Modifier
            .height(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, indication = null, interactionSource = null),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (selected) colors.accent else colors.surface)
                .then(if (!selected) Modifier.border(1.dp, colors.outline, shape) else Modifier)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) colors.onAccent else dot))
                Spacer(Modifier.width(Spacing.sm))
            }
            if (icon != null) {
                Icon(icon, null, tint = if (selected) colors.onAccent else colors.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onAccent else colors.textPrimary, maxLines = 1)
        }
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
    val matchBg = colors.warning.copy(alpha = if (colors.isDark) 0.32f else 0.28f)
    val uiDirection = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.md)
            .animateContentSize(),
    ) {
        // Header: overline (folder · date) and title; the favorite toggle keeps a full 48dp touch target.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(folder.colorArgb)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                    }
                    Text(formatShortDate(script.updatedAt), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary, maxLines = 1)
                }
                Text(
                    highlight(script.title.ifBlank { untitled }, item.titleMatches, matchBg),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (script.title.isBlank()) colors.textTertiary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .pressable(haptic = HapticEvent.TOGGLE_ON, onClick = onFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (script.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    stringResource(if (script.isFavorite) R.string.scripts_unfavorite else R.string.scripts_favorite),
                    tint = if (script.isFavorite) colors.warning else colors.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.padding(end = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (item.preview.isNotBlank()) {
                val direction = script.direction.resolve(item.preview, uiDirection)
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Text(
                        highlight(item.preview, item.previewMatches, matchBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.scripts_card_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary,
                )
            }
            if (item.words > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MetaPill(Icons.Rounded.Schedule, formatDurationMs(item.durationMs), stringResource(R.string.scripts_card_duration_cd, wordsPerMinute.toString().localizeDigits()))
                    MetaPill(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.scripts_card_words, item.words.toString().localizeDigits()), null)
                }
            }
        }
    }
}

@Composable
private fun MetaPill(icon: ImageVector, text: String, contentDescription: String?) {
    val colors = RgTheme.colors
    Row(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.surfaceMuted)
            .padding(horizontal = Spacing.sm)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
    }
}

/** Loading placeholder shaped like a script card. */
@Composable
private fun ScriptCardSkeleton(modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(18.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(12.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonBlock(Modifier.size(width = 64.dp, height = 24.dp), RoundedCornerShape(Radius.pill))
            SkeletonBlock(Modifier.size(width = 72.dp, height = 24.dp), RoundedCornerShape(Radius.pill))
        }
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
    RgBottomSheet(onDismiss = onDismiss) {
        ScriptActionsContent(script, onOpenPrompter, onRecord, onEdit, onDuplicate, onMove, onFavorite, onDelete)
    }
}

/** Body of the long-press sheet: the two primary actions as tiles, then secondary actions as a list. */
@Composable
internal fun ScriptActionsContent(
    script: Script,
    onOpenPrompter: () -> Unit,
    onRecord: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = RgTheme.colors
    SheetContent(title = script.title.ifBlank { stringResource(R.string.scripts_untitled) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ActionTile(Icons.Rounded.Slideshow, stringResource(R.string.scripts_action_prompter), colors.pastelLavender, colors.accent, onOpenPrompter, Modifier.weight(1f))
            ActionTile(Icons.Rounded.Videocam, stringResource(R.string.scripts_action_record), colors.pastelRose, colors.record, onRecord, Modifier.weight(1f))
        }
        RgListItem(stringResource(UiR.string.action_edit), icon = Icons.Rounded.Edit, onClick = onEdit, trailing = null)
        RgListItem(stringResource(UiR.string.action_duplicate), icon = Icons.Rounded.ContentCopy, onClick = onDuplicate, trailing = null)
        RgListItem(stringResource(R.string.scripts_action_move), icon = Icons.AutoMirrored.Rounded.DriveFileMove, onClick = onMove)
        RgListItem(
            stringResource(if (script.isFavorite) R.string.scripts_unfavorite else R.string.scripts_favorite),
            icon = if (script.isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star,
            iconTint = colors.warning,
            iconBackground = colors.pastelButter,
            onClick = onFavorite,
            trailing = null,
        )
        HorizontalDivider(color = colors.outline, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
        RgListItem(
            stringResource(UiR.string.action_delete),
            icon = Icons.Rounded.Delete,
            iconTint = colors.danger,
            iconBackground = colors.danger.copy(alpha = 0.12f),
            onClick = onDelete,
            trailing = null,
        )
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, container: Color, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(container)
            .pressable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.textPrimary, textAlign = TextAlign.Center, maxLines = 2)
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
    RgBottomSheet(onDismiss = onDismiss) { MoveToFolderContent(folders, current, onSelect, onNewFolder) }
}

@Composable
internal fun MoveToFolderContent(
    folders: List<ScriptFolder>,
    current: String?,
    onSelect: (String?) -> Unit,
    onNewFolder: () -> Unit,
) {
    val colors = RgTheme.colors
    val check: @Composable () -> Unit = { Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(22.dp)) }
    SheetContent(title = stringResource(R.string.scripts_action_move)) {
        RgListItem(
            stringResource(R.string.scripts_no_folder),
            icon = Icons.Rounded.FolderOff,
            iconTint = colors.textSecondary,
            iconBackground = colors.surfaceMuted,
            onClick = { onSelect(null) },
            trailing = if (current == null) check else null,
        )
        folders.forEach { folder ->
            RgListItem(
                folder.name,
                icon = Icons.Rounded.Folder,
                iconTint = Color(folder.colorArgb),
                iconBackground = Color(folder.colorArgb).copy(alpha = 0.16f),
                onClick = { onSelect(folder.id) },
                trailing = if (current == folder.id) check else null,
            )
        }
        RgListItem(stringResource(R.string.scripts_folder_new), icon = Icons.Rounded.CreateNewFolder, onClick = onNewFolder, trailing = null)
    }
}

@Composable
private fun CreateSheet(onDismiss: () -> Unit, onNew: () -> Unit, onImport: () -> Unit, onPaste: () -> Unit) {
    RgBottomSheet(onDismiss = onDismiss) { CreateContent(onNew, onImport, onPaste) }
}

@Composable
internal fun CreateContent(onNew: () -> Unit, onImport: () -> Unit, onPaste: () -> Unit) {
    val colors = RgTheme.colors
    SheetContent(title = stringResource(R.string.scripts_new)) {
        RgListItem(stringResource(R.string.scripts_create_blank), subtitle = stringResource(R.string.scripts_create_blank_hint), icon = Icons.Rounded.Edit, onClick = onNew)
        RgListItem(
            stringResource(R.string.scripts_create_import), subtitle = stringResource(R.string.scripts_create_import_hint), icon = Icons.Rounded.FileOpen,
            iconTint = Palette.Mint500, iconBackground = colors.pastelMint, onClick = onImport,
        )
        RgListItem(
            stringResource(R.string.scripts_create_paste), subtitle = stringResource(R.string.scripts_create_paste_hint), icon = Icons.Rounded.ContentPaste,
            iconTint = Palette.Peach400, iconBackground = colors.pastelPeach, onClick = onPaste,
        )
    }
}

/** Folder long-press menu body. */
@Composable
internal fun FolderMenuContent(folder: ScriptFolder, onRename: () -> Unit, onDelete: () -> Unit) {
    SheetContent(title = folder.name) {
        RgListItem(stringResource(UiR.string.action_rename), icon = Icons.Rounded.Edit, onClick = onRename, trailing = null)
        RgListItem(
            stringResource(R.string.scripts_folder_delete),
            subtitle = stringResource(R.string.scripts_folder_delete_hint),
            icon = Icons.Rounded.Delete,
            iconTint = RgTheme.colors.danger,
            iconBackground = RgTheme.colors.danger.copy(alpha = 0.12f),
            onClick = onDelete,
            trailing = null,
        )
    }
}

/** Sheet body: title on the screen gutter, list rows inset so their 12dp padding lines text up with the title. */
@Composable
internal fun SheetContent(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = RgTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.xs, bottom = Spacing.sm),
            )
        }
        Column(Modifier.padding(horizontal = Spacing.sm), content = content)
    }
}
