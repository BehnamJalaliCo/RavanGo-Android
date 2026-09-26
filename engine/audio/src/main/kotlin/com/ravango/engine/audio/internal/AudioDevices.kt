package com.ravango.engine.audio.internal

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioInputType
import com.ravango.core.model.AudioSettings
import com.ravango.engine.audio.R

/** Pure mapping of platform device types (AudioDeviceInfo.TYPE_*) to our input types; null = not offered. */
internal object InputTypeMapper {
    // Constant values mirrored so the mapping is testable on the JVM.
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_LINE_ANALOG = 5
    const val TYPE_LINE_DIGITAL = 6
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_HDMI = 9
    const val TYPE_HDMI_ARC = 10
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_DOCK = 13
    const val TYPE_USB_HEADSET = 22
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_HDMI_EARC = 29
    const val TYPE_DOCK_ANALOG = 31

    fun map(type: Int): AudioInputType? = when (type) {
        TYPE_BUILTIN_MIC -> AudioInputType.BUILT_IN
        TYPE_WIRED_HEADSET, TYPE_LINE_ANALOG -> AudioInputType.WIRED
        TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY -> AudioInputType.USB
        TYPE_BLUETOOTH_SCO -> AudioInputType.BLUETOOTH
        TYPE_BLE_HEADSET -> AudioInputType.BLUETOOTH_LE
        TYPE_HDMI, TYPE_HDMI_ARC, TYPE_HDMI_EARC -> AudioInputType.HDMI
        TYPE_LINE_DIGITAL, TYPE_DOCK, TYPE_DOCK_ANALOG -> AudioInputType.OTHER
        // Telephony, remote submix, FM/TV tuners, echo reference, bus, IP… are never user-selectable mics.
        else -> null
    }

    /** Stable ordering for the picker: external/pro inputs first, the phone mic last. */
    fun rank(type: AudioInputType): Int = when (type) {
        AudioInputType.USB -> 0
        AudioInputType.WIRED -> 1
        AudioInputType.BLUETOOTH_LE -> 2
        AudioInputType.BLUETOOTH -> 3
        AudioInputType.HDMI -> 4
        AudioInputType.OTHER -> 5
        AudioInputType.BUILT_IN -> 6
    }

    val AudioInputType.isBluetooth: Boolean get() = this == AudioInputType.BLUETOOTH || this == AudioInputType.BLUETOOTH_LE
}

/** A mapped input together with the platform device it came from. */
internal class InputEntry(val device: AudioInputDevice, val info: AudioDeviceInfo)

internal class AudioDeviceCatalog(private val context: Context, private val audioManager: AudioManager) {

    fun inputs(): List<InputEntry> {
        val devices = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrDefault(emptyArray())
        val seen = HashSet<String>()
        val builtIns = devices.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        var builtInIndex = 0
        val result = ArrayList<InputEntry>()
        for (info in devices) {
            val type = InputTypeMapper.map(info.type) ?: continue
            val key = "${info.type}|${info.productName}|${address(info)}"
            if (!seen.add(key)) continue
            val name = when (type) {
                AudioInputType.BUILT_IN -> {
                    builtInIndex++
                    builtInName(address(info), builtInIndex, builtIns)
                }
                else -> externalName(type, info)
            }
            result += InputEntry(
                AudioInputDevice(
                    id = info.id,
                    name = name,
                    type = type,
                    channelCounts = info.channelCounts.toList().distinct().sorted(),
                    sampleRates = info.sampleRates.toList().distinct().sorted(),
                ),
                info,
            )
        }
        return result.sortedWith(compareBy({ InputTypeMapper.rank(it.device.type) }, { it.device.name }))
    }

    /** Headphones that make live monitoring safe (no speaker feedback) and low-latency: wired or USB. */
    fun monitoringOutput(): AudioDeviceInfo? {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
        val preferred = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
        )
        for (t in preferred) outputs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    fun supportsUnprocessedSource(): Boolean =
        runCatching { audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true" }.getOrDefault(false)

    /**
     * Resolves the preference to a concrete input: exact name match within the preferred type, then any input of
     * that type, then the built-in microphone. Returns null only if the device reports no inputs at all.
     */
    fun resolve(settings: AudioSettings, entries: List<InputEntry>): InputEntry? =
        resolve(settings.preferredInput, settings.preferredDeviceName, entries.map { it.device })?.let { d -> entries.first { it.device.id == d.id } }

    private fun address(info: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { info.address }.getOrNull().orEmpty() else ""

    private fun builtInName(address: String, index: Int, total: Int): String {
        val base = context.getString(R.string.audio_input_builtin)
        if (total <= 1) return base
        val position = when {
            address.contains("back", ignoreCase = true) -> context.getString(R.string.audio_input_position_back)
            address.contains("front", ignoreCase = true) || address.contains("top", ignoreCase = true) -> context.getString(R.string.audio_input_position_front)
            address.contains("bottom", ignoreCase = true) -> context.getString(R.string.audio_input_position_bottom)
            else -> index.toString()
        }
        return context.getString(R.string.audio_input_with_position, base, position)
    }

    private fun externalName(type: AudioInputType, info: AudioDeviceInfo): String {
        val product = info.productName?.toString()?.trim().orEmpty()
        val generic = context.getString(
            when (type) {
                AudioInputType.WIRED -> R.string.audio_input_wired
                AudioInputType.USB -> R.string.audio_input_usb
                AudioInputType.BLUETOOTH -> R.string.audio_input_bluetooth
                AudioInputType.BLUETOOTH_LE -> R.string.audio_input_bluetooth_le
                AudioInputType.HDMI -> R.string.audio_input_hdmi
                AudioInputType.OTHER, AudioInputType.BUILT_IN -> R.string.audio_input_other
            },
        )
        // Wired jacks report the phone model as product name — meaningless to users.
        val useProduct = product.isNotEmpty() && type != AudioInputType.WIRED && !product.equals(Build.MODEL, ignoreCase = true)
        return if (useProduct) context.getString(R.string.audio_input_named, product, generic) else generic
    }

    companion object {
        /** Pure resolution logic (JVM-testable). */
        fun resolve(type: AudioInputType, name: String?, inputs: List<AudioInputDevice>): AudioInputDevice? {
            val ofType = inputs.filter { it.type == type }
            return (name?.let { n -> ofType.firstOrNull { it.name == n } } ?: ofType.firstOrNull())
                ?: inputs.firstOrNull { it.type == AudioInputType.BUILT_IN }
                ?: inputs.firstOrNull()
        }
    }
}
