package com.ravango.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.media.MediaProbe
import com.ravango.core.media.MediaStorePublisher
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.Project
import com.ravango.core.model.newId
import com.ravango.core.common.result.ErrorKind
import com.ravango.engine.camera.CameraEngine
import com.ravango.engine.camera.CameraEvent
import com.ravango.engine.camera.RecordedMedia
import com.ravango.engine.camera.recovery.RecoveredRecording
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Where a finished take should go. */
data class SaveTarget(
    val projectId: String?,
    val scriptId: String?,
    val templateId: String?,
    val aspectRatio: AspectRatioSpec,
)

/** Progress of saving the most recent take. */
sealed interface SaveEvent {
    data object Saving : SaveEvent
    data class Saved(val take: SavedTake) : SaveEvent
    data class Failed(val kind: ErrorKind) : SaveEvent
}

data class SavedTake(
    val projectId: String,
    val assetId: String,
    val file: File,
    val mimeType: String,
    val durationUs: Long,
    val publishedUri: String?,
    val thumbnailPath: String?,
    val isAudio: Boolean,
)

/**
 * Turns a finished recording into a project asset: creates/updates the project, probes the file, writes a
 * thumbnail, and publishes to the gallery when the user wants that. Runs in the application scope so leaving
 * Camera Studio mid-save never loses a take.
 */
@Singleton
class RecordingSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projects: ProjectRepository,
    private val preferences: PreferencesDataSource,
    private val probe: MediaProbe,
    private val publisher: MediaStorePublisher,
    engine: CameraEngine,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Where the next take goes; set by Camera Studio. Takes after the first go to the same project. */
    @Volatile var target: SaveTarget? = null

    private val _events = MutableSharedFlow<SaveEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SaveEvent> = _events.asSharedFlow()

    init {
        // Every finished recording is saved here — in the app scope — whatever happened to the screen that started it
        // (lifecycle stop, back navigation, thermal or low-storage auto-stop).
        appScope.launch {
            engine.events.filterIsInstance<CameraEvent.RecordingFinished>().collect { event ->
                _events.emit(SaveEvent.Saving)
                val t = target ?: SaveTarget(null, null, null, event.media.aspectRatio ?: AspectRatioSpec.Portrait9x16)
                try {
                    val take = withContext(io) { saveBlocking(event.media, t, recovered = false) }
                    target = t.copy(projectId = take.projectId)
                    _events.emit(SaveEvent.Saved(take))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RgLog.e(TAG, "saving the take failed", e)
                    _events.emit(SaveEvent.Failed(if (e is java.io.IOException) ErrorKind.STORAGE_FULL else ErrorKind.UNKNOWN))
                }
            }
        }
    }

    suspend fun saveRecovered(recording: RecoveredRecording): SavedTake? = appScope.async(io) {
        val file = File(recording.path)
        if (!file.exists()) return@async null
        val media = RecordedMedia(
            file = file,
            captureMode = recording.captureMode,
            mimeType = if (recording.captureMode == CaptureMode.AUDIO_ONLY) "audio/mp4" else "video/mp4",
            durationUs = recording.durationUs,
            width = recording.width,
            height = recording.height,
            frameRate = recording.frameRate,
            sizeBytes = file.length(),
            hasAudio = recording.hasAudio,
            aspectRatio = recording.aspectRatio,
            createdAt = recording.createdAt,
        )
        runCatching { saveBlocking(media, SaveTarget(null, null, null, recording.aspectRatio), recovered = true) }
            .onFailure { RgLog.e(TAG, "import of recovered recording failed", it) }
            .getOrNull()
    }.await()

    private suspend fun saveBlocking(media: RecordedMedia, target: SaveTarget, recovered: Boolean): SavedTake {
        val isAudio = media.captureMode == CaptureMode.AUDIO_ONLY
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.createdAt))
        val titleRes = if (recovered) R.string.camera_recovered_title else R.string.camera_recording_title
        val aspect = media.aspectRatio ?: target.aspectRatio
        var project: Project = target.projectId?.let { projects.getProject(it) }
            ?: projects.create(context.getString(titleRes, date), aspect, target.scriptId, target.templateId)

        val uri = Uri.fromFile(media.file).toString()
        val info = probe.probe(uri)
        val thumbnail = if (isAudio) null else writeThumbnail(media.file, project.id)

        val publishedUri = if (preferences.currentUserPreferences().saveToGallery) {
            runCatching {
                if (isAudio) publisher.publishAudio(media.file) else publisher.publishVideo(media.file)
            }.onFailure { RgLog.w(TAG, "gallery publish failed", it) }.getOrNull()?.toString()
        } else {
            null
        }

        val asset = MediaAsset(
            id = newId(),
            projectId = project.id,
            uri = uri,
            kind = if (isAudio) MediaKind.AUDIO else MediaKind.VIDEO,
            origin = MediaOrigin.RECORDED,
            mimeType = info?.mimeType?.takeIf { it.isNotBlank() } ?: media.mimeType,
            durationUs = info?.durationUs?.takeIf { it > 0 } ?: media.durationUs,
            width = info?.displayWidth?.takeIf { it > 0 } ?: media.width,
            height = info?.displayHeight?.takeIf { it > 0 } ?: media.height,
            frameRate = info?.frameRate?.takeIf { it > 0f } ?: media.frameRate.toFloat(),
            sizeBytes = media.file.length(),
            hasAudio = info?.hasAudio ?: media.hasAudio,
            publishedUri = publishedUri,
            createdAt = System.currentTimeMillis(),
        )
        projects.addAsset(asset)

        project = project.copy(
            thumbnailPath = project.thumbnailPath?.takeIf { File(it).exists() } ?: thumbnail,
            durationUs = if (project.durationUs == 0L) asset.durationUs else project.durationUs,
        )
        projects.update(project)
        return SavedTake(project.id, asset.id, media.file, asset.mimeType, asset.durationUs, publishedUri, thumbnail ?: project.thumbnailPath, isAudio)
    }

    /** Frame at 0.5 s, scaled to ≤ 720 px, as JPEG in app storage. */
    private suspend fun writeThumbnail(video: File, projectId: String): String? = withContext(io) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return@withContext null
            val scale = 720f / max(frame.width, frame.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
            } else {
                frame
            }
            val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val out = File(dir, "${projectId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (bitmap !== frame) bitmap.recycle()
            frame.recycle()
            out.absolutePath
        } catch (e: Exception) {
            RgLog.w(TAG, "thumbnail failed", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "RecordingSaver"
    }
}
