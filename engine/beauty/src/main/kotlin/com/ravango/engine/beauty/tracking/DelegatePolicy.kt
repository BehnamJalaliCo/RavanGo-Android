package com.ravango.engine.beauty.tracking

import android.content.Context
import android.content.SharedPreferences
import com.ravango.core.common.diagnostics.Diagnostics
import com.ravango.core.common.diagnostics.ExitHistory
import com.ravango.core.common.log.RgLog

/**
 * Crash-loop breaker for MediaPipe's GPU delegate. Some GPU drivers crash *natively* (SIGSEGV/abort) while the GPU
 * delegate initializes or runs: that cannot be caught, and without memory it would crash again every time the
 * camera opens. Markers are written (synchronously) around the risky parts:
 *
 * - `init` is set right before creating the task on the GPU and cleared right after. Still set on the next start →
 *   the process died inside GPU initialization → the GPU delegate is disabled.
 * - `running` is set while the task runs on the GPU and cleared on close. Still set on the next start *and* the
 *   system reports that the previous process ended with a native crash → the GPU delegate is disabled.
 *
 * The decision is kept per app version (an update retries the GPU). The CPU delegate is used instead: slower but
 * safe (inputs are small). Pure logic lives in [DelegateDecision] for unit tests.
 */
internal class DelegatePolicy(context: Context, private val task: String) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val version: Long = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private val keyInit = "$task.gpu_init"
    private val keyRunning = "$task.gpu_running"
    private val keyBlocked = "$task.gpu_blocked_version"

    /** Whether the GPU delegate may be tried. Call on a background thread (may read the exit history). */
    fun gpuAllowed(): Boolean {
        val decision = DelegateDecision.decide(
            blockedForVersion = prefs.getLong(keyBlocked, -1L) == version,
            initMarker = prefs.getBoolean(keyInit, false),
            runningMarker = prefs.getBoolean(keyRunning, false),
            lastExitNative = { ExitHistory.lastExitWasNativeCrash(appContext) },
        )
        if (decision.block) {
            if (prefs.getLong(keyBlocked, -1L) != version) {
                Diagnostics.record("MediaPipe", "$task: GPU delegate disabled (${decision.reason}); using CPU")
            }
            prefs.edit().putLong(keyBlocked, version).putBoolean(keyInit, false).putBoolean(keyRunning, false).commit()
        }
        return !decision.block
    }

    fun beginGpuInit() {
        prefs.edit().putBoolean(keyInit, true).commit()
    }

    fun endGpuInit(success: Boolean) {
        prefs.edit().putBoolean(keyInit, false).putBoolean(keyRunning, success).commit()
    }

    /** The task stopped using the GPU (closed, or fell back to CPU). */
    fun gpuStopped() {
        runCatching { prefs.edit().putBoolean(keyRunning, false).apply() }
            .onFailure { RgLog.w("MediaPipe", "could not clear marker", it) }
    }

    private companion object {
        const val PREFS = "ravango_mediapipe_delegate"
    }
}

/** Pure decision of [DelegatePolicy]. */
internal object DelegateDecision {
    data class Result(val block: Boolean, val reason: String)

    fun decide(blockedForVersion: Boolean, initMarker: Boolean, runningMarker: Boolean, lastExitNative: () -> Boolean): Result = when {
        blockedForVersion -> Result(true, "disabled earlier for this version")
        initMarker -> Result(true, "the app died while the GPU delegate was starting")
        runningMarker && lastExitNative() -> Result(true, "native crash while the GPU delegate was running")
        else -> Result(false, "ok")
    }
}
