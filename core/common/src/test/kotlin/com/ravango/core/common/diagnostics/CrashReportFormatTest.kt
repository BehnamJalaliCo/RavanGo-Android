package com.ravango.core.common.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashReportFormatTest {

    private fun report(kind: CrashKind, time: Long, body: String = "body") =
        CrashReport(CrashReportFormat.fileName(kind, time), kind, time, "summary $time", body)

    @Test
    fun `encode and decode round trip`() {
        val original = report(CrashKind.NATIVE, 1_700_000_000_123L, body = "line 1\n---\nline 3\n")
        val decoded = CrashReportFormat.decode(original.id, CrashReportFormat.encode(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decode rejects text that is not a report`() {
        assertThat(CrashReportFormat.decode("x", "hello")).isNull()
        assertThat(CrashReportFormat.decode("x", "kind=NOPE\ntime=1\nsummary=s\n---\nbody")).isNull()
        assertThat(CrashReportFormat.decode("x", "kind=JAVA\ntime=abc\nsummary=s\n---\nbody")).isNull()
    }

    @Test
    fun `summary line breaks never break the header`() {
        val r = CrashReport("id", CrashKind.JAVA, 5L, "a\nb", "body")
        assertThat(CrashReportFormat.decode("id", CrashReportFormat.encode(r))!!.summary).isEqualTo("a b")
    }

    @Test
    fun `summarize names the root cause and the first app frame`() {
        val root = IllegalStateException("eglMakeCurrent failed: 0x300d\nmore")
        root.stackTrace = arrayOf(
            StackTraceElement("android.opengl.EGL14", "eglMakeCurrent", "EGL14.java", -2),
            StackTraceElement("com.ravango.engine.render.EglCore", "makeCurrent", "EglCore.kt", 79),
        )
        val wrapper = RuntimeException("wrapped", root)
        assertThat(CrashReportFormat.summarize(wrapper))
            .isEqualTo("IllegalStateException: eglMakeCurrent failed: 0x300d (EglCore.kt:79)")
    }

    @Test
    fun `printable strings extract readable runs from a binary tombstone`() {
        val bytes = byteArrayOf(0, 1, 2) + "SIGSEGV".toByteArray() + byteArrayOf(0, -1) +
            "/data/app/lib/arm64/libmediapipe_tasks_jni.so".toByteArray() + byteArrayOf(7) + "ab".toByteArray()
        val strings = CrashReportFormat.printableStrings(bytes)
        assertThat(strings.lines()).containsAtLeast("SIGSEGV", "/data/app/lib/arm64/libmediapipe_tasks_jni.so")
        assertThat(strings).doesNotContain("ab\n")
        assertThat(CrashReportFormat.nativeSummary("crash", strings))
            .isEqualTo("Native crash · SIGSEGV · in libmediapipe_tasks_jni.so")
    }

    @Test
    fun `printable strings respect the size cap`() {
        val bytes = ByteArray(100_000) { 'a'.code.toByte() }
        assertThat(CrashReportFormat.printableStrings(bytes, maxChars = 1_000).length).isAtMost(1_000)
    }

    @Test
    fun `a system exit already covered by the in-process report is not imported twice`() {
        val java = report(CrashKind.JAVA, 10_000_000L)
        assertThat(CrashReportFormat.coveredByJavaReport(10_000_400L, listOf(java))).isTrue()
        assertThat(CrashReportFormat.coveredByJavaReport(20_000_000L, listOf(java))).isFalse()
        assertThat(CrashReportFormat.coveredByJavaReport(10_000_400L, listOf(report(CrashKind.NATIVE, 10_000_000L)))).isFalse()
    }

    @Test
    fun `prune keeps the newest reports`() {
        val reports = (1..5).map { report(CrashKind.JAVA, it * 1000L) }
        assertThat(CrashReportFormat.prune(reports, 3)).containsExactly(reports[0].id, reports[1].id)
    }

    @Test
    fun `body contains every section and is capped`() {
        val body = CrashReportFormat.body(
            title = "Kind: test",
            timeMillis = 0L,
            device = mapOf("Device" to "Samsung SM-A546E"),
            environment = mapOf("gl.renderer" to "ARM Mali-G68"),
            details = "trace",
            recent = listOf("W/Tag: warn"),
            persisted = "E/GL: shader failed",
        )
        assertThat(body).contains("Samsung SM-A546E")
        assertThat(body).contains("gl.renderer: ARM Mali-G68")
        assertThat(body).contains("W/Tag: warn")
        assertThat(body).contains("E/GL: shader failed")
        val huge = CrashReportFormat.body("t", 0L, emptyMap(), emptyMap(), "x".repeat(200_000), emptyList(), "")
        assertThat(huge.length).isAtMost(CrashReportFormat.MAX_BODY_CHARS)
        assertThat(huge).endsWith("(report truncated)\n")
    }
}

class CpuRangesTest {
    @Test
    fun `parses kernel cpu lists`() {
        assertThat(com.ravango.core.common.device.CpuRanges.count("0-7\n")).isEqualTo(8)
        assertThat(com.ravango.core.common.device.CpuRanges.count("0-3,6")).isEqualTo(5)
        assertThat(com.ravango.core.common.device.CpuRanges.count("0")).isEqualTo(1)
        assertThat(com.ravango.core.common.device.CpuRanges.count("garbage")).isEqualTo(0)
    }
}

class DiagnosticsTest {

    @Test
    fun `identical events are rate limited and recent keeps the order`() {
        val tag = "Test-${System.nanoTime()}"
        Diagnostics.note('W', tag, "first")
        Diagnostics.note('W', tag, "first")
        Diagnostics.note('W', tag, "second")
        val mine = Diagnostics.recent().filter { it.contains(tag) }
        assertThat(mine).hasSize(2)
        assertThat(mine[0]).endsWith("first")
        assertThat(mine[1]).endsWith("second")
    }

    @Test
    fun `environment is sorted and bounded`() {
        Diagnostics.setEnv("zz.test", "x".repeat(1_000))
        Diagnostics.setEnv("aa.test", "y")
        val env = Diagnostics.environment()
        assertThat(env["zz.test"]!!.length).isEqualTo(300)
        assertThat(env.keys.indexOf("aa.test")).isLessThan(env.keys.indexOf("zz.test"))
    }
}
