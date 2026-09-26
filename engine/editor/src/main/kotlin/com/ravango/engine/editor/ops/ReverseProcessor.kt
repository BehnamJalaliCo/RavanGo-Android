package com.ravango.engine.editor.ops

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.media.toUri
import com.ravango.core.model.VideoClip
import com.ravango.engine.editor.export.EditorError
import com.ravango.engine.editor.export.EditorException
import com.ravango.engine.editor.export.runTransformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Produces a reversed rendition of a clip's trim window.
 *
 * 1. Transcode the trimmed segment with [Transformer] to an all-intra H.264 file (I-frame interval 0 via
 *    [VideoEncoderSettings]) with audio removed, downscaled to at most 1080p to bound the intra bitrate.
 * 2. Read the samples in reverse order with [MediaExtractor] (a seek per sync sample — every sample is one) and remux
 *    them with [MediaMuxer], rewriting timestamps so the last frame comes first.
 *
 * Results are cached per (source, trim window) in the project's `reversed/` directory.
 */
@Singleton
class ReverseProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val files: ProjectFiles,
) {
    /** Returns the `file://` URI of the reversed rendition. Throws [EditorException] on failure. */
    suspend fun reverse(projectId: String, clip: VideoClip, onProgress: (Float) -> Unit): String {
        val key = cacheKey(clip)
        val out = File(files.reversedDir(projectId), "rev_$key.mp4")
        if (out.exists() && out.length() > 0) return ProjectFiles.uriOf(out)
        val intra = File(files.tempDir(), "intra_$key.mp4")
        try {
            transcodeAllIntra(clip, intra) { onProgress(it * 0.75f) }
            withContext(io) { remuxReversed(intra, File(out.path + ".tmp")) { onProgress(0.75f + it * 0.25f) } }
            File(out.path + ".tmp").renameTo(out)
            onProgress(1f)
            return ProjectFiles.uriOf(out)
        } finally {
            intra.delete()
            File(out.path + ".tmp").delete()
        }
    }

    fun cachedReverse(projectId: String, clip: VideoClip): String? {
        val out = File(files.reversedDir(projectId), "rev_${cacheKey(clip)}.mp4")
        return if (out.exists() && out.length() > 0) ProjectFiles.uriOf(out) else null
    }

    private fun cacheKey(clip: VideoClip): String =
        Integer.toHexString("${clip.source.uri}|${clip.trimStartUs}|${clip.trimEndUs}".hashCode()) + "_" + (clip.trimEndUs - clip.trimStartUs) / 1000

    private suspend fun transcodeAllIntra(clip: VideoClip, output: File, onProgress: (Float) -> Unit) {
        val mediaItem = MediaItem.Builder()
            .setUri(clip.source.uri.toUri())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionUs(clip.trimStartUs).setEndPositionUs(clip.trimEndUs).build(),
            ).build()
        val shortSide = minOf(clip.source.width, clip.source.height).takeIf { it > 0 } ?: MAX_SHORT_SIDE
        val videoEffects = if (shortSide > MAX_SHORT_SIDE) listOf(Presentation.createForShortSide(MAX_SHORT_SIDE)) else emptyList()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        val pixels = minOf(shortSide, MAX_SHORT_SIDE).let { s -> s.toLong() * s * 16 / 9 }
        val bitrate = (pixels * 30 * 0.35).toLong().coerceIn(8_000_000, 40_000_000).toInt()
        val settings = VideoEncoderSettings.Builder()
            .setiFrameIntervalSeconds(0f)
            .setBitrate(bitrate)
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build()).build()
        runTransformer(
            create = {
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings).setEnableFallback(true).build())
                    .build()
            },
            composition = composition,
            outputPath = output.absolutePath,
            onProgress = onProgress,
        )
    }

    private suspend fun remuxReversed(input: File, output: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw EditorException(EditorError.PROCESSING, "No video track in intra file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            // Pass 1: sample table.
            val times = ArrayList<Long>(1024)
            var syncCount = 0
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                times += t
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncCount++
                extractor.advance()
            }
            if (times.isEmpty()) throw EditorException(EditorError.PROCESSING, "Empty intra file")
            if (syncCount < times.size * 0.98) {
                RgLog.w(TAG, "Encoder ignored all-intra request: $syncCount/${times.size} sync samples")
                throw EditorException(EditorError.NOT_ALL_INTRA)
            }
            times.sort()

            val rotation = MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(input.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally {
                    r.release()
                }
            }
            val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            m.setOrientationHint(rotation)
            val outTrack = m.addTrack(format)
            m.start()
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            var buffer = ByteBuffer.allocateDirect(capacity.coerceAtLeast(256 * 1024))
            val info = MediaCodec.BufferInfo()
            val last = times.last()
            for (i in times.indices.reversed()) {
                coroutineContext.ensureActive()
                val t = times[i]
                extractor.seekTo(t, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (extractor.sampleTime != t) extractor.seekTo(t, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val needed = extractor.sampleSize
                    if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect((needed * 3 / 2).toInt())
                }
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    // Buffer too small (API < 28 has no sampleSize): grow and retry once.
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 4)
                    extractor.readSampleData(buffer, 0)
                }
                if (size < 0) continue
                info.set(0, size, last - t, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buffer, info)
                if (i % 15 == 0) onProgress(1f - i.toFloat() / times.size)
            }
            m.stop()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "Reverse"
        const val MAX_SHORT_SIDE = 1080
    }
}
