package com.ravango.engine.beauty

import android.content.Context
import android.content.SharedPreferences
import com.ravango.core.common.device.DeviceProfiler
import com.ravango.core.common.device.DeviceTier
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.device.ThermalMonitor
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.quality.QualityController
import com.ravango.engine.beauty.quality.ThermalHint
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide beauty engine. Owns the live [BeautyState] (restored from and debounced back to
 * [PreferencesDataSource]) and the eye colour (kept in this module's own preferences file), feeds device tier /
 * thermal / power-save hints to the adaptive quality controller and exposes the GL [processor] the camera attaches
 * to its pipeline.
 */
@Singleton
class DefaultBeautyEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: PreferencesDataSource,
    private val deviceProfiler: DeviceProfiler,
    private val thermalMonitor: ThermalMonitor,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BeautyEngine {

    private val controls = BeautyControls(TierHint.MID)
    private val _state = MutableStateFlow(BeautyState())
    private val _status = MutableStateFlow(BeautyStatus(quality = QualityController.capFor(TierHint.MID, ThermalHint.NORMAL, false)))
    private val _previewBypass = MutableStateFlow(false)
    private val _eyeColor = MutableStateFlow(EyeColorSetting())
    private val userChanged = AtomicBoolean(false)
    private val eyeColorChanged = AtomicBoolean(false)

    override val processor: GlFrameProcessor = BeautyProcessor(controls, context.applicationContext)
    override val status: StateFlow<BeautyStatus> = _status.asStateFlow()
    override val state: StateFlow<BeautyState> = _state.asStateFlow()
    override val previewBypass: StateFlow<Boolean> = _previewBypass.asStateFlow()
    override val eyeColor: StateFlow<EyeColorSetting> = _eyeColor.asStateFlow()

    init {
        controls.state = _state.value
        controls.onStatus = { _status.value = it }
        scope.launch { observeDevice() }
        scope.launch { restoreAndPersist() }
        scope.launch { restoreAndPersistEyeColor() }
    }

    override fun setEyeColor(setting: EyeColorSetting) {
        val clean = setting.copy(intensity = setting.intensity.coerceIn(0, 100))
        eyeColorChanged.set(true)
        controls.eyeColor = clean
        _eyeColor.value = clean
    }

    override fun setState(state: BeautyState) {
        userChanged.set(true)
        controls.state = state
        _state.value = state
    }

    override fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean) {
        controls.bypassAll = showOriginal && affectsRecording
        _previewBypass.value = showOriginal && !affectsRecording
    }

    override fun setRecording(recording: Boolean) {
        controls.recording = recording
    }

    private suspend fun observeDevice() {
        try {
            controls.tier = when (deviceProfiler.profile.tier) {
                DeviceTier.LOW -> TierHint.LOW
                DeviceTier.MID -> TierHint.MID
                DeviceTier.HIGH -> TierHint.HIGH
            }
            _status.value = _status.value.copy(quality = QualityController.capFor(controls.tier, controls.thermal, controls.powerSave))
            thermalMonitor.levels
                .catch { e -> RgLog.w(TAG, "Thermal status unavailable", e) }
                .collect { level ->
                    controls.thermal = when (level) {
                        ThermalLevel.NORMAL -> ThermalHint.NORMAL
                        ThermalLevel.WARM -> ThermalHint.WARM
                        ThermalLevel.HOT -> ThermalHint.HOT
                        ThermalLevel.CRITICAL -> ThermalHint.CRITICAL
                    }
                    controls.powerSave = thermalMonitor.isPowerSaveMode
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Device profiling failed; keeping defaults", e)
        }
    }

    private suspend fun restoreAndPersist() {
        val saved = try {
            preferences.beautyState.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore beauty state", e)
            null
        }
        if (saved != null && !userChanged.get()) {
            controls.state = saved
            _state.value = saved
        } else if (userChanged.get()) {
            persist(_state.value)
        }
        _state.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { persist(it) }
    }

    private suspend fun restoreAndPersistEyeColor() {
        val prefs: SharedPreferences? = try {
            withContext(io) {
                context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE).also { p ->
                    val saved = EyeColorSetting(p.getInt(KEY_EYE_INTENSITY, 0), p.getLong(KEY_EYE_COLOR, EyeColorSetting.DEFAULT_COLOR))
                    if (!eyeColorChanged.get()) {
                        controls.eyeColor = saved
                        _eyeColor.value = saved
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore eye colour", e)
            null
        }
        prefs ?: return
        // A value set before the restore finished is persisted right away; later ones are debounced.
        val initial = if (eyeColorChanged.get()) _eyeColor else _eyeColor.drop(1)
        initial.debounce(PERSIST_DEBOUNCE_MS).collect { setting ->
            try {
                withContext(io) {
                    prefs.edit().putInt(KEY_EYE_INTENSITY, setting.intensity).putLong(KEY_EYE_COLOR, setting.color).apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Could not save eye colour", e)
            }
        }
    }

    private suspend fun persist(state: BeautyState) {
        try {
            preferences.updateBeautyState { state }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not save beauty state", e)
        }
    }

    private companion object {
        const val TAG = "BeautyEngine"
        const val PERSIST_DEBOUNCE_MS = 600L
        const val EXTRAS_PREFS = "ravango_beauty_extras"
        const val KEY_EYE_INTENSITY = "eye_color_intensity"
        const val KEY_EYE_COLOR = "eye_color_argb"
    }
}
