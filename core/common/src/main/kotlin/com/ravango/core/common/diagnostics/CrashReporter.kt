package com.ravango.core.common.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.ravango.core.common.AppConfig
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * On-device crash reporting. Nothing leaves the device unless the user shares a report.
 *
 * - [install] (from `Application.onCreate`) chains a default uncaught-exception handler that writes the stack trace
 *   plus device / app / GL / MediaPipe facts and the recent diagnostics to `files/diagnostics/reports/`, then hands
 *   over to the previous handler (the system still shows its crash dialog).
 * - On Android 11+ the next launch also reads `ActivityManager.getHistoricalProcessExitReasons`: native crashes
 *   (GPU driver, MediaPipe) with the tombstone, ANRs with their trace, and Java crashes the handler could not
 *   write. Exit records the system still holds from before this version was installed are imported too.
 * - [pending] lists reports the user has not acknowledged yet (the launch prompt); [reports] all stored ones.
 */
@Singleton
class CrashReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: AppConfig,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val root = File(context.filesDir, "diagnostics")
    private val reportsDir = File(root, "reports")
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    private val _pending = MutableStateFlow<List<CrashReport>>(emptyList())

    /** Every stored report, newest first. */
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    /** Reports newer than the last one the user acknowledged, newest first. */
    val pending: StateFlow<List<CrashReport>> = _pending.asStateFlow()

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Diagnostics.attach(root)
        Diagnostics.setEnv("app.version", "${config.versionName} (${config.versionCode})")
        chainLogSink()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeJavaReport(thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        scope.launch(io) {
            runCatching { if (Build.VERSION.SDK_INT >= 30) importExitReasons() }
                .onFailure { RgLog.w(TAG, "could not read exit reasons", it) }
            refresh()
        }
    }

    /** Reloads the stored reports (I/O). */
    suspend fun refresh() = withContext(io) {
        val all = runCatching {
            reportsDir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().mapNotNull { f ->
                runCatching { CrashReportFormat.decode(f.name.removeSuffix(EXT), f.readText()) }.getOrNull()
            }.sortedByDescending { it.timeMillis }
        }.getOrDefault(emptyList())
        val acknowledged = prefs.getLong(KEY_ACKNOWLEDGED, 0L)
        _reports.value = all
        _pending.value = all.filter { it.timeMillis > acknowledged }
    }

    /** Marks every current report as seen (the launch prompt stops showing them). */
    suspend fun acknowledge() = withContext(io) {
        val newest = _reports.value.maxOfOrNull { it.timeMillis } ?: return@withContext
        prefs.edit().putLong(KEY_ACKNOWLEDGED, maxOf(newest, prefs.getLong(KEY_ACKNOWLEDGED, 0L))).apply()
        _pending.value = emptyList()
    }

    suspend fun delete(id: String) = withContext(io) {
        File(reportsDir, id + EXT).delete()
        refresh()
    }

    suspend fun deleteAll() = withContext(io) {
        reportsDir.listFiles().orEmpty().forEach { it.delete() }
        Diagnostics.clearPersisted()
        refresh()
    }

    // ------------------------------------------------------------------------------------------------ writing

    private fun chainLogSink() {
        val previous = RgLog.sink
        RgLog.sink = { level, tag, message, t ->
            if (level >= android.util.Log.ERROR) Diagnostics.record(tag, message, t) else Diagnostics.note('W', tag, message, t)
            previous?.invoke(level, tag, message, t)
        }
    }

    private fun writeJavaReport(thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val details = buildString {
            append("Uncaught exception in thread \"").append(thread.name).append("\"\n")
            append(throwable.stackTraceToString().take(40_000))
        }
        val body = CrashReportFormat.body(
            title = "Kind: app error (uncaught exception)",
            timeMillis = now,
            device = deviceInfo(),
            environment = Diagnostics.environment(),
            details = details,
            recent = Diagnostics.recent(),
            persisted = Diagnostics.persistedLog(maxChars = 12_000),
        )
        store(CrashReport(CrashReportFormat.fileName(CrashKind.JAVA, now), CrashKind.JAVA, now, CrashReportFormat.summarize(throwable), body))
    }

    @RequiresApi(30)
    private fun importExitReasons() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        val lastImported = prefs.getLong(KEY_LAST_EXIT, 0L)
        var newest = lastImported
        val existing = loadAll()
        for (info in infos) {
            val ts = info.timestamp
            if (ts <= lastImported) continue
            newest = maxOf(newest, ts)
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashKind.NATIVE
                ApplicationExitInfo.REASON_ANR -> CrashKind.ANR
                ApplicationExitInfo.REASON_CRASH -> CrashKind.JAVA
                else -> null
            } ?: continue
            if (kind == CrashKind.JAVA && CrashReportFormat.coveredByJavaReport(ts, existing)) continue
            store(exitReport(info, kind))
        }
        if (newest != lastImported) prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
    }

    @RequiresApi(30)
    private fun exitReport(info: ApplicationExitInfo, kind: CrashKind): CrashReport {
        val trace: String? = runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readNBytesCompat(MAX_TRACE_BYTES)
                if (kind == CrashKind.NATIVE) CrashReportFormat.printableStrings(bytes) else String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        val stateSummary = runCatching { info.processStateSummary?.let { String(it, Charsets.UTF_8) } }.getOrNull()
        val details = buildString {
            append("Recorded by Android (ApplicationExitInfo)\n")
            append("reason: ").append(reasonName(info.reason)).append('\n')
            append("description: ").append(info.description ?: "-").append('\n')
            append("status/signal: ").append(info.status).append('\n')
            append("importance: ").append(info.importance).append('\n')
            append("process: ").append(info.processName).append(" pid ").append(info.pid).append('\n')
            append("pss: ").append(info.pss).append(" kB, rss: ").append(info.rss).append(" kB\n")
            if (!stateSummary.isNullOrBlank()) append("state at exit: ").append(stateSummary).append('\n')
            append('\n')
            when {
                trace.isNullOrBlank() -> append("(no trace available from the system)\n")
                kind == CrashKind.NATIVE -> append("-- tombstone (readable strings) --\n").append(trace)
                else -> append("-- trace --\n").append(trace)
            }
        }
        val summary = when (kind) {
            CrashKind.NATIVE -> CrashReportFormat.nativeSummary(info.description, trace.orEmpty())
            CrashKind.ANR -> "App not responding" + (info.description?.takeIf { it.isNotBlank() }?.let { " · ${it.take(120)}" } ?: "")
            CrashKind.JAVA -> "App error" + (info.description?.takeIf { it.isNotBlank() }?.let { " · ${it.take(120)}" } ?: "")
        }
        val body = CrashReportFormat.body(
            title = "Kind: " + when (kind) {
                CrashKind.NATIVE -> "native crash"
                CrashKind.ANR -> "app not responding (ANR)"
                CrashKind.JAVA -> "app error (from the system record)"
            },
            timeMillis = info.timestamp,
            device = deviceInfo(),
            environment = emptyMap(), // Runtime facts of *this* process would be misleading for an earlier one.
            details = details,
            recent = emptyList(),
            persisted = Diagnostics.persistedLog(maxChars = 16_000),
        )
        return CrashReport(CrashReportFormat.fileName(kind, info.timestamp), kind, info.timestamp, summary, body)
    }

    private fun store(report: CrashReport) {
        reportsDir.mkdirs()
        File(reportsDir, report.id + EXT).writeText(CrashReportFormat.encode(report))
        val all = loadAll()
        CrashReportFormat.prune(all, MAX_REPORTS).forEach { File(reportsDir, it + EXT).delete() }
    }

    private fun loadAll(): List<CrashReport> =
        reportsDir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().mapNotNull { f ->
            runCatching { CrashReportFormat.decode(f.name.removeSuffix(EXT), f.readText()) }.getOrNull()
        }

    private fun deviceInfo(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["App"] = "RavanGo ${config.versionName} (${config.versionCode})${if (config.isDebug) " debug" else ""} · ${config.distribution}"
        map["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})"
        map["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            (if (Build.VERSION.SDK_INT >= 23) " patch ${Build.VERSION.SECURITY_PATCH}" else "")
        map["ABIs"] = Build.SUPPORTED_ABIS.joinToString()
        if (Build.VERSION.SDK_INT >= 31) map["SoC"] = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            map["Memory"] = "${mi.totalMem / (1024 * 1024)} MB total · heap ${am.memoryClass}/${am.largeMemoryClass} MB" +
                (if (am.isLowRamDevice) " · low-RAM device" else "")
        }
        runCatching {
            val rt = Runtime.getRuntime()
            map["Java heap"] = "${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} / ${rt.maxMemory() / (1024 * 1024)} MB"
        }
        map["Locale"] = Locale.getDefault().toLanguageTag()
        return map
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> reason.toString()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "CrashReporter"
        const val PREFS = "ravango_diagnostics"
        const val KEY_ACKNOWLEDGED = "acknowledged_until"
        const val KEY_LAST_EXIT = "last_exit_imported"
        const val EXT = ".txt"
        const val MAX_REPORTS = 12
        const val MAX_EXIT_RECORDS = 10
        const val MAX_TRACE_BYTES = 512 * 1024
    }
}

/**
 * The previous process's exit reason (Android 11+), cached for this process. Used to break crash loops, e.g. to
 * stop using a MediaPipe GPU delegate after it crashed natively.
 */
object ExitHistory {
    @Volatile private var cached: Int? = null
    @Volatile private var loaded = false

    /** True when the previous run of the app ended with a native crash. Binder call on first use: not on the UI thread. */
    fun lastExitWasNativeCrash(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (!loaded) {
            cached = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                    ?.firstOrNull()?.reason
            }.getOrNull()
            loaded = true
        }
        return cached == ApplicationExitInfo.REASON_CRASH_NATIVE
    }
}
