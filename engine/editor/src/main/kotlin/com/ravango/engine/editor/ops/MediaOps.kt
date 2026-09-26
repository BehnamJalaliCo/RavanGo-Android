package com.ravango.engine.editor.ops

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Transformer
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.media.toUri
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import com.ravango.core.model.Transition
import com.ravango.core.model.VideoClip
import com.ravango.core.model.newId
import com.ravango.engine.editor.export.EditorError
import com.ravango.engine.editor.export.EditorException
import com.ravango.engine.editor.export.runTransformer
import com.ravango.engine.editor.timeline.TimelineMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Freeze frames and audio extraction. */
@Singleton
class MediaOps @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val files: ProjectFiles,
) {
    /**
     * Grabs the exact frame at [sourceUs] ([MediaMetadataRetriever.OPTION_CLOSEST]) and stores it as a PNG still.
     */
    suspend fun freezeFrame(projectId: String, clip: VideoClip, sourceUs: Long): MediaSource = withContext(io) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = clip.source.uri.toUri()
            if (uri.scheme == null || uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            val frame: Bitmap = retriever.getFrameAtTime(sourceUs.coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw EditorException(EditorError.DECODER_UNSUPPORTED, "No frame at $sourceUs")
            val out = File(files.stillsDir(projectId), "freeze_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaSource(
                uri = ProjectFiles.uriOf(out),
                kind = MediaKind.IMAGE,
                durationUs = 0,
                width = frame.width,
                height = frame.height,
                hasAudio = false,
            ).also { frame.recycle() }
        } finally {
            retriever.release()
        }
    }

    /**
     * Inserts a still of [still] for [durationUs] at the playhead, inheriting the clip's look (crop, rotation,
     * transform, grade) so it continues the shot seamlessly.
     */
    fun insertFreezeFrame(doc: EditorDocument, atUs: Long, still: MediaSource, durationUs: Long): EditResult {
        val p = TimelineMath.clipAt(doc, atUs)
        val base = p?.clip
        val clip = VideoClip(
            id = newId(),
            source = still,
            stillDurationUs = durationUs.coerceIn(TimelineMath.MIN_CLIP_US, EditOps.MAX_STILL_US),
            crop = base?.crop ?: com.ravango.core.model.CropRect(),
            // The PNG is saved upright from the source orientation; keep the user's turns/flips.
            rotationQuarterTurns = base?.rotationQuarterTurns ?: 0,
            flipHorizontal = base?.flipHorizontal ?: false,
            flipVertical = base?.flipVertical ?: false,
            transform = base?.transform ?: com.ravango.core.model.Transform2D(),
            filter = base?.filter ?: com.ravango.core.model.FilterPreset.NONE,
            filterIntensity = base?.filterIntensity ?: 1f,
            adjustments = base?.adjustments ?: com.ravango.core.model.ColorAdjustments(),
            transitionOut = Transition(),
        )
        return EditOps.insertClips(doc, listOf(clip), atUs)
    }

    /** Extracts the audio track of [source] to an AAC .m4a with Transformer (video removed). Cached per source. */
    suspend fun extractAudio(projectId: String, source: MediaSource, onProgress: (Float) -> Unit): MediaSource {
        val key = Integer.toHexString(source.uri.hashCode())
        val out = File(files.audioDir(projectId), "extracted_$key.m4a")
        if (!out.exists() || out.length() == 0L) {
            val tmp = File(out.path + ".tmp.m4a")
            try {
                val item = EditedMediaItem.Builder(MediaItem.fromUri(source.uri.toUri())).setRemoveVideo(true).build()
                runTransformer(
                    create = { Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).build() },
                    composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build(),
                    outputPath = tmp.absolutePath,
                    onProgress = onProgress,
                )
                tmp.renameTo(out)
            } finally {
                tmp.delete()
            }
        }
        return MediaSource(uri = ProjectFiles.uriOf(out), kind = MediaKind.AUDIO, durationUs = source.durationUs, hasAudio = true)
    }
}
