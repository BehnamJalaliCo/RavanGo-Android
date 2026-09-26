package com.ravango.feature.projects.templates

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.data.repository.TemplateRepository
import com.ravango.core.model.ProFeature
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.feature.projects.common.TemplateStartMode
import com.ravango.feature.projects.common.TemplateStarter
import com.ravango.feature.projects.common.TemplateTexts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TemplatesUiState(
    val templates: List<ProjectTemplate> = emptyList(),
    val selected: ProjectTemplate? = null,
    val busyMode: TemplateStartMode? = null,
    val captionsIncluded: Boolean = false,
)

sealed interface TemplatesEvent {
    data class Navigate(val route: Any) : TemplatesEvent
    data class Error(val kind: ErrorKind) : TemplatesEvent
}

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    templateRepository: TemplateRepository,
    entitlements: EntitlementProvider,
    private val starter: TemplateStarter,
) : ViewModel() {

    private val templates = templateRepository.templates()
    private val selectedId = savedStateHandle.getStateFlow<String?>(KEY_SELECTED, null)
    private val busy = MutableStateFlow<TemplateStartMode?>(null)
    private val events = Channel<TemplatesEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<TemplatesUiState> = combine(selectedId, busy, entitlements.entitlements) { id, mode, ent ->
        TemplatesUiState(
            templates = templates,
            selected = templates.firstOrNull { it.id == id },
            busyMode = mode,
            captionsIncluded = ent.has(ProFeature.AUTO_CAPTIONS),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplatesUiState(templates = templates))

    fun select(template: ProjectTemplate?) {
        if (busy.value != null) return
        savedStateHandle[KEY_SELECTED] = template?.id
    }

    fun start(mode: TemplateStartMode, texts: TemplateTexts) {
        val template = uiState.value.selected ?: return
        if (busy.value != null) return
        busy.value = mode
        viewModelScope.launch {
            val result = starter.start(template, mode, texts)
            busy.value = null
            when (result) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_SELECTED] = null
                    events.send(TemplatesEvent.Navigate(result.value))
                }
                is Outcome.Failure -> events.send(TemplatesEvent.Error(result.kind))
            }
        }
    }

    private companion object {
        const val KEY_SELECTED = "templates_selected"
    }
}
