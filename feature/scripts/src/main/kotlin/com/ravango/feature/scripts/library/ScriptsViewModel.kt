package com.ravango.feature.scripts.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.DefaultDispatcher
import com.ravango.core.data.repository.ScriptFilter
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.core.model.ScriptSortOrder
import com.ravango.core.navigation.ScriptsRoute
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import com.ravango.engine.teleprompter.timing.PrompterTiming
import com.ravango.feature.scripts.importing.ImportError
import com.ravango.feature.scripts.importing.ImportResult
import com.ravango.feature.scripts.importing.ScriptImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Library filter chip. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Favorites : LibraryFilter
    data class Folder(val id: String) : LibraryFilter
}

data class ScriptItem(
    val script: Script,
    val words: Int,
    val durationMs: Long,
    /** Plain-text preview (markup removed), around the first search match when searching. */
    val preview: String,
    val titleMatches: List<IntRange>,
    val previewMatches: List<IntRange>,
)

data class ScriptsUiState(
    val loading: Boolean = true,
    val items: List<ScriptItem> = emptyList(),
    val folders: List<ScriptFolder> = emptyList(),
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.All,
    val sort: ScriptSortOrder = ScriptSortOrder.UPDATED_DESC,
    val wordsPerMinute: Int = 140,
    val pickMode: Boolean = false,
    val importing: Boolean = false,
)

sealed interface ScriptsEvent {
    data class OpenEditor(val scriptId: String) : ScriptsEvent
    data class Deleted(val script: Script) : ScriptsEvent
    data class Duplicated(val script: Script) : ScriptsEvent
    data class ImportFailed(val error: ImportError) : ScriptsEvent
    data object ClipboardEmpty : ScriptsEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ScriptsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scripts: ScriptRepository,
    private val importer: ScriptImporter,
    prefs: PreferencesDataSource,
    @DefaultDispatcher private val compute: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ScriptsRoute>()

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<LibraryFilter>(route.folderId?.let { LibraryFilter.Folder(it) } ?: LibraryFilter.All)
    private val sort = MutableStateFlow(ScriptSortOrder.UPDATED_DESC)
    private val importing = MutableStateFlow(false)

    private val _events = Channel<ScriptsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Parsed stats cache keyed by (id, updatedAt) so re-sorting or filtering does not re-parse bodies. */
    private val statsCache = object : LinkedHashMap<String, Pair<Int, Int>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?) = size > 500
    }

    private val wpm = prefs.prompterDefaults.map { it.wordsPerMinute }.distinctUntilChanged()

    private val debouncedQuery = query.debounce { if (it.isEmpty()) 0L else 250L }.distinctUntilChanged()

    private val items = combine(filter, sort) { f, s -> f to s }
        .flatMapLatest { (f, s) ->
            scripts.observeScripts(
                ScriptFilter(
                    folderId = (f as? LibraryFilter.Folder)?.id,
                    favoritesOnly = f == LibraryFilter.Favorites,
                    sort = s,
                ),
            )
        }
        .combine(debouncedQuery) { list, q -> list to q }
        .combine(wpm) { (list, q), speed -> buildItems(list, q, speed) }
        .flowOn(compute)

    val uiState: StateFlow<ScriptsUiState> = combine(
        items.map<List<ScriptItem>, List<ScriptItem>?> { it }.onStart { emit(null) },
        scripts.observeFolders(),
        combine(query, filter, sort) { q, f, s -> Triple(q, f, s) },
        wpm,
        importing,
    ) { list, folders, (q, f, s), speed, isImporting ->
        ScriptsUiState(
            loading = list == null,
            items = list.orEmpty(),
            folders = folders.filter { it.deletedAt == null }.sortedWith(compareBy<ScriptFolder> { it.sortIndex }.thenBy { it.name }),
            query = q,
            filter = f,
            sort = s,
            wordsPerMinute = speed,
            pickMode = route.pickForPrompter,
            importing = isImporting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScriptsUiState(pickMode = route.pickForPrompter))

    private fun buildItems(list: List<Script>, rawQuery: String, speed: Int): List<ScriptItem> {
        val terms = SearchText.terms(rawQuery)
        return list.mapNotNull { script ->
            val plain = ScriptMarkup.toPlainText(script.body)
            if (terms.isNotEmpty() && !SearchText.matchesAll(terms, script.title, plain)) return@mapNotNull null
            val key = script.id + ":" + script.updatedAt
            val (words, pauses) = synchronized(statsCache) {
                statsCache.getOrPut(key) {
                    val parsed = ScriptMarkup.parse(script.body)
                    parsed.totalWords to parsed.totalPauses
                }
            }
            val speedForScript = script.prompterSettings?.wordsPerMinute ?: speed
            val preview = SearchText.snippet(plain, terms)
            ScriptItem(
                script = script,
                words = words,
                durationMs = PrompterTiming.durationMs(words, pauses, speedForScript),
                preview = preview,
                titleMatches = SearchText.matchRanges(script.title, terms),
                previewMatches = SearchText.matchRanges(preview, terms),
            )
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: LibraryFilter) { filter.value = value }
    fun setSort(value: ScriptSortOrder) { sort.value = value }

    fun toggleFavorite(script: Script) {
        viewModelScope.launch { scripts.setFavorite(script.id, !script.isFavorite) }
    }

    fun delete(script: Script) {
        viewModelScope.launch {
            scripts.delete(script.id)
            _events.send(ScriptsEvent.Deleted(script))
        }
    }

    /** Undo for delete: the soft-deleted row is written back without its tombstone. */
    fun restore(script: Script) {
        viewModelScope.launch { scripts.save(script.copy(deletedAt = null)) }
    }

    fun duplicate(script: Script, copySuffix: String) {
        viewModelScope.launch {
            scripts.duplicate(script.id, copySuffix)?.let { _events.send(ScriptsEvent.Duplicated(it)) }
        }
    }

    fun move(script: Script, folderId: String?) {
        viewModelScope.launch { scripts.move(script.id, folderId) }
    }

    fun createFolder(name: String, color: Long) {
        viewModelScope.launch {
            val folder = scripts.createFolder(name.trim(), color)
            filter.value = LibraryFilter.Folder(folder.id)
        }
    }

    fun renameFolder(folder: ScriptFolder, name: String) {
        viewModelScope.launch { scripts.renameFolder(folder.id, name.trim()) }
    }

    fun deleteFolder(folder: ScriptFolder) {
        viewModelScope.launch {
            scripts.deleteFolder(folder.id)
            if ((filter.value as? LibraryFilter.Folder)?.id == folder.id) filter.value = LibraryFilter.All
        }
    }

    fun import(uri: Uri) {
        if (importing.value) return
        importing.value = true
        viewModelScope.launch {
            try {
                when (val result = importer.import(uri)) {
                    is ImportResult.Success -> {
                        val folderId = (filter.value as? LibraryFilter.Folder)?.id
                        val script = scripts.create(result.title, result.body, folderId)
                        _events.send(ScriptsEvent.OpenEditor(script.id))
                    }
                    is ImportResult.Failure -> _events.send(ScriptsEvent.ImportFailed(result.error))
                }
            } finally {
                importing.value = false
            }
        }
    }

    /** Creates a script from pasted text; the first line becomes the title. */
    fun createFromText(text: String?, fallbackTitle: String) {
        viewModelScope.launch {
            val body = text?.trim().orEmpty()
            if (body.isEmpty()) {
                _events.send(ScriptsEvent.ClipboardEmpty)
                return@launch
            }
            val firstLine = ScriptMarkup.toPlainText(body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).trim()
            val title = firstLine.take(60).ifBlank { fallbackTitle }
            val folderId = (filter.value as? LibraryFilter.Folder)?.id
            val script = scripts.create(title, body, folderId)
            _events.send(ScriptsEvent.OpenEditor(script.id))
        }
    }
}
