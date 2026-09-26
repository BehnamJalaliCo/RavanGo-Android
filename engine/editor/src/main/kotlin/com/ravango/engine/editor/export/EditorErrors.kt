package com.ravango.engine.editor.export

import androidx.media3.transformer.ExportException
import com.ravango.core.common.result.ErrorKind
import java.io.FileNotFoundException
import java.io.IOException

/** User-facing categories of editor/export failures; the UI maps each to a localized message. */
enum class EditorError {
    ENCODER_UNSUPPORTED,
    DECODER_UNSUPPORTED,
    SOURCE_MISSING,
    PERMISSION,
    STORAGE_FULL,
    PROCESSING,
    AUDIO_PROCESSING,
    MUXING,
    NOT_ALL_INTRA,
    CANCELLED,
    UNKNOWN,
}

class EditorException(val error: EditorError, message: String? = null, cause: Throwable? = null) : Exception(message ?: error.name, cause)

/** Classifies any failure from Transformer/MediaCodec/IO into an [EditorError]. */
fun Throwable.toEditorError(): EditorError = when (this) {
    is EditorException -> error
    is ExportException -> when (errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_ENCODING_FAILED -> EditorError.ENCODER_UNSUPPORTED
        ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_DECODING_FAILED -> EditorError.DECODER_UNSUPPORTED
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> EditorError.SOURCE_MISSING
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> EditorError.PERMISSION
        ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> EditorError.PROCESSING
        ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> EditorError.AUDIO_PROCESSING
        ExportException.ERROR_CODE_MUXING_FAILED, ExportException.ERROR_CODE_MUXING_TIMEOUT, ExportException.ERROR_CODE_MUXING_APPEND -> EditorError.MUXING
        else -> if (hasNoSpaceCause()) EditorError.STORAGE_FULL else EditorError.UNKNOWN
    }
    is FileNotFoundException -> EditorError.SOURCE_MISSING
    is SecurityException -> EditorError.PERMISSION
    is IOException -> if (hasNoSpaceCause()) EditorError.STORAGE_FULL else EditorError.UNKNOWN
    is kotlinx.coroutines.CancellationException -> EditorError.CANCELLED
    else -> if (hasNoSpaceCause()) EditorError.STORAGE_FULL else EditorError.UNKNOWN
}

fun EditorError.toErrorKind(): ErrorKind = when (this) {
    EditorError.ENCODER_UNSUPPORTED, EditorError.DECODER_UNSUPPORTED, EditorError.NOT_ALL_INTRA -> ErrorKind.NOT_SUPPORTED
    EditorError.PERMISSION -> ErrorKind.PERMISSION
    EditorError.STORAGE_FULL -> ErrorKind.STORAGE_FULL
    EditorError.SOURCE_MISSING -> ErrorKind.INVALID_INPUT
    EditorError.CANCELLED -> ErrorKind.CANCELLED
    else -> ErrorKind.UNKNOWN
}

private fun Throwable.hasNoSpaceCause(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 8) {
        val m = t.message ?: ""
        if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
        t = t.cause
    }
    return false
}
