package com.ravango.feature.camera.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ravango.core.ui.findActivity
import kotlin.math.abs
import kotlin.math.atan2

/** Snaps an OrientationEventListener angle to 0/90/180/270 with hysteresis (no flicker near 45°). */
internal fun snapOrientation(angle: Int, current: Int): Int {
    if (angle == OrientationEventListener.ORIENTATION_UNKNOWN) return current
    val diff = abs(((angle - current + 540) % 360) - 180)
    if (diff < 60) return current
    return (((angle + 45) / 90) * 90) % 360
}

/**
 * Physical device orientation (0/90/180/270) while the screen stays locked to its natural orientation, so the
 * camera studio rotates its controls, never the preview.
 */
@Composable
internal fun rememberDeviceOrientation(onChange: (Int) -> Unit): State<Int> {
    val context = LocalContext.current
    val state = remember { mutableIntStateOf(0) }
    val callback by rememberUpdatedState(onChange)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val next = snapOrientation(orientation, state.intValue)
                if (next != state.intValue) {
                    state.intValue = next
                    callback(next)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return state
}

/** Locks the activity to its natural orientation and hides system bars while the studio is visible. */
@Composable
internal fun StudioWindow() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
}

/**
 * Screen-plane angle of gravity in degrees (0 = device upright). Draw the horizon rotated by this angle.
 * Uses the gravity sensor, or a low-passed accelerometer when unavailable.
 */
@Composable
internal fun rememberGravityAngle(enabled: Boolean): State<Float> {
    val context = LocalContext.current
    val angle = remember { mutableFloatStateOf(0f) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravity ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val filtered = FloatArray(2)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val alpha = if (gravity != null) 1f else 0.15f
                filtered[0] += alpha * (event.values[0] - filtered[0])
                filtered[1] += alpha * (event.values[1] - filtered[1])
                val degrees = Math.toDegrees(atan2(filtered[0].toDouble(), filtered[1].toDouble())).toFloat()
                if (abs(degrees - angle.floatValue) > 0.05f) angle.floatValue = degrees
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    return angle
}
