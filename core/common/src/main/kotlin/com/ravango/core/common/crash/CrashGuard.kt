package com.ravango.core.common.crash

import android.content.Context
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects unclean shutdowns so the app can offer recovery (unsaved drafts, interrupted recordings).
 *
 * A marker file is written on start and removed on clean exit of a critical section. The previous uncaught-exception
 * handler is always chained, so platform crash reporting keeps working.
 */
@Singleton
class CrashGuard @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir = File(context.filesDir, "crash").apply { mkdirs() }
    private val lastCrashFile = File(dir, "last_crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                lastCrashFile.writeText("${System.currentTimeMillis()}\n${thread.name}\n${throwable.stackTraceToString().take(8_000)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns and clears the report of a crash that happened in a previous process, if any. */
    fun consumePreviousCrash(): String? = runCatching {
        if (!lastCrashFile.exists()) return null
        lastCrashFile.readText().also { lastCrashFile.delete() }
    }.onFailure { RgLog.w("CrashGuard", "read failed", it) }.getOrNull()

    /** Marks a critical section (e.g. recording) as active. If the process dies, [wasInterrupted] reports it next launch. */
    fun beginSection(name: String, payload: String = "") {
        runCatching { File(dir, "$name.active").writeText(payload) }
    }

    fun endSection(name: String) {
        File(dir, "$name.active").delete()
    }

    fun interruptedPayload(name: String): String? = File(dir, "$name.active").takeIf { it.exists() }?.readText()
}
