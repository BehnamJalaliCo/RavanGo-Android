package com.ravango.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MediaInfo(
    val uri: String,
    val kind: MediaKind,
    val mimeType: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameRate: Float,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val sizeBytes: Long,
    val displayName: String?,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
) {
    /** Width/height after applying rotation metadata. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    fun toMediaSource(assetId: String? = null) = MediaSource(
        uri = uri, kind = kind, durationUs = durationUs, width = displayWidth, height = displayHeight,
        rotationDegrees = 0, hasAudio = hasAudio, frameRate = frameRate, assetId = assetId,
    )
}

/** Reads container metadata for local files and content URIs. */
@Singleton
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun probe(uriString: String): MediaInfo? = withContext(io) { runCatching { probeBlocking(uriString) }.getOrNull() }

    fun probeBlocking(uriString: String): MediaInfo {
        val uri = uriString.toUri()
        val mime = mimeTypeOf(uri) ?: ""
        if (mime.startsWith("image/")) return probeImage(uriString, uri, mime)

        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever, uri)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fpsMeta = if (android.os.Build.VERSION.SDK_INT >= 28) retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() else null
            val (fps, sampleRate, channels) = extractorDetails(uri)
            return MediaInfo(
                uri = uriString,
                kind = if (hasVideo) MediaKind.VIDEO else MediaKind.AUDIO,
                mimeType = mime.ifEmpty { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "" },
                durationUs = durationMs * 1000,
                width = width,
                height = height,
                rotationDegrees = rotation,
                frameRate = fps.takeIf { it > 0 } ?: fpsMeta ?: 30f,
                hasAudio = hasAudio,
                hasVideo = hasVideo,
                sizeBytes = sizeOf(uri),
                displayName = displayNameOf(uri),
                audioSampleRate = sampleRate,
                audioChannels = channels,
            )
        } finally {
            retriever.release()
        }
    }

    private fun probeImage(uriString: String, uri: Uri, mime: String): MediaInfo {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        return MediaInfo(uriString, MediaKind.IMAGE, mime, 0, opts.outWidth, opts.outHeight, 0, 0f, hasAudio = false, hasVideo = false, sizeBytes = sizeOf(uri), displayName = displayNameOf(uri))
    }

    private fun extractorDetails(uri: Uri): Triple<Float, Int, Int> {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme == null || uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            var fps = 0f; var sr = 0; var ch = 0
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                if (m.startsWith("audio/")) {
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            Triple(fps, sr, ch)
        } catch (e: Exception) {
            Triple(0f, 0, 0)
        } finally {
            extractor.release()
        }
    }

    private fun setDataSource(retriever: MediaMetadataRetriever, uri: Uri) {
        if (uri.scheme == null || uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
    }

    fun mimeTypeOf(uri: Uri): String? = if (uri.scheme == "content") {
        context.contentResolver.getType(uri)
    } else {
        val ext = uri.path?.substringAfterLast('.', "")?.lowercase()
        when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> null
        }
    }

    private fun sizeOf(uri: Uri): Long = if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    } else {
        uri.path?.let { File(it).length() } ?: 0L
    }

    private fun displayNameOf(uri: Uri): String? = if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    } else {
        uri.lastPathSegment
    }
}

/** Parses "content://…", "file://…" or a bare absolute path. */
fun String.toUri(): Uri = if (startsWith("/")) Uri.fromFile(File(this)) else Uri.parse(this)
