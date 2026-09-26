package com.ravango.core.ui

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Routes hardware key events (volume buttons, Bluetooth remotes / page turners, keyboards, media keys)
 * from the Activity to the top-most interested screen. MainActivity calls [dispatch] from dispatchKeyEvent.
 */
object HardwareKeys {
    private val handlers = ArrayDeque<(KeyEvent) -> Boolean>()

    @Synchronized
    fun dispatch(event: KeyEvent): Boolean {
        for (i in handlers.indices.reversed()) if (handlers[i](event)) return true
        return false
    }

    @Synchronized internal fun push(handler: (KeyEvent) -> Boolean) = handlers.addLast(handler)
    @Synchronized internal fun remove(handler: (KeyEvent) -> Boolean) = handlers.remove(handler)

    /** Key codes typically sent by Bluetooth teleprompter remotes and page turners. */
    val REMOTE_FORWARD = setOf(
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_TAB,
    )
    val REMOTE_BACKWARD = setOf(
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
    )
    val REMOTE_TOGGLE = setOf(
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_CAMERA,
    )
    val VOLUME = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
}

/**
 * Registers [onKey] while this composable is in composition. Return true to consume the event.
 * The most recently registered handler wins.
 */
@Composable
fun HardwareKeyHandler(enabled: Boolean = true, onKey: (KeyEvent) -> Boolean) {
    val current by rememberUpdatedState(onKey)
    DisposableEffect(enabled) {
        val handler: (KeyEvent) -> Boolean = { e -> enabled && current(e) }
        if (enabled) HardwareKeys.push(handler)
        onDispose { HardwareKeys.remove(handler) }
    }
}
