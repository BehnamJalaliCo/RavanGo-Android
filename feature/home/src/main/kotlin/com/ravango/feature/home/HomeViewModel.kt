package com.ravango.feature.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.data.repository.TemplateRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Clock
import com.ravango.core.model.MediaKind
import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.model.Script
import com.ravango.core.model.service.AuthSessionProvider
import com.ravango.core.model.service.AuthState
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.model.service.SyncController
import com.ravango.core.model.service.SyncPhase
import com.ravango.feature.home.common.MediaImporter
import com.ravango.feature.home.common.TemplateStartMode
import com.ravango.feature.home.common.TemplateStarter
import com.ravango.feature.home.common.TemplateTexts
import com.ravango.feature.home.common.ThumbnailSource
import com.ravango.feature.home.common.firstVisualAssets
import com.ravango.feature.home.common.thumbnailFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeProject(
    val project: Project,
    val thumbnail: ThumbnailSource?,
    val audioOnly: Boolean,
)

data class ContinueItem(val item: HomeProject, val editedAt: Long)

data class HomeUiState(
    val loading: Boolean = true,
    val signedIn: Boolean = false,
    val userName: String? = null,
    val avatarUrl: String? = null,
    val plan: Plan = Plan.FREE,
    val captionsIncluded: Boolean = false,
    val syncPhase: SyncPhase = SyncPhase.DISABLED,
    val recentProjects: List<HomeProject> = emptyList(),
    val recentScripts: List<Script> = emptyList(),
    val scriptCount: Int = 0,
    val templates: List<ProjectTemplate> = emptyList(),
    val continueItem: ContinueItem? = null,
    val wordsPerMinute: Int = 140,
    val importing: Boolean = false,
    val selectedTemplate: ProjectTemplate? = null,
    val templateBusy: TemplateStartMode? = null,
) {
    val isPaid: Boolean get() = plan != Plan.FREE
    val isNewUser: Boolean get() = !loading && recentProjects.isEmpty() && scriptCount == 0
}

sealed interface HomeEvent {
    data class Navigate(val route: Any) : HomeEvent
    data class Error(val kind: ErrorKind) : HomeEvent
}

private data class Account(val state: AuthState, val plan: Plan, val captions: Boolean, val sync: SyncPhase)
private data class Content(
    val recent: List<HomeProject>,
    val continueItem: ContinueItem?,
    val scripts: List<Script>,
    val scriptCount: Int,
)
private data class Personal(val wpm: Int, val focus: List<String>)
private data class Transient(val importing: Boolean, val selectedId: String?, val busy: TemplateStartMode?)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val projects: ProjectRepository,
    scripts: ScriptRepository,
    templateRepository: TemplateRepository,
    preferences: PreferencesDataSource,
    entitlements: EntitlementProvider,
    auth: AuthSessionProvider,
    sync: SyncController,
    private val importer: MediaImporter,
    private val templateStarter: TemplateStarter,
    private val clock: Clock,
) : ViewModel() {

    private val builtInTemplates = templateRepository.templates()
    private val importing = MutableStateFlow(false)
    private val templateBusy = MutableStateFlow<TemplateStartMode?>(null)
    private val selectedTemplateId = savedStateHandle.getStateFlow<String?>(KEY_TEMPLATE, null)

    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val account = combine(auth.authState, entitlements.entitlements, sync.state) { a, e, s ->
        Account(a, e.plan, e.has(ProFeature.AUTO_CAPTIONS), s.phase)
    }

    private val content = combine(
        projects.observeProjects(),
        projects.observeAllAssets(),
        projects.observeDrafts(),
        scripts.observeRecent(3),
        scripts.observeCount(),
    ) { all, assets, drafts, recentScripts, scriptCount ->
        val live = all.filter { it.deletedAt == null }
        val liveAssets = assets.filter { it.deletedAt == null && it.projectId != null }
        val firstVisual = firstVisualAssets(liveAssets)
        val audioOnlyIds = liveAssets.groupBy { it.projectId!! }.filterValues { l -> l.all { it.kind == MediaKind.AUDIO } }.keys
        fun toItem(p: Project) = HomeProject(p, thumbnailFor(p, firstVisual[p.id]), p.id in audioOnlyIds)
        val byId = live.associateBy { it.id }
        val continueItem = continueCandidate(drafts, byId, clock.now())?.let { (p, d) -> ContinueItem(toItem(p), d.updatedAt) }
        Content(
            recent = live.sortedByDescending { it.updatedAt }.take(RECENT_LIMIT).map(::toItem),
            continueItem = continueItem,
            scripts = recentScripts,
            scriptCount = scriptCount,
        )
    }

    private val personal = combine(
        preferences.prompterDefaults.map { it.wordsPerMinute }.distinctUntilChanged(),
        preferences.observeString(FOCUS_KEY).map(::parseFocus),
    ) { wpm, focus -> Personal(wpm, focus) }

    private val transient = combine(importing, selectedTemplateId, templateBusy) { i, id, b -> Transient(i, id, b) }

    val uiState: StateFlow<HomeUiState> = combine(account, content, personal, transient) { a, c, p, t ->
        val user = (a.state as? AuthState.SignedIn)?.user
        HomeUiState(
            loading = false,
            signedIn = user != null,
            userName = greetingNameFor(user),
            avatarUrl = user?.avatarUrl,
            plan = a.plan,
            captionsIncluded = a.captions,
            syncPhase = if (user == null) SyncPhase.DISABLED else a.sync,
            recentProjects = c.recent,
            recentScripts = c.scripts,
            scriptCount = c.scriptCount,
            templates = orderTemplatesByFocus(builtInTemplates, p.focus),
            continueItem = c.continueItem,
            wordsPerMinute = p.wpm,
            importing = t.importing,
            selectedTemplate = builtInTemplates.firstOrNull { it.id == t.selectedId },
            templateBusy = t.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(templates = builtInTemplates))

    fun importMedia(uris: List<Uri>, fallbackTitle: String) {
        if (uris.isEmpty() || importing.value) return
        importing.value = true
        viewModelScope.launch {
            val result = importer.importAsProject(uris, fallbackTitle)
            importing.value = false
            when (result) {
                is Outcome.Success -> events.send(HomeEvent.Navigate(com.ravango.core.navigation.EditorRoute(result.value.id)))
                is Outcome.Failure -> if (result.kind != ErrorKind.CANCELLED) events.send(HomeEvent.Error(result.kind))
            }
        }
    }

    fun selectTemplate(template: ProjectTemplate?) {
        if (templateBusy.value != null) return
        savedStateHandle[KEY_TEMPLATE] = template?.id
    }

    fun startTemplate(mode: TemplateStartMode, texts: TemplateTexts) {
        val template = uiState.value.selectedTemplate ?: return
        if (templateBusy.value != null) return
        templateBusy.value = mode
        viewModelScope.launch {
            val result = templateStarter.start(template, mode, texts)
            templateBusy.value = null
            when (result) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_TEMPLATE] = null
                    events.send(HomeEvent.Navigate(result.value))
                }
                is Outcome.Failure -> events.send(HomeEvent.Error(result.kind))
            }
        }
    }

    private companion object {
        const val KEY_TEMPLATE = "home_selected_template"
        const val RECENT_LIMIT = 10

        /** Written by feature:onboarding (string contract). */
        const val FOCUS_KEY = "onboarding_focus"
    }
}
