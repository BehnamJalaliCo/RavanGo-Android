package com.ravango.core.common.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Normalized thermal pressure. Engines degrade progressively: effects first, never the recording itself. */
enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }

@Singleton
class ThermalMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    val current: ThermalLevel
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) map(powerManager.currentThermalStatus) else ThermalLevel.NORMAL

    val levels: Flow<ThermalLevel> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callbackFlow {
                val listener = PowerManager.OnThermalStatusChangedListener { trySend(map(it)) }
                trySend(map(powerManager.currentThermalStatus))
                powerManager.addThermalStatusListener(context.mainExecutor, listener)
                awaitClose { powerManager.removeThermalStatusListener(listener) }
            }.distinctUntilChanged()
        } else {
            flowOf(ThermalLevel.NORMAL)
        }

    val isPowerSaveMode: Boolean get() = powerManager.isPowerSaveMode

    private fun map(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
        else -> ThermalLevel.CRITICAL
    }
}
