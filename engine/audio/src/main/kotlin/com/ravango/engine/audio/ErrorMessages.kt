package com.ravango.engine.audio

import android.Manifest
import androidx.annotation.StringRes

/** Localized message for an [AudioEngineError] (English + Persian resources in engine:audio). */
@StringRes
fun AudioEngineError.messageRes(): Int = when (this) {
    is AudioEngineError.PermissionRequired ->
        if (permission == Manifest.permission.BLUETOOTH_CONNECT) R.string.audio_error_bluetooth_permission else R.string.audio_error_mic_permission
    is AudioEngineError.InputLost -> R.string.audio_error_input_lost
    is AudioEngineError.BluetoothUnavailable -> R.string.audio_error_bluetooth_unavailable
    AudioEngineError.MicrophoneUnavailable -> R.string.audio_error_mic_unavailable
    is AudioEngineError.RecordingFailed -> R.string.audio_error_recording_failed
}
