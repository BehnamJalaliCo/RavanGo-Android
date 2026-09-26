package com.ravango.feature.projects.list

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.Project
import com.ravango.core.navigation.ProjectsRoute
import com.ravango.feature.projects.common.MediaImporter
import com.ravango.feature.projects.common.ThumbnailSource
import com.ravango.feature.projects.common.firstVisualAssets
import com.ravango.feature.projects.common.thumbnailFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

data class ProjectItem(
    val project: Project,
    val thumbnail: ThumbnailSource?,
    val hasDraft: Boolean,
    val hasExport: Boolean,
    val audioOnly: Boolean,
)

data class ProjectsUiState(
    val loading: Boolean = true,
    val tab: ProjectsTab = ProjectsTab.ALL,
    val query: String = "",
    val sort: ProjectSort = ProjectSort.RECENT,
    val items: List<ProjectItem> = emptyList(),
    /** Projects in the library at all (to tell "no results" from "no projects"). */
    val totalProjects: Int = 0,
    val storageBytes: Long = 0,
    val selection: Set<String> = emptySet(),
    val importing: Boolean = false,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

sealed interface ProjectsEvent {
    data class OpenEditor(val projectId: String) : ProjectsEvent
    data class Deleted(val ids: Set<String>) : ProjectsEvent
    data class Duplicated(val title: String) : ProjectsEvent
    data class Share(val uri: String, val mimeType: String) : ProjectsEvent
    data object NothingToShare : ProjectsEvent
    data class Error(val kind: ErrorKind) : ProjectsEvent
}

private data class Controls(val tab: ProjectsTab, val query: String, val sort: ProjectSort, val hidden: Set<String>)

private data class Library(
    val projects: List<Project>,
    val draftIds: Set<String>,
    val firstVisual: Map<String, MediaAsset>,
    val exportedIds: Set<String>,
    val audioIds: Set<String>,
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val importer: MediaImporter,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val initialTab = ProjectsTab.fromIndex(runCatching { savedStateHandle.toRoute<ProjectsRoute>().tab }.getOrDefault(0))
    private val tab = MutableStateFlow(savedStateHandle.get<String>(KEY_TAB)?.let { runCatching { ProjectsTab.valueOf(it) }.getOrNull() } ?: initialTab)
    private val query = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY).orEmpty())
    private val sort = MutableStateFlow(savedStateHandle.get<String>(KEY_SORT)?.let { runCatching { ProjectSort.valueOf(it) }.getOrNull() } ?: ProjectSort.RECENT)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val pendingDeletion = MutableStateFlow<Set<String>>(emptySet())
    /** Deletes in flight: kept hidden until the database emits without them. */
    private val committing = MutableStateFlow<Set<String>>(emptySet())
    private val importing = MutableStateFlow(false)

    private val events = Channel<ProjectsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val collator: Comparator<String> = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }.let { c -> Comparator { a, b -> c.compare(a, b) } }

    private val library = combine(
        repository.observeProjects(),
        repository.observeDrafts().map { drafts -> drafts.map { it.projectId }.toSet() },
        repository.observeAllAssets(),
    ) { projects, draftIds, assets ->
        val live = assets.filter { it.deletedAt == null && it.projectId != null }
        val byProject = live.groupBy { it.projectId!! }
        Library(
            projects = projects.filter { it.deletedAt == null },
            draftIds = draftIds,
            firstVisual = firstVisualAssets(live),
            exportedIds = live.filter { it.origin == MediaOrigin.EXPORTED }.mapNotNull { it.projectId }.toSet(),
            audioIds = byProject.filterValues { list -> list.isNotEmpty() && list.all { it.kind == MediaKind.AUDIO } }.keys,
        )
    }

    private val controls = combine(tab, query, sort, pendingDeletion, committing) { t, q, s, pending, inFlight -> Controls(t, q, s, pending + inFlight) }

    val uiState: StateFlow<ProjectsUiState> = combine(
        library,
        controls,
        repository.observeStorageBytes(),
        selection,
        importing,
    ) { lib, c, bytes, sel, busy ->
        val visible = filterProjects(lib.projects, lib.draftIds, c.tab, c.query, c.sort, c.hidden, collator)
        ProjectsUiState(
            loading = false,
            tab = c.tab,
            query = c.query,
            sort = c.sort,
            items = visible.map { p ->
                ProjectItem(
                    project = p,
                    thumbnail = thumbnailFor(p, lib.firstVisual[p.id]),
                    hasDraft = p.id in lib.draftIds,
                    hasExport = p.lastExportUri != null || p.id in lib.exportedIds,
                    audioOnly = p.id in lib.audioIds,
                )
            },
            totalProjects = lib.projects.count { it.id !in c.hidden },
            storageBytes = bytes,
            selection = sel.filterTo(mutableSetOf()) { id -> visible.any { it.id == id } },
            importing = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState(tab = tab.value, query = query.value, sort = sort.value))

    fun selectTab(value: ProjectsTab) {
        tab.value = value
        savedStateHandle[KEY_TAB] = value.name
        selection.value = emptySet()
    }

    fun setQuery(value: String) {
        query.value = value
        savedStateHandle[KEY_QUERY] = value
    }

    fun setSort(value: ProjectSort) {
        sort.value = value
        savedStateHandle[KEY_SORT] = value.name
    }

    fun toggleSelection(id: String) = selection.update { if (id in it) it - id else it + id }
    fun selectAll() { selection.value = uiState.value.items.map { it.project.id }.toSet() }
    fun clearSelection() { selection.value = emptySet() }

    fun rename(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { report(outcomeOf { repository.rename(id, clean.take(120)) }) }
    }

    fun duplicate(id: String, copySuffix: String) {
        viewModelScope.launch {
            when (val r = outcomeOf { repository.duplicate(id, copySuffix) }) {
                is Outcome.Success -> r.value?.let { events.send(ProjectsEvent.Duplicated(it.title)) }
                is Outcome.Failure -> events.send(ProjectsEvent.Error(r.kind))
            }
        }
    }

    /**
     * Hides the projects immediately and lets the user undo. The real (file-deleting) delete runs in [commitDelete]
     * once the undo window closes, so undo never has to resurrect files.
     */
    fun delete(ids: Set<String>) {
        if (ids.isEmpty()) return
        pendingDeletion.update { it + ids }
        selection.value = emptySet()
        viewModelScope.launch { events.send(ProjectsEvent.Deleted(ids)) }
    }

    /** Restores a batch hidden by [delete]; ids already committed are unaffected. */
    fun undoDelete(ids: Set<String>) = pendingDeletion.update { it - ids }

    /** Permanently deletes the given batch (only ids still awaiting undo), or every pending id when [ids] is null. */
    fun commitDelete(ids: Set<String>? = null) {
        val pending = pendingDeletion.value
        val toCommit = if (ids == null) pending else pending intersect ids
        if (toCommit.isEmpty()) return
        committing.update { it + toCommit }
        pendingDeletion.update { it - toCommit }
        // Runs outside the screen lifecycle so leaving the screen never loses a confirmed delete.
        appScope.launch {
            toCommit.forEach { id -> runCatching { repository.delete(id) }.onFailure { RgLog.e(TAG, "Delete failed for $id", it) } }
            committing.update { it - toCommit }
        }
    }

    fun share(id: String) {
        viewModelScope.launch {
            val target = runCatching { resolveExport(id) }.getOrNull()
            events.send(target ?: ProjectsEvent.NothingToShare)
        }
    }

    private suspend fun resolveExport(id: String): ProjectsEvent.Share? {
        val project = repository.getProject(id) ?: return null
        val export = repository.assets(id).filter { it.origin == MediaOrigin.EXPORTED && it.deletedAt == null }.maxByOrNull { it.createdAt }
        export?.publishedUri?.let { return ProjectsEvent.Share(it, export.mimeType.ifBlank { "video/mp4" }) }
        export?.uri?.let { return ProjectsEvent.Share(it, export.mimeType.ifBlank { "video/mp4" }) }
        return project.lastExportUri?.let { ProjectsEvent.Share(it, "video/mp4") }
    }

    fun import(uris: List<Uri>, fallbackTitle: String) {
        if (uris.isEmpty() || importing.value) return
        importing.value = true
        viewModelScope.launch {
            val result = importer.importAsProject(uris, fallbackTitle)
            importing.value = false
            when (result) {
                is Outcome.Success -> events.send(ProjectsEvent.OpenEditor(result.value.id))
                is Outcome.Failure -> if (result.kind != ErrorKind.CANCELLED) events.send(ProjectsEvent.Error(result.kind))
            }
        }
    }

    private suspend fun report(outcome: Outcome<*>) {
        if (outcome is Outcome.Failure) events.send(ProjectsEvent.Error(outcome.kind))
    }

    override fun onCleared() {
        commitDelete()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Projects"
        const val KEY_TAB = "projects_tab"
        const val KEY_QUERY = "projects_query"
        const val KEY_SORT = "projects_sort"
    }
}
