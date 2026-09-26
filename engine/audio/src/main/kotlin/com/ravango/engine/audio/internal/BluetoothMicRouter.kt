package com.ravango.engine.audio.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import com.ravango.core.common.log.RgLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Routes capture to a Bluetooth microphone (classic SCO or LE Audio headset).
 *
 * - API 31+: selects the headset as communication device (`setCommunicationDevice`) and clears it afterwards.
 * - Older: starts a SCO link (`startBluetoothSco`) and waits for `ACTION_SCO_AUDIO_STATE_UPDATED` → CONNECTED.
 *
 * The audio mode is switched to MODE_IN_COMMUNICATION while the link is up (required by many HALs to open the
 * headset mic) and restored on [disconnect]. All calls are blocking and made from the capture thread.
 */
internal class BluetoothMicRouter(
    private val context: Context,
    private val audioManager: AudioManager,
    private val handler: Handler,
) {
    private var connected = false
    private var usedScoApi = false
    private var previousMode = AudioManager.MODE_NORMAL

    /** @return true when the Bluetooth mic is routed and ready. */
    fun connect(input: AudioDeviceInfo, timeoutMs: Long = 4_000): Boolean {
        if (connected) disconnect()
        previousMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) connectModern(input) else connectSco(timeoutMs)
        if (!ok) {
            restoreMode()
            return false
        }
        connected = true
        return true
    }

    fun disconnect() {
        if (!connected) return
        connected = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !usedScoApi) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        restoreMode()
    }

    private fun restoreMode() {
        runCatching { if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = previousMode }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun connectModern(input: AudioDeviceInfo): Boolean {
        usedScoApi = false
        val candidates = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        // The communication device is the output side of the headset: match by address, then by type.
        val outputType = if (input.type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioDeviceInfo.TYPE_BLE_HEADSET else AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val target = candidates.firstOrNull { it.type == outputType && it.address == input.address }
            ?: candidates.firstOrNull { it.type == outputType }
        if (target == null) {
            RgLog.w(TAG, "No communication device for ${input.productName} (type ${input.type})")
            return false
        }
        val ok = runCatching { audioManager.setCommunicationDevice(target) }.getOrElse {
            RgLog.w(TAG, "setCommunicationDevice failed", it); false
        }
        if (!ok) return false
        // Wait (briefly) for the platform to report the switch.
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) {
            if (audioManager.communicationDevice?.id == target.id) return true
            Thread.sleep(50)
        }
        return audioManager.communicationDevice?.id == target.id
    }

    @Suppress("DEPRECATION")
    private fun connectSco(timeoutMs: Long): Boolean {
        usedScoApi = true
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        val latch = CountDownLatch(1)
        var state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val s = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                // The initial sticky value is stale unless the link is already up.
                if (isInitialStickyBroadcast && s != AudioManager.SCO_AUDIO_STATE_CONNECTED) return
                state = s
                if (s == AudioManager.SCO_AUDIO_STATE_CONNECTED || s == AudioManager.SCO_AUDIO_STATE_ERROR) latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), null, handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val ok = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            if (!ok) {
                RgLog.w(TAG, "SCO did not connect (state=$state)")
                runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
            }
            ok
        } catch (e: Exception) {
            RgLog.w(TAG, "startBluetoothSco failed", e)
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object { const val TAG = "BtMic" }
}
