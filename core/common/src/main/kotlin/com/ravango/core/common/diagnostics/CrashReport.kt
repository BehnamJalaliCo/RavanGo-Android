package com.ravango.core.common.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How the process ended. */
enum class CrashKind {
    /** Uncaught Java/Kotlin exception (stack trace captured in-process). */
    JAVA,
    /** Native crash (e.g. a GPU driver or MediaPipe SIGSEGV), read from the system's exit record + tombstone. */
    NATIVE,
    /** "App not responding", read from the system's exit record + ANR trace. */
    ANR,
}

/** One crash report stored on the device. [id] is its file name (without extension). */
data class CrashReport(
    val id: String,
    val kind: CrashKind,
    val timeMillis: Long,
    /** One line for lists and the launch prompt, e.g. `IllegalStateException: … (CameraRenderer.kt:193)`. */
    val summary: String,
    /** Full, human-readable text that the user can view, copy or share. */
    val body: String,
)

/** Pure helpers to build, store and parse crash reports (unit-tested on the JVM). */
object CrashReportFormat {

    /** Hard cap for a stored report (sharing larger text through an Intent can fail). */
    const val MAX_BODY_CHARS = 90_000

    private const val HEADER_END = "\n---\n"
    private val APP_PACKAGES = listOf("com.ravango.")

    fun fileName(kind: CrashKind, timeMillis: Long): String = "crash-$timeMillis-${kind.name.lowercase(Locale.US)}"

    /** Serialises [report] as `key=value` header lines, a `---` separator and the body. */
    fun encode(report: CrashReport): String = buildString {
        append("kind=").append(report.kind.name).append('\n')
        append("time=").append(report.timeMillis).append('\n')
        append("summary=").append(report.summary.replace('\n', ' ')).append(HEADER_END)
        append(report.body.take(MAX_BODY_CHARS))
    }

    /** Parses [encode]'s output; null when the text is not a report. */
    fun decode(id: String, text: String): CrashReport? {
        val split = text.indexOf(HEADER_END)
        if (split < 0) return null
        val header = text.substring(0, split).lines().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap()
        val kind = header["kind"]?.let { k -> CrashKind.entries.firstOrNull { it.name == k } } ?: return null
        val time = header["time"]?.toLongOrNull() ?: return null
        return CrashReport(id, kind, time, header["summary"].orEmpty(), text.substring(split + HEADER_END.length))
    }

    /** The root cause's class + message and the first frame in app code, e.g. `NPE: x (Foo.kt:12)`. */
    fun summarize(t: Throwable): String {
        var root = t
        var depth = 0
        while (root.cause != null && root.cause !== root && depth < 10) { root = root.cause!!; depth++ }
        val name = root.javaClass.simpleName.ifBlank { root.javaClass.name }
        val message = root.message?.lineSequence()?.firstOrNull()?.take(160)
        val frame = (root.stackTrace.firstOrNull { f -> APP_PACKAGES.any { f.className.startsWith(it) } } ?: root.stackTrace.firstOrNull())
            ?.let { "${it.fileName ?: it.className.substringAfterLast('.')}:${it.lineNumber}" }
        return buildString {
            append(name)
            if (!message.isNullOrBlank()) append(": ").append(message)
            if (frame != null) append(" (").append(frame).append(')')
        }
    }

    /**
     * Printable ASCII runs (like the `strings` tool) of a binary blob, e.g. an Android tombstone protobuf: signal,
     * abort message, library names and symbol names become readable without a proto parser.
     */
    fun printableStrings(bytes: ByteArray, minLength: Int = 5, maxChars: Int = 40_000): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minLength && out.length < maxChars) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == '\t'.code) run.append(c.toChar()) else flush()
            if (out.length >= maxChars) break
        }
        flush()
        return if (out.length > maxChars) out.substring(0, maxChars) else out.toString()
    }

    /** The most telling line of a native tombstone's strings: signal / abort message / first backtrace library. */
    fun nativeSummary(description: String?, strings: String): String {
        val lines = strings.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val signal = lines.firstOrNull { it.startsWith("SIG") && it.length <= 16 }
        val abort = lines.firstOrNull { it.contains("Abort message") || it.contains("Check failed") || it.contains("F0000") }
        val lib = lines.firstOrNull { it.endsWith(".so") && !it.contains("libc.so") && !it.contains("libart.so") }
            ?.substringAfterLast('/')
        return listOfNotNull(
            "Native crash",
            signal,
            abort?.take(140),
            lib?.let { "in $it" },
            description?.takeIf { it.isNotBlank() && it != "crash" }?.take(80),
        ).joinToString(" · ")
    }

    /** Assembles the shareable body. */
    fun body(
        title: String,
        timeMillis: Long,
        device: Map<String, String>,
        environment: Map<String, String>,
        details: String,
        recent: List<String>,
        persisted: String,
    ): String = buildString {
        append("RavanGo crash report\n")
        append(title).append('\n')
        append("Time: ").append(isoTime(timeMillis)).append('\n')
        append('\n').append("== Device & app ==\n")
        device.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        if (environment.isNotEmpty()) {
            append('\n').append("== Runtime ==\n")
            environment.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        }
        append('\n').append("== Details ==\n").append(details.trimEnd()).append('\n')
        if (recent.isNotEmpty()) {
            append('\n').append("== Recent warnings & errors ==\n")
            recent.takeLast(60).forEach { append(it).append('\n') }
        }
        if (persisted.isNotBlank()) {
            append('\n').append("== Diagnostics log ==\n").append(persisted.trimEnd()).append('\n')
        }
    }.let { if (it.length > MAX_BODY_CHARS) it.take(MAX_BODY_CHARS - 40) + "\n… (report truncated)\n" else it }

    /** True when a JAVA report written by the in-process handler already covers the system exit at [exitTime]. */
    fun coveredByJavaReport(exitTime: Long, reports: List<CrashReport>): Boolean =
        reports.any { it.kind == CrashKind.JAVA && it.timeMillis in (exitTime - 60_000)..(exitTime + 5_000) }

    /** Keeps the newest [max] reports; returns the ids to delete. */
    fun prune(reports: List<CrashReport>, max: Int): List<String> =
        reports.sortedByDescending { it.timeMillis }.drop(max).map { it.id }

    fun isoTime(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(timeMillis))
}
