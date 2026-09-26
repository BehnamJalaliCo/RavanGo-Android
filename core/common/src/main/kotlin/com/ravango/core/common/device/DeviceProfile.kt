package com.ravango.core.common.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Coarse performance class used to scale heavy features (beauty, preview effects, export concurrency). */
enum class DeviceTier { LOW, MID, HIGH }

data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamMb: Long,
    val cpuCores: Int,
    val isLowRamDevice: Boolean,
    /** Android media performance class (0 if not declared, else e.g. 31, 33, 34). */
    val mediaPerformanceClass: Int,
    val socModel: String?,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
)

@Singleton
class DeviceProfiler @Inject constructor(@ApplicationContext private val context: Context) {

    val profile: DeviceProfile by lazy { compute() }

    private fun compute(): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramMb = mem.totalMem / (1024 * 1024)
        // availableProcessors() counts *online* cores only; big.LITTLE phones hotplug cores off when idle, which
        // made 8-core phones look like 4-core LOW-tier devices (beauty capped at LIGHT). Use the present cores.
        val cores = maxOf(Runtime.getRuntime().availableProcessors(), presentCpuCount())
        val mpc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val tier = when {
            am.isLowRamDevice || ramMb < 3_000 || cores <= 4 -> DeviceTier.LOW
            mpc >= Build.VERSION_CODES.TIRAMISU || (ramMb >= 7_000 && cores >= 8) -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
        com.ravango.core.common.diagnostics.Diagnostics.setEnv("device.tier", "$tier (ram ${ramMb}MB, cores $cores, mpc $mpc, soc $soc)")
        return DeviceProfile(
            tier = tier,
            totalRamMb = ramMb,
            cpuCores = cores,
            isLowRamDevice = am.isLowRamDevice,
            mediaPerformanceClass = mpc,
            socModel = soc,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    private fun presentCpuCount(): Int = runCatching {
        CpuRanges.count(java.io.File("/sys/devices/system/cpu/present").readText())
    }.getOrDefault(0)
}

/** Parses Linux CPU range lists such as `0-7` or `0-3,6`. */
object CpuRanges {
    fun count(text: String): Int = text.trim().split(',').filter { it.isNotBlank() }.sumOf { part ->
        val bounds = part.trim().split('-')
        val start = bounds[0].trim().toIntOrNull() ?: return@sumOf 0
        val end = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: start
        if (end >= start) end - start + 1 else 0
    }
}
