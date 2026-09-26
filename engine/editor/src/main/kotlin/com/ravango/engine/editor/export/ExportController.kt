package com.ravango.engine.editor.export

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.ravango.core.common.device.StorageInfo
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.media.MediaStorePublisher
import com.ravango.core.media.toUri
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.ProFeature
import com.ravango.core.model.ProjectStatus
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.engine.editor.composition.BuildOptions
import com.ravango.engine.editor.composition.CompositionBuilder
import com.ravango.engine.editor.ops.ProjectFiles
import com.ravango.engine.editor.ops.ReverseProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What to export. [srt] is the sidecar content (built by the caller's SubtitleBuilder) or null. */
data class ExportRequest(
    val projectId: String,
    val title: String,
    val document: EditorDocument,
    val settings: ExportSettings,
    val srt: String? = null,
    val publishToGallery: Boolean = true,
)

enum class PrepareStep { REVERSING, BUILDING }

sealed interface ExportState {
    data object Idle : ExportState
    data class Preparing(val projectId: String, val step: PrepareStep, val progress: Float) : ExportState
    data class Running(val projectId: String, val progress: Float, val startedAtMs: Long) : ExportState
    data class Succeeded(
        val projectId: String,
        val file: File,
        val galleryUri: String?,
        val sizeBytes: Long,
        val durationUs: Long,
        val width: Int,
        val height: Int,
        val srtFile: File?,
        val codecFallback: Boolean,
    ) : ExportState
    data class Failed(val projectId: String, val error: EditorError, val detail: String?) : ExportState
    data class Cancelled(val projectId: String) : ExportState

    val isActive: Boolean get() = this is Preparing || this is Running
}

/**
 * Owns the (single) running export. The job runs in the application scope and a foreground [ExportService] keeps the
 * process alive with a progress notification, so an export survives leaving the editor or the app.
 */
@Singleton
class ExportController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val builder: CompositionBuilder,
    private val reverse: ReverseProcessor,
    private val files: ProjectFiles,
    private val storage: StorageInfo,
    private val projects: ProjectRepository,
    private val publisher: MediaStorePublisher,
    private val entitlements: EntitlementProvider,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var job: Job? = null

    /** True when the device has an encoder for [codec]. */
    fun isCodecSupported(codec: VideoCodec): Boolean = runCatching { EncoderUtil.getSupportedEncoders(codec.mimeType).isNotEmpty() }.getOrDefault(codec == VideoCodec.H264)

    fun start(request: ExportRequest) {
        if (_state.value.isActive) return
        _state.value = ExportState.Preparing(request.projectId, PrepareStep.BUILDING, 0f)
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
            .onFailure { RgLog.w(TAG, "Could not start export service; exporting in-process", it) }
        job = scope.launch { run(request) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished/failed state once the UI has shown it. */
    fun acknowledge() {
        if (!_state.value.isActive) _state.value = ExportState.Idle
    }

    private suspend fun run(request: ExportRequest) {
        val pid = request.projectId
        var output: File? = null
        try {
            val ent = entitlements.entitlements.value
            var settings = ExportMath.clamp(request.settings, ent)
            var codecFallback = false
            if (settings.codec == VideoCodec.HEVC && !isCodecSupported(VideoCodec.HEVC)) {
                settings = settings.copy(codec = VideoCodec.H264)
                codecFallback = true
            }
            // 1. Make sure every reversed clip has its rendition.
            var doc = request.document
            val toReverse = doc.mainTrack.filter { it.reversed && (it.reversedUri == null || !exists(it.reversedUri)) }
            toReverse.forEachIndexed { i, clip ->
                _state.value = ExportState.Preparing(pid, PrepareStep.REVERSING, i.toFloat() / toReverse.size)
                val uri = reverse.reverse(pid, clip) { p -> _state.value = ExportState.Preparing(pid, PrepareStep.REVERSING, (i + p) / toReverse.size) }
                doc = doc.copy(mainTrack = doc.mainTrack.map { if (it.id == clip.id) it.copy(reversedUri = uri) else it })
            }
            _state.value = ExportState.Preparing(pid, PrepareStep.BUILDING, 1f)

            // 2. Space check.
            val videoBps = ExportMath.effectiveBitrate(settings, doc.canvas.aspectRatio)
            val estimate = ExportMath.estimateBytes(doc.durationUs, videoBps, settings.audioBitrateBps, settings.includeAudio)
            val available = storage.snapshot(files.exportsDir).availableBytes
            if (available in 1 until estimate + 64L * 1024 * 1024) throw EditorException(EditorError.STORAGE_FULL)

            // 3. Build and export.
            val watermark = ent.watermarkOnExport && !ent.has(ProFeature.EXPORT_NO_WATERMARK)
            val built = builder.build(
                doc,
                BuildOptions(
                    shortSide = settings.resolutionShortSide,
                    forExport = true,
                    includeAudio = settings.includeAudio,
                    watermark = watermark,
                    targetFrameRate = settings.frameRate,
                    burnSubtitles = doc.subtitles.burnIn,
                ),
            ) ?: throw EditorException(EditorError.SOURCE_MISSING, "Empty timeline")
            val name = fileName(request.title)
            val out = File(files.exportsDir, "$name.mp4").also { output = it }
            val started = System.currentTimeMillis()
            _state.value = ExportState.Running(pid, 0f, started)
            val result = runTransformer(
                create = {
                    Transformer.Builder(context)
                        .setVideoMimeType(settings.codec.mimeType)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build())
                                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(settings.audioBitrateBps).build())
                                .setEnableFallback(true)
                                .build(),
                        )
                        .build()
                },
                composition = built.composition,
                outputPath = out.absolutePath,
            ) { p -> _state.value = ExportState.Running(pid, p, started) }

            // 4. Sidecar, gallery, project bookkeeping.
            val srtFile = request.srt?.takeIf { settings.exportSrt && it.isNotBlank() }?.let { srt ->
                File(files.exportsDir, "$name.srt").apply { writeText(srt) }
            }
            val gallery = if (request.publishToGallery) publisher.publishVideo(out, out.name)?.toString() else null
            val now = System.currentTimeMillis()
            runCatching {
                projects.addAsset(
                    MediaAsset(
                        projectId = pid,
                        uri = out.absolutePath,
                        kind = MediaKind.VIDEO,
                        origin = MediaOrigin.EXPORTED,
                        mimeType = "video/mp4",
                        durationUs = result.durationMs * 1000,
                        width = result.width,
                        height = result.height,
                        frameRate = settings.frameRate.toFloat(),
                        sizeBytes = out.length(),
                        hasAudio = settings.includeAudio,
                        publishedUri = gallery,
                        createdAt = now,
                    ),
                )
                projects.getProject(pid)?.let { p ->
                    projects.update(p.copy(status = ProjectStatus.EXPORTED, lastExportUri = gallery ?: out.absolutePath, durationUs = doc.durationUs))
                }
            }.onFailure { RgLog.w(TAG, "Export bookkeeping failed", it) }
            _state.value = ExportState.Succeeded(pid, out, gallery, out.length(), doc.durationUs, result.width.takeIf { it > 0 } ?: built.width, result.height.takeIf { it > 0 } ?: built.height, srtFile, codecFallback)
        } catch (e: CancellationException) {
            output?.delete()
            _state.value = ExportState.Cancelled(pid)
        } catch (e: Throwable) {
            output?.delete()
            RgLog.e(TAG, "Export failed", e)
            _state.value = ExportState.Failed(pid, e.toEditorError(), e.message)
        }
    }

    private fun exists(uri: String): Boolean = uri.toUri().let { u -> if (u.scheme == "file" || u.scheme == null) File(u.path ?: "").exists() else true }

    private fun fileName(title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).trim('_').ifEmpty { "RavanGo" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${safe}_$stamp"
    }

    private companion object {
        const val TAG = "Export"
    }
}
