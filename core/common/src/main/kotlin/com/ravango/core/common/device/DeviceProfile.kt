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
        val cores = Runtime.getRuntime().availableProcessors()
        val mpc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val tier = when {
            am.isLowRamDevice || ramMb < 3_000 || cores <= 4 -> DeviceTier.LOW
            mpc >= Build.VERSION_CODES.TIRAMISU || (ramMb >= 7_000 && cores >= 8) -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
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
}
