package com.ravango.core.common.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Process-wide, on-device diagnostics log (nothing is ever uploaded).
 *
 * - [note] keeps a small in-memory ring of recent warnings/errors ("breadcrumbs") that crash reports include.
 * - [record] is for non-fatal problems worth keeping across a crash: shader compile/link failures, GL errors,
 *   MediaPipe init failures, effect passes that were switched off. They go to the ring *and* to
 *   `files/diagnostics/events.log` (written on a background thread, size-capped), so they survive native crashes
 *   that kill the process without a Java callback.
 * - [setEnv] holds facts about the runtime (GL renderer/version, MediaPipe delegate…) printed in every report.
 *
 * Safe to call from any thread (GL, camera, MediaPipe callbacks) and before [attach]: events are then kept in
 * memory only. Identical events are rate-limited so a per-frame failure cannot flood the log.
 */
object Diagnostics {

    private const val RING_SIZE = 150
    private const val MAX_LOG_BYTES = 128 * 1024L
    private const val REPEAT_WINDOW_MS = 10_000L
    private const val MAX_MESSAGE = 2_000
    private const val MAX_TRACE = 6_000

    private val ring = ArrayDeque<String>(RING_SIZE)
    private val env = ConcurrentHashMap<String, String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rg-diagnostics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile private var dir: File? = null

    /** Directory holding `events.log` (and crash reports, see [CrashReporter]). */
    val directory: File? get() = dir

    /** Starts persisting [record]ed events under [directory]. Idempotent. */
    fun attach(directory: File) {
        runCatching { directory.mkdirs() }
        dir = directory
    }

    fun setEnv(key: String, value: String) {
        env[key] = value.take(300)
    }

    fun environment(): Map<String, String> = env.toSortedMap()

    /** In-memory breadcrumb (warnings / errors logged through `RgLog`). [level] is 'W', 'E', 'I'. */
    fun note(level: Char, tag: String, message: String, t: Throwable? = null) {
        if (!shouldEmit(level, tag, message)) return
        push(line(level, tag, message, t, traceLimit = 600))
    }

    /** A non-fatal problem worth keeping: added to the ring and appended to the on-device event log. */
    fun record(tag: String, message: String, t: Throwable? = null) {
        if (!shouldEmit('E', tag, message)) return
        val text = line('E', tag, message, t, traceLimit = MAX_TRACE)
        push(text)
        val d = dir ?: return
        runCatching {
            writer.execute {
                runCatching {
                    val file = File(d, LOG_FILE)
                    if (file.length() > MAX_LOG_BYTES) {
                        val old = File(d, OLD_LOG_FILE)
                        old.delete()
                        file.renameTo(old)
                    }
                    file.appendText(text + "\n")
                }
            }
        }
    }

    /** Recent breadcrumbs, oldest first. */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    /** The persisted event log (previous + current file), newest last. Does I/O. */
    fun persistedLog(maxChars: Int = 24_000): String {
        val d = dir ?: return ""
        val text = buildString {
            runCatching { File(d, OLD_LOG_FILE).takeIf { it.exists() }?.readText()?.let(::append) }
            runCatching { File(d, LOG_FILE).takeIf { it.exists() }?.readText()?.let(::append) }
        }
        return if (text.length > maxChars) "…\n" + text.takeLast(maxChars) else text
    }

    fun clearPersisted() {
        val d = dir ?: return
        writer.execute {
            File(d, LOG_FILE).delete()
            File(d, OLD_LOG_FILE).delete()
        }
    }

    private fun push(text: String) {
        synchronized(ring) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(text)
        }
    }

    private fun shouldEmit(level: Char, tag: String, message: String): Boolean {
        val key = "$level|$tag|${message.take(120)}"
        val now = System.currentTimeMillis()
        val previous = lastSeen.put(key, now)
        if (lastSeen.size > 512) lastSeen.clear()
        return previous == null || now - previous > REPEAT_WINDOW_MS
    }

    private fun line(level: Char, tag: String, message: String, t: Throwable?, traceLimit: Int): String {
        val time = timeFormat.get()!!.format(Date())
        val base = "$time $level/$tag: ${message.take(MAX_MESSAGE)}"
        if (t == null) return base
        return base + "\n" + t.stackTraceToString().take(traceLimit).trimEnd()
    }

    internal const val LOG_FILE = "events.log"
    internal const val OLD_LOG_FILE = "events.1.log"
}
