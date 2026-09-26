package com.ravango.feature.beauty.looks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.rememberHaptics

/**
 * Tap + long-press for carousel tiles (tap applies, long-press toggles favourite) with the design system's spring
 * press scale and haptics; both actions are exposed to accessibility services.
 */
fun Modifier.tileGestures(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    tapLabel: String? = null,
    longPressLabel: String? = null,
): Modifier = composed {
    val haptics = rememberHaptics()
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) Motion.PressScale else 1f, Motion.snappy(), label = "tilePress")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics {
            role = Role.Button
            onClick(label = tapLabel) { tap(); true }
            if (onLongPress != null) onLongClick(label = longPressLabel) { long?.invoke(); true }
        }
        .pointerInput(onLongPress != null) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                    }
                },
                onTap = {
                    haptics.perform(HapticEvent.SNAP)
                    tap()
                },
                onLongPress = if (onLongPress == null) null else { _ ->
                    haptics.perform(HapticEvent.LONG_PRESS)
                    long?.invoke()
                },
            )
        }
}
