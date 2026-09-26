package com.ravango.feature.teleprompter.data

import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.Script
import com.ravango.core.model.TeleprompterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Effective prompter settings for a script: its own override, or the user's defaults. */
fun effectiveSettings(script: Script?, defaults: TeleprompterSettings): TeleprompterSettings = script?.prompterSettings ?: defaults

/**
 * Settings edited live from the prompter (pinch-to-zoom, speed, mirror…). Changes apply instantly in memory and
 * are persisted after a short debounce to wherever they belong: the script's override when it has one, otherwise
 * the user's defaults. Once the store reflects the written value the in-memory override is dropped, so edits
 * made elsewhere (the settings screen) flow back in.
 */
class LiveSettingsEditor(
    private val scope: CoroutineScope,
    private val scripts: ScriptRepository,
    private val prefs: PreferencesDataSource,
) {
    private val _local = MutableStateFlow<TeleprompterSettings?>(null)
    val local: StateFlow<TeleprompterSettings?> = _local.asStateFlow()
    private var persistJob: Job? = null

    /** Combines persisted settings with the in-memory edit. */
    fun effective(script: Flow<Script?>): Flow<TeleprompterSettings> =
        combine(script, prefs.prompterDefaults, _local) { s, defaults, local -> local ?: effectiveSettings(s, defaults) }

    fun update(current: TeleprompterSettings, script: Script?, transform: (TeleprompterSettings) -> TeleprompterSettings) {
        val next = transform(current)
        if (next == current) return
        _local.value = next
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val target = _local.value ?: return@launch
            if (script?.prompterSettings != null) {
                scripts.savePrompterSettings(script.id, target)
                withTimeoutOrNull(2_000) { scripts.observeScript(script.id).first { it?.prompterSettings == target } }
            } else {
                prefs.updatePrompterDefaults { target }
                withTimeoutOrNull(2_000) { prefs.prompterDefaults.first { it == target } }
            }
            if (_local.value == target) _local.value = null
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 350L
    }
}
