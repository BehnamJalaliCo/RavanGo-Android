package com.ravango.feature.scripts.importing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportersTest {

    private val newParser = { KXmlParser() }

    @Test
    fun `srt keeps only spoken text`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            سلام <i>دوستان</i>

            2
            00:00:03,600 --> 00:00:05,000
            امروز می‌خواهیم
            درباره‌ی نور صحبت کنیم.

            3
            00:00:05,100 --> 00:00:07,000
            Let's start.
        """.trimIndent()
        assertThat(SubtitleText.extract(srt)).isEqualTo("سلام دوستان امروز می‌خواهیم درباره‌ی نور صحبت کنیم.\nLet's start.")
    }

    @Test
    fun `vtt skips header, notes, identifiers and cue settings`() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE this is a comment
            spanning lines

            intro
            00:01.000 --> 00:04.000 align:start position:10%
            <v Roger>Hello there.</v>

            00:04.500 --> 00:06.000
            Second cue
        """.trimIndent()
        assertThat(SubtitleText.extract(vtt)).isEqualTo("Hello there.\nSecond cue")
    }

    @Test
    fun `docx paragraphs, bold runs and headings are converted`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>مقدمه</w:t></w:r></w:p>
                <w:p>
                  <w:r><w:t xml:space="preserve">این یک </w:t></w:r>
                  <w:r><w:rPr><w:b/></w:rPr><w:t>متن</w:t></w:r>
                  <w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve"> مهم</w:t></w:r>
                  <w:r><w:t xml:space="preserve"> است.</w:t></w:r>
                </w:p>
                <w:p><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>Not bold</w:t><w:tab/><w:t>tabbed</w:t><w:br/><w:t>next line</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val text = DocxText(newParser).extract(zipOf("word/document.xml" to xml.toByteArray()))
        assertThat(text).isEqualTo("## مقدمه\nاین یک **متن مهم** است.\nNot bold\ttabbed\nnext line\n")
    }

    @Test
    fun `docx without document part is rejected`() {
        val result = runCatching { DocxText(newParser).extract(zipOf("other.xml" to "<a/>".toByteArray())) }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `rtf control words are stripped, unicode and code page escapes decoded, bold kept`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Arial;}}{\colortbl;\red0\green0\blue0;}""" +
            """{\*\generator Word;}\f0\fs24 Hello \b world\b0 !\par Caf\'e9 """ +
            // RTF \uN escapes (decimal code points) for "سلام", each followed by its ANSI fallback char.
            listOf(1587, 1604, 1575, 1605).joinToString("") { "\\" + "u$it?" } + "\\" + "par}"
        assertThat(RtfText.extract(rtf).trim()).isEqualTo("Hello **world**!\nCafé سلام")
    }

    @Test
    fun `html blocks become lines, bold becomes emphasis, entities decoded`() {
        val html = "<html><head><title>x</title><style>p{}</style></head><body><h1>Title</h1>" +
            "<p>One &amp; <strong>two</strong></p><p>سه&zwnj;تا<br>four&nbsp;&#1601;</p><script>alert(1)</script></body></html>"
        val text = TextDecoding.tidy(HtmlText.extract(html))
        assertThat(text).isEqualTo("Title\n\nOne & **two**\n\nسه‌تا\nfour ف")
    }

    @Test
    fun `charset detection honours BOMs, utf8 and legacy persian windows-1256`() {
        val persian = "سلام دنیا"
        assertThat(TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + persian.toByteArray())).isEqualTo(persian)
        assertThat(TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + persian.toByteArray(Charsets.UTF_16LE))).isEqualTo(persian)
        assertThat(TextDecoding.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + persian.toByteArray(Charsets.UTF_16BE))).isEqualTo(persian)
        assertThat(TextDecoding.decode("hello world".toByteArray(Charsets.UTF_16LE))).isEqualTo("hello world")
        assertThat(TextDecoding.decode(persian.toByteArray())).isEqualTo(persian)
        // Windows-1256 has no Persian yeh/keheh; legacy files use the Arabic letter forms.
        val legacy = "سلام بر شما"
        assertThat(TextDecoding.decode(legacy.toByteArray(charset("windows-1256")))).isEqualTo(legacy)
    }

    @Test
    fun `format detection prefers extension then mime`() {
        assertThat(ScriptImport.detect("talk.SRT", "application/octet-stream")).isEqualTo(ImportFormat.SUBTITLE)
        assertThat(ScriptImport.detect("doc.docx", null)).isEqualTo(ImportFormat.DOCX)
        assertThat(ScriptImport.detect("file.pdf", "application/pdf")).isEqualTo(ImportFormat.PDF)
        assertThat(ScriptImport.detect(null, "text/plain")).isEqualTo(ImportFormat.TEXT)
        assertThat(ScriptImport.detect("x.bin", "application/octet-stream")).isEqualTo(ImportFormat.UNSUPPORTED)
    }

    @Test
    fun `markdown headings become sections and links are flattened`() {
        val md = "# Intro\nSome [link](https://x.y) and __bold__ and `code`\n- item"
        assertThat(ScriptImport.markdownToScript(md)).isEqualTo("## Intro\nSome link and **bold** and code\n• item")
    }

    @Test
    fun `title from file name`() {
        assertThat(ScriptImport.titleFrom("my_video__script.final.docx")).isEqualTo("my video script.final")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): java.io.InputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray().inputStream()
    }
}
