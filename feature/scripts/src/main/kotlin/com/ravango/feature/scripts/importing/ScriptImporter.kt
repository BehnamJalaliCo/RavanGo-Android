package com.ravango.feature.scripts.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import javax.inject.Inject

enum class ImportFormat { TEXT, MARKDOWN, SUBTITLE, DOCX, RTF, HTML, PDF, UNSUPPORTED }

enum class ImportError { PDF_UNSUPPORTED, UNSUPPORTED_TYPE, EMPTY, TOO_LARGE, READ_FAILED }

sealed interface ImportResult {
    data class Success(val title: String, val body: String) : ImportResult
    data class Failure(val error: ImportError) : ImportResult
}

/** Pure import logic (format detection + parsing), shared by the Android importer and unit tests. */
object ScriptImport {
    const val MAX_BYTES = 10L * 1024 * 1024

    /** MIME types offered in the system file picker. */
    val PICKER_MIME_TYPES = arrayOf(
        "text/*",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/rtf",
        "application/x-subrip",
        "application/x-rtf",
        "application/pdf",
        "application/octet-stream",
    )

    fun detect(fileName: String?, mime: String?): ImportFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        when (ext) {
            "txt", "text" -> return ImportFormat.TEXT
            "md", "markdown" -> return ImportFormat.MARKDOWN
            "srt", "vtt" -> return ImportFormat.SUBTITLE
            "docx" -> return ImportFormat.DOCX
            "rtf" -> return ImportFormat.RTF
            "html", "htm", "xhtml" -> return ImportFormat.HTML
            "pdf" -> return ImportFormat.PDF
        }
        val m = mime?.lowercase().orEmpty()
        return when {
            m == "application/pdf" -> ImportFormat.PDF
            m.contains("wordprocessingml") -> ImportFormat.DOCX
            m.contains("rtf") -> ImportFormat.RTF
            m == "text/html" || m == "application/xhtml+xml" -> ImportFormat.HTML
            m == "application/x-subrip" || m == "text/vtt" -> ImportFormat.SUBTITLE
            m == "text/markdown" -> ImportFormat.MARKDOWN
            m.startsWith("text/") -> ImportFormat.TEXT
            else -> ImportFormat.UNSUPPORTED
        }
    }

    /** Converts file bytes of [format] into script text (RavanGo markup where formatting maps cleanly). */
    fun parse(format: ImportFormat, bytes: ByteArray, newParser: () -> XmlPullParser): String = when (format) {
        ImportFormat.TEXT -> TextDecoding.decode(bytes)
        ImportFormat.MARKDOWN -> markdownToScript(TextDecoding.decode(bytes))
        ImportFormat.SUBTITLE -> SubtitleText.extract(TextDecoding.decode(bytes))
        ImportFormat.DOCX -> DocxText(newParser).extract(ByteArrayInputStream(bytes))
        ImportFormat.RTF -> RtfText.extract(TextDecoding.decode(bytes))
        ImportFormat.HTML -> HtmlText.extract(TextDecoding.decode(bytes))
        ImportFormat.PDF, ImportFormat.UNSUPPORTED -> ""
    }.let(TextDecoding::tidy)

    /** Markdown headings become sections; bold stays `**…**`; list markers and other syntax are simplified. */
    fun markdownToScript(md: String): String = md.lines().joinToString("\n") { line ->
        val heading = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""").find(line)
        when {
            heading != null -> "## " + heading.groupValues[1]
            else -> line
                .replace(Regex("""^\s*[-*+]\s+"""), "• ")
                .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace(Regex("""(?<![*\w])__(.+?)__(?!\w)"""), "**$1**")
                .replace(Regex("""`([^`]+)`"""), "$1")
        }
    }

    /** A title from the file name: extension removed, separators turned into spaces. */
    fun titleFrom(fileName: String?): String = fileName.orEmpty()
        .substringBeforeLast('.')
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
}

/** Reads a document chosen with the Storage Access Framework and converts it into script text. */
class ScriptImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun import(uri: Uri): ImportResult = withContext(io) {
        try {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
                    size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong) ?: -1L
                }
            }
            if (size > ScriptImport.MAX_BYTES) return@withContext ImportResult.Failure(ImportError.TOO_LARGE)
            val format = ScriptImport.detect(name, resolver.getType(uri))
            when (format) {
                ImportFormat.PDF -> return@withContext ImportResult.Failure(ImportError.PDF_UNSUPPORTED)
                ImportFormat.UNSUPPORTED -> return@withContext ImportResult.Failure(ImportError.UNSUPPORTED_TYPE)
                else -> Unit
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > ScriptImport.MAX_BYTES) return@withContext ImportResult.Failure(ImportError.TOO_LARGE)
                }
                buffer.toByteArray()
            } ?: return@withContext ImportResult.Failure(ImportError.READ_FAILED)
            val body = ScriptImport.parse(format, bytes) { Xml.newPullParser() }
            if (body.isBlank()) return@withContext ImportResult.Failure(ImportError.EMPTY)
            ImportResult.Success(ScriptImport.titleFrom(name), body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w("ScriptImport", "import failed", e)
            ImportResult.Failure(ImportError.READ_FAILED)
        }
    }
}
