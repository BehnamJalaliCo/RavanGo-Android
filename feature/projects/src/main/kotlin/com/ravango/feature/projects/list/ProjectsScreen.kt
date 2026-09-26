package com.ravango.feature.projects.list

import com.ravango.core.designsystem.component.RgSpinner
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.ShimmerBox
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.TabularNumbers
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import com.ravango.core.ui.messageRes
import com.ravango.core.ui.shareMedia
import com.ravango.feature.projects.R
import com.ravango.feature.projects.common.ProjectThumbnail
import com.ravango.feature.projects.common.aspectLabel
import com.ravango.feature.projects.common.currentLocale
import com.ravango.feature.projects.common.formatRelativeTime
import com.ravango.feature.projects.common.rememberEntranceActive
import com.ravango.feature.projects.common.staggeredEntrance
import kotlinx.coroutines.launch
import java.io.File
import com.ravango.core.ui.R as UiR

private const val MAX_IMPORT_ITEMS = 30

@Composable
fun ProjectsRoute(
    onBack: () -> Unit,
    onOpenEditor: (String) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val fallbackTitle = stringResource(R.string.projects_imported_title)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_ITEMS)) { uris ->
        viewModel.import(uris, fallbackTitle)
    }

    val undoText = stringResource(UiR.string.action_undo)
    val shareTitle = stringResource(UiR.string.action_share)
    val nothingToShare = stringResource(R.string.projects_share_unavailable)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ProjectsEvent.OpenEditor -> onOpenEditor(event.projectId)
                is ProjectsEvent.Deleted -> {
                    // A newer delete replaces the visible snackbar; the older batch then commits via its finally.
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch {
                        var undone = false
                        try {
                            val count = event.ids.size
                            val message = context.resources.getQuantityString(R.plurals.projects_deleted, count, count.toString().localizeDigits())
                            val result = snackbar.showSnackbar(message, actionLabel = undoText, duration = SnackbarDuration.Long)
                            if (result == SnackbarResult.ActionPerformed) {
                                undone = true
                                haptics.perform(HapticEvent.CONFIRM)
                                viewModel.undoDelete(event.ids)
                            }
                        } finally {
                            if (!undone) viewModel.commitDelete(event.ids)
                        }
                    }
                }
                is ProjectsEvent.Duplicated -> snackbar.showSnackbar(context.getString(R.string.projects_duplicated, event.title))
                is ProjectsEvent.Share -> {
                    val uri = context.shareableUri(event.uri)
                    if (uri == null) snackbar.showSnackbar(nothingToShare) else runCatching { context.shareMedia(uri, event.mimeType, shareTitle) }
                        .onFailure { snackbar.showSnackbar(nothingToShare) }
                }
                ProjectsEvent.NothingToShare -> snackbar.showSnackbar(nothingToShare)
                is ProjectsEvent.Error -> snackbar.showSnackbar(context.getString(event.kind.messageRes()))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    ProjectsContent(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onTab = viewModel::selectTab,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onOpen = onOpenEditor,
        onToggleSelect = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onDelete = viewModel::delete,
        onRename = viewModel::rename,
        onDuplicate = viewModel::duplicate,
        onShare = viewModel::share,
        onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
    )
    ImportingOverlay(state.importing)
    }
}

/** Converts a stored export location into a URI other apps can read. */
private fun Context.shareableUri(raw: String): Uri? {
    if (raw.startsWith("content://")) return Uri.parse(raw)
    val file = File(raw.removePrefix("file://"))
    if (!file.exists()) return null
    return runCatching { FileProvider.getUriForFile(this, "$packageName.files", file) }.getOrNull()
}

@Composable
internal fun ProjectsContent(
    state: ProjectsUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTab: (ProjectsTab) -> Unit,
    onQuery: (String) -> Unit,
    onSort: (ProjectSort) -> Unit,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: (Set<String>) -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String, String) -> Unit,
    onShare: (String) -> Unit,
    onImport: () -> Unit,
) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    var searchOpen by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var menuFor by remember { mutableStateOf<ProjectItem?>(null) }
    var renameFor by remember { mutableStateOf<Project?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    val entrance = rememberEntranceActive(ready = !state.loading)
    val copySuffix = stringResource(R.string.projects_copy_suffix)

    BackHandler(enabled = state.selectionMode) { onClearSelection() }

    val title = if (state.selectionMode) {
        pluralStringResource(R.plurals.projects_selected, state.selection.size, state.selection.size.toString().localizeDigits(locale))
    } else {
        stringResource(R.string.projects_title)
    }

    RgScreen(
        title = title,
        onBack = if (state.selectionMode) onClearSelection else onBack,
        snackbarHostState = snackbar,
        actions = {
            if (state.selectionMode) {
                RgIconButton(Icons.Rounded.DoneAll, stringResource(R.string.projects_select_all), onSelectAll)
                RgIconButton(Icons.Rounded.Delete, stringResource(UiR.string.action_delete), { confirmDelete = state.selection }, tint = colors.danger)
            } else {
                RgIconButton(
                    if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                    stringResource(UiR.string.action_search),
                    {
                        if (searchOpen) onQuery("")
                        searchOpen = !searchOpen
                    },
                    selected = searchOpen,
                )
            }
        },
        floatingActionButton = {
            val libraryEmpty = !state.loading && state.totalProjects == 0
            AnimatedVisibility(!state.selectionMode && !libraryEmpty, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                RgPrimaryButton(
                    text = stringResource(R.string.projects_import),
                    onClick = onImport,
                    icon = Icons.Rounded.AddPhotoAlternate,
                    loading = state.importing,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            // 148dp keeps two columns on 360dp phones (2 × 148 + 12 gap ≤ 360 − 2 × 20 gutter).
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        RgTextField(
                            value = state.query,
                            onValueChange = onQuery,
                            placeholder = stringResource(R.string.projects_search_hint),
                            leadingIcon = Icons.Rounded.Search,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                        )
                    }
                    RgSegmentedControl(
                        options = ProjectsTab.entries,
                        selected = state.tab,
                        onSelect = onTab,
                        label = { tabLabel(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.loading || state.totalProjects > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        RgTag(
                            text = stringResource(R.string.projects_storage_used, formatBytes(state.storageBytes, locale)),
                            icon = Icons.Rounded.SdStorage,
                            color = colors.surface,
                            contentColor = colors.textSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        RgChip(stringResource(R.string.projects_sort_recent), state.sort == ProjectSort.RECENT, { onSort(ProjectSort.RECENT) })
                        Spacer(Modifier.width(Spacing.sm))
                        RgChip(stringResource(R.string.projects_sort_name), state.sort == ProjectSort.NAME, { onSort(ProjectSort.NAME) })
                    }
                }
            }

            when {
                state.loading -> items(4, key = { "placeholder$it" }) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ShimmerBox(Modifier.fillMaxWidth().aspectRatio(0.8f), RoundedCornerShape(Radius.lg))
                        ShimmerBox(Modifier.fillMaxWidth(0.7f).height(14.dp))
                    }
                }
                state.items.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    ProjectsEmpty(state, onImport)
                }
                else -> items(state.items, key = { it.project.id }) { item ->
                    val index = state.items.indexOf(item)
                    ProjectCard(
                        item = item,
                        selected = item.project.id in state.selection,
                        selectionMode = state.selectionMode,
                        onClick = { if (state.selectionMode) onToggleSelect(item.project.id) else onOpen(item.project.id) },
                        onLongClick = { onToggleSelect(item.project.id) },
                        onMore = { menuFor = item },
                        modifier = Modifier.animateItem().staggeredEntrance(index, entrance),
                    )
                }
            }
        }
    }

    menuFor?.let { item ->
        ProjectMenuSheet(
            item = item,
            onDismiss = { menuFor = null },
            onOpen = { menuFor = null; onOpen(item.project.id) },
            onRename = { menuFor = null; renameFor = item.project },
            onDuplicate = { menuFor = null; onDuplicate(item.project.id, copySuffix) },
            onShare = { menuFor = null; onShare(item.project.id) },
            onDelete = { menuFor = null; confirmDelete = setOf(item.project.id) },
        )
    }

    renameFor?.let { project ->
        RenameDialog(
            initial = project.title,
            onConfirm = { onRename(project.id, it); renameFor = null },
            onDismiss = { renameFor = null },
        )
    }

    confirmDelete?.let { ids ->
        RgConfirmDialog(
            title = pluralStringResource(R.plurals.projects_delete_title, ids.size, ids.size.toString().localizeDigits(locale)),
            message = stringResource(R.string.projects_delete_message),
            confirmText = stringResource(UiR.string.action_delete),
            dismissText = stringResource(UiR.string.action_cancel),
            onConfirm = { onDelete(ids); confirmDelete = null },
            onDismiss = { confirmDelete = null },
            destructive = true,
        )
    }
}

@Composable
private fun tabLabel(tab: ProjectsTab): String = stringResource(
    when (tab) {
        ProjectsTab.ALL -> R.string.projects_tab_all
        ProjectsTab.DRAFTS -> R.string.projects_tab_drafts
        ProjectsTab.EXPORTED -> R.string.projects_tab_exported
    },
)

@Composable
private fun ProjectsEmpty(state: ProjectsUiState, onImport: () -> Unit) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Rounded.Search,
            title = stringResource(R.string.projects_empty_search_title),
            message = stringResource(R.string.projects_empty_search_message),
        )
        state.totalProjects == 0 -> EmptyState(
            icon = Icons.Rounded.VideoLibrary,
            title = stringResource(R.string.projects_empty_title),
            message = stringResource(R.string.projects_empty_message),
            actionText = stringResource(R.string.projects_import),
            onAction = onImport,
        )
        state.tab == ProjectsTab.DRAFTS -> EmptyState(
            icon = Icons.Rounded.Edit,
            title = stringResource(R.string.projects_empty_drafts_title),
            message = stringResource(R.string.projects_empty_drafts_message),
        )
        else -> EmptyState(
            icon = Icons.Rounded.Upload,
            title = stringResource(R.string.projects_empty_exported_title),
            message = stringResource(R.string.projects_empty_exported_message),
        )
    }
}

@Composable
fun statusLabel(status: ProjectStatus): String = stringResource(
    when (status) {
        ProjectStatus.RECORDED -> R.string.projects_status_recorded
        ProjectStatus.EDITING -> R.string.projects_status_editing
        ProjectStatus.EXPORTED -> R.string.projects_status_exported
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    item: ProjectItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> Motion.PressScale
            selected -> 0.94f
            else -> 1f
        },
        Motion.snappy(), label = "cardScale",
    )
    val project = item.project
    val shape = RoundedCornerShape(Radius.lg)

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = { haptics.perform(HapticEvent.LONG_PRESS); onLongClick() },
                onClick = { haptics.perform(HapticEvent.TAP); onClick() },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(shape)
                .then(if (selected) Modifier.border(3.dp, colors.accent, shape) else Modifier.border(1.dp, colors.outline, shape)),
        ) {
            ProjectThumbnail(item.thumbnail, accentSeed = project.id, audioOnly = item.audioOnly, modifier = Modifier.fillMaxSize())
            OverlayPill(statusLabel(project.status), Modifier.align(Alignment.TopStart).padding(Spacing.sm), dot = statusColor(project.status))
            OverlayPill(aspectLabel(project.aspectRatio, locale), Modifier.align(Alignment.BottomStart).padding(Spacing.sm))
            if (project.durationUs > 0) {
                OverlayPill(formatDuration(project.durationUs, locale), Modifier.align(Alignment.BottomEnd).padding(Spacing.sm))
            }
            SelectionCheck(selectionMode, selected, Modifier.align(Alignment.TopEnd).padding(Spacing.sm))
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                Text(project.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    formatRelativeTime(project.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionMode) {
                RgIconButton(Icons.Rounded.MoreVert, stringResource(UiR.string.action_more), onMore, size = 40.dp, iconSize = 20.dp, container = Color.Transparent, tint = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SelectionCheck(visible: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    AnimatedVisibility(visible, modifier, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Black.copy(alpha = 0.3f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

/** Status dot colors tuned to read on the dark frosted overlay pill. */
private fun statusColor(status: ProjectStatus): Color = when (status) {
    ProjectStatus.RECORDED -> Palette.Butter400
    ProjectStatus.EDITING -> Palette.Lavender300
    ProjectStatus.EXPORTED -> Palette.Mint400
}

/** Frosted metadata pill over thumbnails; fixed 22dp so pills in all four corners share one size. */
@Composable
private fun OverlayPill(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = Color.White, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectMenuSheet(
    item: ProjectItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = RgTheme.colors
    RgBottomSheet(onDismiss = onDismiss, title = item.project.title) {
        Column(Modifier.padding(horizontal = Spacing.md)) {
            RgListItem(stringResource(R.string.projects_open_in_editor), icon = Icons.Rounded.FolderOpen, onClick = onOpen)
            RgListItem(stringResource(UiR.string.action_rename), icon = Icons.Rounded.DriveFileRenameOutline, onClick = onRename)
            RgListItem(stringResource(UiR.string.action_duplicate), icon = Icons.Rounded.ContentCopy, onClick = onDuplicate)
            if (item.hasExport) {
                RgListItem(stringResource(R.string.projects_share_export), icon = Icons.Rounded.Share, onClick = onShare)
            } else {
                RgListItem(
                    stringResource(R.string.projects_share_export),
                    subtitle = stringResource(R.string.projects_share_not_exported),
                    icon = Icons.Rounded.Share,
                    iconTint = colors.textTertiary,
                    iconBackground = colors.surfaceMuted,
                    trailing = null,
                )
            }
            RgListItem(
                stringResource(UiR.string.action_delete),
                icon = Icons.Rounded.Delete,
                iconTint = colors.danger,
                iconBackground = colors.pastelRose,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = RgTheme.colors
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(stringResource(R.string.projects_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            RgTextField(
                value = text,
                onValueChange = { text = it.take(120) },
                placeholder = stringResource(R.string.projects_rename_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = { RgTextButton(stringResource(UiR.string.action_save), { onConfirm(text) }, enabled = text.isNotBlank()) },
        dismissButton = { RgTextButton(stringResource(UiR.string.action_cancel), onDismiss, color = colors.textSecondary) },
    )
}

/** Importing overlay shared by the grid screen. */
@Composable
internal fun ImportingOverlay(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        // Swallows touches so nothing underneath is triggered while media is prepared.
        Box(Modifier.fillMaxSize().background(RgTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
            GlassSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RgSpinner(Modifier.size(22.dp), color = RgTheme.colors.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.projects_importing), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                }
            }
        }
    }
}
