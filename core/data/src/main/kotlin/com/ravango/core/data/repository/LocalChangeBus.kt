package com.ravango.core.data.repository

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Collections that participate in cloud sync. Names match the backend tables. */
enum class SyncCollection(val remoteName: String) {
    SCRIPTS("scripts"),
    FOLDERS("script_folders"),
    PROJECTS("projects"),
    DRAFTS("drafts"),
    MEDIA("media_assets"),
    BEAUTY_PRESETS("beauty_presets"),
    PROMPTER_PRESETS("prompter_presets"),
    PREFERENCES("user_settings"),
}

/**
 * Emits whenever local data changes. The cloud module listens and schedules a debounced sync, so the data layer
 * never depends on networking.
 */
@Singleton
class LocalChangeBus @Inject constructor() {
    private val _changes = MutableSharedFlow<SyncCollection>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<SyncCollection> = _changes.asSharedFlow()

    fun notifyChanged(collection: SyncCollection) {
        _changes.tryEmit(collection)
    }
}
