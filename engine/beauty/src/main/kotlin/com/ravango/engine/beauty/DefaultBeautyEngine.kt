package com.ravango.engine.beauty

import com.ravango.core.common.device.DeviceProfiler
import com.ravango.core.common.device.DeviceTier
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.device.ThermalMonitor
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.quality.QualityController
import com.ravango.engine.beauty.quality.ThermalHint
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide beauty engine. Owns the live [BeautyState] (restored from and debounced back to
 * [PreferencesDataSource]), feeds device tier / thermal / power-save hints to the adaptive quality controller and
 * exposes the GL [processor] the camera attaches to its pipeline.
 */
@Singleton
class DefaultBeautyEngine @Inject constructor(
    private val preferences: PreferencesDataSource,
    private val deviceProfiler: DeviceProfiler,
    private val thermalMonitor: ThermalMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) : BeautyEngine {

    private val controls = BeautyControls(TierHint.MID)
    private val _state = MutableStateFlow(BeautyState())
    private val _status = MutableStateFlow(BeautyStatus(quality = QualityController.capFor(TierHint.MID, ThermalHint.NORMAL, false)))
    private val _previewBypass = MutableStateFlow(false)
    private val userChanged = AtomicBoolean(false)

    override val processor: GlFrameProcessor = BeautyProcessor(controls)
    override val status: StateFlow<BeautyStatus> = _status.asStateFlow()
    override val state: StateFlow<BeautyState> = _state.asStateFlow()
    override val previewBypass: StateFlow<Boolean> = _previewBypass.asStateFlow()

    init {
        controls.state = _state.value
        controls.onStatus = { _status.value = it }
        scope.launch { observeDevice() }
        scope.launch { restoreAndPersist() }
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
    }
}
