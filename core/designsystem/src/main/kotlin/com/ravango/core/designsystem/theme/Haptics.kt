package com.ravango.core.designsystem.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** Semantic haptic events; mapped to the best platform constant available on the device. */
enum class HapticEvent { TAP, TICK, TOGGLE_ON, TOGGLE_OFF, CONFIRM, REJECT, LONG_PRESS, SNAP, RECORD_START, RECORD_STOP }

@Stable
class Haptics(private val view: View, private val enabled: Boolean) {
    fun perform(event: HapticEvent) {
        if (!enabled) return
        val constant = when (event) {
            HapticEvent.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.SNAP -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.TOGGLE_ON -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.TOGGLE_OFF -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.CONFIRM, HapticEvent.RECORD_START ->
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
            HapticEvent.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticEvent.LONG_PRESS, HapticEvent.RECORD_STOP -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

internal val LocalHapticsEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
