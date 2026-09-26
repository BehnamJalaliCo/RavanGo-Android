package com.ravango.feature.scripts.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.DefaultDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Clock
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.ProFeature
import com.ravango.core.model.Script
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.ScriptEditorRoute
import com.ravango.core.ui.detectDirection
import com.ravango.engine.ai.api.AiAvailability
import com.ravango.engine.ai.api.AiStreamEvent
import com.ravango.engine.ai.api.AiTextService
import com.ravango.engine.ai.api.TextRequest
import com.ravango.engine.ai.api.TextTask
import com.ravango.engine.ai.api.Tone
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import com.ravango.engine.teleprompter.timing.PrompterTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SaveStatus { IDLE, PENDING, SAVING, SAVED, FAILED }

data class EditorStats(val words: Int = 0, val pauses: Int = 0, val durationMs: Long = 0, val wordsPerMinute: Int = 140)

data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val scriptId: String? = null,
    val title: String = "",
    val body: TextFieldValue = TextFieldValue(""),
    val direction: ContentDirection = ContentDirection.AUTO,
    val preview: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val floatingIsPro: Boolean = false,
)

enum class AiBlock { NOT_CONFIGURED, CONSENT }

data class AiUiState(
    val availability: AiAvailability? = null,
    val block: AiBlock? = null,
    val task: TextTask? = null,
    val tone: Tone = Tone.FRIENDLY,
    val running: Boolean = false,
    val output: String = "",
    val items: List<String> = emptyList(),
    val completed: Boolean = false,
    val error: ErrorKind? = null,
    val errorDetail: String? = null,
    /** Range of the body the result applies to (the selection when one existed, otherwise the whole text). */
    val target: TextRange = TextRange.Zero,
    val onSelection: Boolean = false,
)

sealed interface EditorEvent {
    data object OpenPaywall : EditorEvent
    data class Navigate(val action: EditorNavAction, val scriptId: String) : EditorEvent
    data object EmptyScript : EditorEvent
}

enum class EditorNavAction { PROMPTER, RECORD, PROMPTER_SETTINGS, FLOATING, AI_STUDIO }

@OptIn(FlowPreview::class)
@HiltViewModel
class ScriptEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scripts: ScriptRepository,
    private val prefs: PreferencesDataSource,
    private val ai: AiTextService,
    private val entitlements: EntitlementProvider,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
    @DefaultDispatcher private val compute: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ScriptEditorRoute>()

    private val _state = MutableStateFlow(EditorUiState(scriptId = route.scriptId))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _ai = MutableStateFlow(AiUiState())
    val aiState: StateFlow<AiUiState> = _ai.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val undo = UndoStack()
    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var aiJob: Job? = null
    private var dirty = false

    val stats: StateFlow<EditorStats> = combine(
        _state.map { it.body.text }.distinctUntilChanged().debounce(150),
        _state.map { it.scriptId }.distinctUntilChanged(),
        prefs.prompterDefaults.map { it.wordsPerMinute },
    ) { text, id, defaultWpm ->
        val override = id?.let { scripts.getScript(it)?.prompterSettings?.wordsPerMinute }
        val parsed = ScriptMarkup.parse(text)
        val wpm = override ?: defaultWpm
        EditorStats(parsed.totalWords, parsed.totalPauses, PrompterTiming.durationMs(parsed.totalWords, parsed.totalPauses, wpm), wpm)
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorStats())

    init {
        viewModelScope.launch {
            val id = route.scriptId
            if (id != null) {
                val script = scripts.getScript(id)
                if (script == null) {
                    _state.update { it.copy(loading = false, notFound = true) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        title = script.title,
                        body = TextFieldValue(script.body, TextRange(script.body.length)),
                        direction = script.direction,
                    )
                }
                scripts.markOpened(id)
            } else {
                val text = route.initialText.orEmpty()
                _state.update { it.copy(loading = false, body = TextFieldValue(text, TextRange(text.length))) }
                if (text.isNotBlank()) {
                    dirty = true
                    scheduleSave()
                }
            }
        }
        viewModelScope.launch {
            entitlements.entitlements.collect { _state.update { s -> s.copy(floatingIsPro = entitlements.has(ProFeature.FLOATING_PROMPTER)) } }
        }
        viewModelScope.launch {
            combine(ai.availability, prefs.userPreferences.map { it.ai.cloudProcessingConsent }) { availability, consent ->
                availability to when {
                    !availability.configured -> AiBlock.NOT_CONFIGURED
                    availability.consentRequired && !consent -> AiBlock.CONSENT
                    else -> null
                }
            }.collect { (availability, block) -> _ai.update { it.copy(availability = availability, block = block) } }
        }
    }

    // ------------------------------------------------------------ editing

    fun onTitleChange(title: String) {
        if (title == _state.value.title) return
        _state.update { it.copy(title = title.replace('\n', ' ')) }
        markDirty()
    }

    fun onBodyChange(value: TextFieldValue) {
        val before = _state.value.body
        if (value.text != before.text) {
            undo.record(before, value, clock.now())
            _state.update { it.copy(body = value, canUndo = undo.canUndo, canRedo = undo.canRedo) }
            markDirty()
        } else {
            _state.update { it.copy(body = value) }
        }
    }

    /** Applies a toolbar/AI edit as one undoable step. */
    fun applyEdit(transform: (TextFieldValue) -> TextFieldValue) {
        val before = _state.value.body
        val after = transform(before)
        if (after == before) return
        undo.record(before, after, clock.now(), discrete = true)
        _state.update { it.copy(body = after, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        if (after.text != before.text) markDirty()
    }

    fun undo() {
        val previous = undo.undo(_state.value.body) ?: return
        _state.update { it.copy(body = previous, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        markDirty()
    }

    fun redo() {
        val next = undo.redo(_state.value.body) ?: return
        _state.update { it.copy(body = next, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        markDirty()
    }

    fun setDirection(direction: ContentDirection) {
        if (direction == _state.value.direction) return
        _state.update { it.copy(direction = direction) }
        markDirty()
    }

    fun setPreview(preview: Boolean) = _state.update { it.copy(preview = preview) }

    /** Direction for the editor/preview: the manual override, or detected from the content. */
    fun resolvedDirection(ui: LayoutDirection): LayoutDirection = when (_state.value.direction) {
        ContentDirection.RTL -> LayoutDirection.Rtl
        ContentDirection.LTR -> LayoutDirection.Ltr
        ContentDirection.AUTO -> detectDirection(_state.value.body.text) ?: detectDirection(_state.value.title) ?: ui
    }

    // ------------------------------------------------------------ saving

    private fun markDirty() {
        dirty = true
        _state.update { it.copy(saveStatus = SaveStatus.PENDING) }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    /** Saves immediately (lifecycle pause, navigation). Returns the script id, creating the script if needed. */
    suspend fun saveNow(): String? = saveMutex.withLock {
        val s = _state.value
        if (!dirty) return@withLock s.scriptId
        val body = s.body.text
        if (s.scriptId == null && body.isBlank() && s.title.isBlank()) return@withLock null
        _state.update { it.copy(saveStatus = SaveStatus.SAVING) }
        try {
            val id = withContext(NonCancellable) {
                val existing = s.scriptId?.let { scripts.getScript(it) }
                if (existing == null) {
                    val title = s.title.ifBlank { deriveTitle(body) }
                    val created = scripts.create(title, body, route.folderId)
                    val saved = if (s.direction != ContentDirection.AUTO) scripts.save(created.copy(direction = s.direction)) else created
                    if (s.title.isBlank() && title.isNotBlank()) _state.update { it.copy(title = title) }
                    saved.id
                } else {
                    // Only the editor-owned fields change; favourites, folder, prompter settings stay as stored.
                    scripts.save(existing.copy(title = s.title, body = body, direction = s.direction)).id
                }
            }
            // Only clear the dirty flag if nothing changed while saving.
            val now = _state.value
            if (now.body.text == body && now.title == s.title && now.direction == s.direction) dirty = false
            _state.update { it.copy(scriptId = id, saveStatus = if (dirty) SaveStatus.PENDING else SaveStatus.SAVED) }
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.e(TAG, "saving script failed", e)
            _state.update { it.copy(saveStatus = SaveStatus.FAILED) }
            s.scriptId
        }
    }

    fun flush() {
        if (!dirty) return
        saveJob?.cancel()
        appScope.launch { saveNow() }
    }

    private fun deriveTitle(body: String): String =
        ScriptMarkup.toPlainText(body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).trim().take(60)

    fun navigate(action: EditorNavAction) {
        viewModelScope.launch {
            saveJob?.cancel()
            val id = saveNow()
            if (id == null) _events.send(EditorEvent.EmptyScript) else _events.send(EditorEvent.Navigate(action, id))
        }
    }

    // ------------------------------------------------------------ AI assist

    fun setAiTone(tone: Tone) = _ai.update { it.copy(tone = tone) }

    fun runAi(task: TextTask) {
        val ai = _ai.value
        if (ai.block != null) return
        val body = _state.value.body
        val selection = body.selection
        val onSelection = !selection.collapsed
        val target = if (onSelection) TextRange(selection.min, selection.max) else TextRange(0, body.text.length)
        val input = body.text.substring(target.min, target.max)
        if (input.isBlank() && task !in GENERATIVE_TASKS) {
            viewModelScope.launch { _events.send(EditorEvent.EmptyScript) }
            return
        }
        val inputLanguage = if ((detectDirection(input) ?: detectDirection(_state.value.title)) == LayoutDirection.Ltr) "en" else "fa"
        val outputLanguage = if (task == TextTask.TRANSLATE) (if (inputLanguage == "fa") "en" else "fa") else inputLanguage
        val request = TextRequest(
            task = task,
            // Hooks/CTAs work best from the whole script even when a line is selected.
            input = if (task in GENERATIVE_TASKS && input.isBlank()) body.text else input,
            outputLanguage = outputLanguage,
            tone = if (task == TextTask.CHANGE_TONE) ai.tone else null,
            variants = if (task in LIST_TASKS) 5 else 1,
            extraInstructions = MARKUP_INSTRUCTIONS,
        )
        aiJob?.cancel()
        _ai.update {
            it.copy(task = task, running = true, output = "", items = emptyList(), completed = false, error = null, errorDetail = null, target = target, onSelection = onSelection)
        }
        aiJob = viewModelScope.launch {
            try {
                this@ScriptEditorViewModel.ai.run(request).collect { event ->
                    when (event) {
                        is AiStreamEvent.Delta -> _ai.update { it.copy(output = it.output + event.text) }
                        is AiStreamEvent.Completed -> _ai.update {
                            val text = event.text.ifBlank { it.output }
                            it.copy(
                                running = false,
                                completed = true,
                                output = text,
                                items = if (task in LIST_TASKS) this@ScriptEditorViewModel.ai.parseList(text) else emptyList(),
                            )
                        }
                        is AiStreamEvent.Failed -> {
                            _ai.update { it.copy(running = false, error = event.kind, errorDetail = event.message) }
                            if (event.kind == ErrorKind.QUOTA) _events.send(EditorEvent.OpenPaywall)
                        }
                    }
                }
                _ai.update { if (it.running) it.copy(running = false, completed = it.output.isNotBlank()) else it }
            } catch (e: CancellationException) {
                _ai.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "AI request failed", e)
                _ai.update { it.copy(running = false, error = ErrorKind.UNKNOWN, errorDetail = e.message) }
            }
        }
    }

    fun cancelAi() {
        aiJob?.cancel()
        aiJob = null
    }

    fun clearAiResult() {
        cancelAi()
        _ai.update { it.copy(task = null, output = "", items = emptyList(), completed = false, error = null, errorDetail = null) }
    }

    /** Replaces the AI target range (selection or whole text) with [text]. */
    fun replaceWithAi(text: String) {
        val target = _ai.value.target
        applyEdit { MarkupEdits.replace(it, clampRange(target, it.text.length), text.trim()) }
        clearAiResult()
    }

    fun insertAiBelow(text: String) {
        val target = _ai.value.target
        applyEdit { MarkupEdits.insertBelow(it, clampRange(target, it.text.length), text) }
        clearAiResult()
    }

    private fun clampRange(range: TextRange, length: Int) = TextRange(range.min.coerceIn(0, length), range.max.coerceIn(0, length))

    override fun onCleared() {
        aiJob?.cancel()
        if (dirty) appScope.launch { saveNow() }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ScriptEditor"
        const val AUTOSAVE_DEBOUNCE_MS = 800L
        val LIST_TASKS = setOf(TextTask.HOOKS, TextTask.CTA, TextTask.TITLES, TextTask.IDEAS)
        private val GENERATIVE_TASKS = setOf(TextTask.HOOKS, TextTask.CTA)
        private const val MARKUP_INSTRUCTIONS =
            "The text is a teleprompter script. Preserve RavanGo markup exactly where present: '## ' section headings, " +
                "==highlight==, **emphasis**, [pause]/[مکث] cues and [[director notes]]. Return only the resulting text."
    }
}
