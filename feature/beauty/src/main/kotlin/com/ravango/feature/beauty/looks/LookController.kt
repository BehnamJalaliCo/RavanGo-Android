package com.ravango.feature.beauty.looks

import androidx.compose.runtime.Immutable
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.BeautyState
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.ProFeature
import com.ravango.core.model.newId
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.beauty.BeautyEngine
import com.ravango.engine.beauty.EyeColorSetting
import com.ravango.engine.beauty.effects.CameraEffects
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.makeup.MakeupStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of the looks feature, shared by the camera carousel and the Beauty panel. */
@Immutable
data class LooksState(
    /** Every look: the user's saved looks first, then the built-in catalogue. */
    val looks: List<LookDef> = LookCatalog.looks,
    val activeId: String? = null,
    val intensity: Int = 100,
    /** The user changed individual sliders after applying the look (the intensity slider would undo that). */
    val customised: Boolean = false,
    val favourites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val loaded: Boolean = false,
) {
    val active: LookDef? get() = activeId?.let { id -> looks.firstOrNull { it.id == id } }
    fun find(id: String): LookDef? = looks.firstOrNull { it.id == id }
    fun isFavourite(key: String): Boolean = key in favourites
    val custom: List<LookDef> get() = looks.filter { it.isCustom }
}

sealed interface LookResult {
    data object Applied : LookResult
    data class Locked(val feature: ProFeature) : LookResult
    data object NotFound : LookResult
}

/**
 * Applies complete looks to the beauty engine (beauty values + makeup layers + [MakeupStyle] + coloured lenses) and
 * the camera effects (the look's live filter), instantly. Keeps the user's own settings as the "base" so the
 * intensity slider fades between base (0) and the look (100) and "Clear" restores exactly what was there before.
 * Persists favourites, recents, the active look and saved looks ([LookStore]).
 *
 * Looks and fun lenses combine: a look lives in the beauty layer and a lens in the effects layer, so applying one
 * never removes the other (see the camera's lens tray for the carousel rules).
 */
@Singleton
class LookController @Inject constructor(
    private val engine: BeautyEngine,
    private val effects: CameraEffects,
    private val entitlementProvider: EntitlementProvider,
    private val store: LookStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LooksState())
    val state: StateFlow<LooksState> = _state.asStateFlow()

    // Base (the user's own settings before the look) and the last values this controller applied.
    @Volatile private var base: BeautyState? = null
    @Volatile private var baseEye: EyeColorSetting = EyeColorSetting()
    @Volatile private var filterBefore: Pair<LiveFilter, Int>? = null
    @Volatile private var lastApplied: ResolvedLook? = null
    private var persistJob: Job? = null

    init {
        scope.launch { restore() }
        scope.launch { watchEngine() }
        scope.launch { watchDowngrade() }
    }

    fun find(id: String): LookDef? = _state.value.find(id)

    /** Applies [id] at the current (or [intensity]) strength. Locked Pro looks are not applied. */
    fun apply(id: String, intensity: Int? = null): LookResult {
        val look = find(id) ?: return LookResult.NotFound
        if (!LookGating.allowed(look, entitlementProvider.entitlements.value)) return LookResult.Locked(LookGating.FEATURE)
        val current = _state.value
        if (current.activeId == null || base == null) {
            // Remember the user's own settings (the base the slider fades from and "Clear" restores).
            base = engine.state.value
            baseEye = engine.eyeColor.value
        }
        val level = (intensity ?: if (current.activeId == null) DEFAULT_INTENSITY else current.intensity).coerceIn(0, 100)
        push(look, level)
        _state.update { it.copy(activeId = look.id, intensity = level, customised = false) }
        persist()
        return LookResult.Applied
    }

    /** Scales the active look (0 = the user's own settings, 100 = the look as designed). */
    fun setIntensity(intensity: Int) {
        val look = _state.value.active ?: return
        val level = intensity.coerceIn(0, 100)
        if (level == _state.value.intensity && !_state.value.customised) return
        push(look, level)
        _state.update { it.copy(intensity = level, customised = false) }
        persistSoon()
    }

    /** Removes the active look and restores the settings from before it (the "Clear" chip). */
    fun clear() {
        if (_state.value.activeId == null) return
        val restore = base
        if (restore != null && !_state.value.customised) engine.setState(restore) else engine.setState(stripMakeup(engine.state.value))
        engine.setEyeColor(baseEye)
        engine.setMakeupStyle(MakeupStyle.Default)
        restoreFilter()
        forget()
    }

    fun toggleFavourite(key: String) {
        val favs = _state.value.favourites
        val next = if (key in favs) favs - key else listOf(key) + favs
        _state.update { it.copy(favourites = next) }
        scope.launch { safely { store.update { it.copy(favourites = next) } } }
    }

    /** Records a carousel item as recently used (newest first, de-duplicated). */
    fun recordRecent(key: String) {
        val next = (listOf(key) + _state.value.recents.filter { it != key }).take(LookPrefs.MAX_RECENTS)
        if (next == _state.value.recents) return
        _state.update { it.copy(recents = next) }
        scope.launch { safely { store.update { it.copy(recents = next) } } }
    }

    /** Saves the current settings (look + the user's fine-tuning) as a new look named [name]. */
    suspend fun saveCurrent(name: String): LookDef? {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return null
        val basedOn = _state.value.active
        val fx = effects.effects.value
        val recipe = LookResolver.capture(engine.state.value, engine.makeupStyle.value, engine.eyeColor.value, fx.filter, fx.filterIntensity)
        val custom = CustomLook(
            id = "my_" + newId(),
            name = clean,
            basedOn = basedOn?.id,
            recipe = recipe,
            avatar = basedOn?.avatar ?: LookCatalog.looks.first().avatar,
            createdAt = System.currentTimeMillis(),
        )
        val def = custom.toDef()
        return try {
            store.update { p -> p.copy(custom = (listOf(custom) + p.custom).take(LookPrefs.MAX_CUSTOM)) }
            if (base == null) {
                base = stripMakeup(engine.state.value)
                baseEye = engine.eyeColor.value.copy(intensity = 0)
            }
            _state.update { s -> s.copy(looks = listOf(def) + s.looks, activeId = def.id, intensity = 100, customised = false) }
            lastApplied = LookResolver.resolve(recipe, 100, base ?: engine.state.value, baseEye)
                .copy(state = engine.state.value, eyeColor = engine.eyeColor.value)
            persist()
            def
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not save look", e)
            null
        }
    }

    fun deleteCustom(id: String) {
        if (_state.value.activeId == id) clear()
        _state.update { s -> s.copy(looks = s.looks.filterNot { it.id == id }, favourites = s.favourites - LookDef.keyOf(id), recents = s.recents - LookDef.keyOf(id)) }
        scope.launch {
            safely {
                store.update { p ->
                    p.copy(custom = p.custom.filterNot { it.id == id }, favourites = p.favourites - LookDef.keyOf(id), recents = p.recents - LookDef.keyOf(id))
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun push(look: LookDef, level: Int) {
        val resolved = LookResolver.resolve(look.recipe, level, base ?: engine.state.value, baseEye)
        lastApplied = resolved
        engine.setMakeupStyle(resolved.style)
        engine.setEyeColor(resolved.eyeColor)
        engine.setState(resolved.state)
        applyFilter(resolved)
    }

    private fun applyFilter(resolved: ResolvedLook) {
        val filter = resolved.filter
        if (filter == null || !LookGating.filterAllowed(filter, entitlementProvider.entitlements.value)) {
            restoreFilter()
            return
        }
        if (filterBefore == null) {
            val fx = effects.effects.value
            filterBefore = fx.filter to fx.filterIntensity
        }
        if (effects.effects.value.filter != filter) effects.setFilter(filter)
        effects.setFilterIntensity(resolved.filterIntensity)
    }

    private fun restoreFilter() {
        val before = filterBefore ?: return
        filterBefore = null
        effects.setFilter(before.first)
        effects.setFilterIntensity(before.second)
    }

    private fun forget() {
        base = null
        lastApplied = null
        _state.update { it.copy(activeId = null, intensity = DEFAULT_INTENSITY, customised = false) }
        persist()
    }

    /**
     * Detects fine-tuning (customised) and a reset from elsewhere (the look is dropped when its makeup is removed).
     * Debounced so the separate engine setters of one [push] never look like a user change.
     */
    @OptIn(FlowPreview::class)
    private suspend fun watchEngine() {
        var hadMakeup = false
        combine(engine.state, engine.eyeColor) { s, e -> s to e }.debounce(WATCH_DEBOUNCE_MS).collect { (s, e) ->
            val makeup = hasMakeup(s)
            val wasMakeup = hadMakeup
            hadMakeup = makeup
            val applied = lastApplied ?: return@collect
            if (_state.value.activeId == null) return@collect
            if (s == applied.state && e == applied.eyeColor) {
                if (_state.value.customised) _state.update { it.copy(customised = false) }
                return@collect
            }
            if (!makeup && wasMakeup) {
                // "Reset all" or makeup cleared in the Beauty panel: the look is gone.
                engine.setMakeupStyle(MakeupStyle.Default)
                filterBefore = null
                forget()
            } else if (makeup && !_state.value.customised) {
                _state.update { it.copy(customised = true) }
            }
        }
    }

    /** A downgrade (or an expired trial) must not keep a Pro look running. */
    private suspend fun watchDowngrade() {
        var had = entitlementProvider.entitlements.value.has(LookGating.FEATURE)
        entitlementProvider.entitlements.collect { e ->
            val has = e.has(LookGating.FEATURE)
            if (had && !has && _state.value.active?.pro == true) clear()
            had = has
        }
    }

    private suspend fun restore() {
        val prefs = try {
            store.prefs.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore looks", e)
            LookPrefs()
        }
        val customDefs = prefs.custom.map { it.toDef() }
        val looks = customDefs + LookCatalog.looks
        val touched = _state.value.activeId != null
        _state.update { s ->
            s.copy(
                looks = looks,
                favourites = prefs.favourites,
                recents = prefs.recents,
                loaded = true,
                activeId = if (touched) s.activeId else prefs.activeId?.takeIf { id -> looks.any { it.id == id } },
                intensity = if (touched) s.intensity else prefs.intensity,
            )
        }
        if (touched) return
        val look = _state.value.active ?: return
        // The engine restores its own beauty state and eye colour; the style is ours to restore.
        base = prefs.base
        baseEye = EyeColorSetting(prefs.baseEyeIntensity, prefs.baseEyeColor ?: EyeColorSetting.DEFAULT_COLOR)
        val resolved = LookResolver.resolve(look.recipe, prefs.intensity, base ?: BeautyState(), baseEye)
        lastApplied = resolved
        engine.setMakeupStyle(resolved.style)
        _state.update { it.copy(customised = engine.state.value != resolved.state) }
    }

    private fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch { writePrefs() }
    }

    /** Debounced persist for slider drags. */
    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            writePrefs()
        }
    }

    private suspend fun writePrefs() = safely {
        val s = _state.value
        val b = base
        val eye = baseEye
        store.update { p ->
            p.copy(
                activeId = s.activeId,
                intensity = s.intensity,
                base = if (s.activeId != null) b else null,
                baseEyeColor = eye.color,
                baseEyeIntensity = eye.intensity,
            )
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not save looks", e)
        }
    }

    companion object {
        private const val TAG = "Looks"
        const val DEFAULT_INTENSITY = 100
        private const val PERSIST_DEBOUNCE_MS = 500L
        private const val WATCH_DEBOUNCE_MS = 250L

        fun hasMakeup(state: BeautyState): Boolean = MakeupFeature.entries.any { state.layer(it).intensity > 0 }

        fun stripMakeup(state: BeautyState): BeautyState =
            state.copy(makeup = MakeupFeature.entries.associateWith { com.ravango.core.model.MakeupLayer(0, state.layer(it).color) })
    }
}
