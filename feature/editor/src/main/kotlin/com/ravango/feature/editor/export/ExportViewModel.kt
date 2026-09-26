package com.ravango.feature.editor.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.ProFeature
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.ExportRoute
import com.ravango.engine.ai.api.SubtitleBuilder
import com.ravango.engine.editor.draft.DraftManager
import com.ravango.engine.editor.export.ExportController
import com.ravango.engine.editor.export.ExportMath
import com.ravango.engine.editor.export.ExportRequest
import com.ravango.engine.editor.export.ExportState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ExportUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val document: EditorDocument? = null,
    val settings: ExportSettings = ExportSettings(),
    val entitlements: Entitlements = Entitlements(),
    val hevcSupported: Boolean = false,
    /** Export state for this project (other projects' exports show as busy). */
    val export: ExportState = ExportState.Idle,
    val otherExportRunning: Boolean = false,
) {
    val durationUs: Long get() = document?.durationUs ?: 0
    val hasCues: Boolean get() = document?.subtitles?.cues?.isNotEmpty() == true

    fun resolutionLocked(shortSide: Int): Boolean =
        shortSide > entitlements.maxExportShortSide || (shortSide >= 2160 && !entitlements.has(ProFeature.EXPORT_4K))

    fun fpsLocked(fps: Int): Boolean = fps > entitlements.maxExportFps || (fps > 30 && !entitlements.has(ProFeature.EXPORT_60FPS))

    val outputSize: Pair<Int, Int>
        get() = ExportMath.outputSize(document?.canvas?.aspectRatio ?: com.ravango.core.model.AspectRatioSpec.Portrait9x16, settings.resolutionShortSide)

    val videoBitrate: Int
        get() = ExportMath.effectiveBitrate(settings, document?.canvas?.aspectRatio ?: com.ravango.core.model.AspectRatioSpec.Portrait9x16)

    val estimatedBytes: Long get() = ExportMath.estimateBytes(durationUs, videoBitrate, settings.audioBitrateBps, settings.includeAudio)

    val watermark: Boolean get() = entitlements.watermarkOnExport && !entitlements.has(ProFeature.EXPORT_NO_WATERMARK)
}

sealed interface ExportEvent {
    data class RequirePro(val feature: ProFeature) : ExportEvent
    data class Launch(val intent: Intent) : ExportEvent
}

/** What the export screen can ask for; implemented by [ExportViewModel]. */
interface ExportActions {
    fun setResolution(shortSide: Int)
    fun setFrameRate(fps: Int)
    fun setCodec(codec: VideoCodec)
    fun setAutoBitrate(auto: Boolean)
    fun setBitrate(bps: Int)
    fun setIncludeAudio(on: Boolean)
    fun setExportSrt(on: Boolean)
    fun removeWatermark()
    fun startExport()
    fun cancel()
    fun reset()
    fun share(chooserTitle: String)
    fun shareSrt(chooserTitle: String)
    fun openInGallery()
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val drafts: DraftManager,
    private val controller: ExportController,
    private val entitlementProvider: EntitlementProvider,
    private val subtitleBuilder: SubtitleBuilder,
) : ViewModel(), ExportActions {
    val projectId: String = savedStateHandle.toRoute<ExportRoute>().projectId
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()
    private val _events = Channel<ExportEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        _state.update { it.copy(hevcSupported = controller.isCodecSupported(VideoCodec.HEVC)) }
        viewModelScope.launch {
            val loaded = drafts.load(projectId)
            if (loaded == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _state.update { s ->
                s.copy(loading = false, title = loaded.project.title, document = loaded.document, settings = sanitize(loaded.document.export, s))
            }
        }
        viewModelScope.launch {
            entitlementProvider.entitlements.collect { e -> _state.update { s -> s.copy(entitlements = e).let { it.copy(settings = sanitize(it.settings, it)) } } }
        }
        viewModelScope.launch {
            controller.state.collect { st ->
                val mine = when (st) {
                    is ExportState.Preparing -> st.projectId == projectId
                    is ExportState.Running -> st.projectId == projectId
                    is ExportState.Succeeded -> st.projectId == projectId
                    is ExportState.Failed -> st.projectId == projectId
                    is ExportState.Cancelled -> st.projectId == projectId
                    ExportState.Idle -> true
                }
                _state.update { it.copy(export = if (mine) st else ExportState.Idle, otherExportRunning = !mine && st.isActive) }
            }
        }
    }

    /** Snaps stored settings to options the plan allows (keeps the user's choice otherwise). */
    private fun sanitize(s: ExportSettings, state: ExportUiState): ExportSettings {
        var res = ExportMath.RESOLUTIONS.lastOrNull { it <= s.resolutionShortSide } ?: 1080
        while (state.resolutionLocked(res) && res > ExportMath.RESOLUTIONS.first()) res = ExportMath.RESOLUTIONS[ExportMath.RESOLUTIONS.indexOf(res) - 1]
        var fps = ExportMath.FRAME_RATES.lastOrNull { it <= s.frameRate } ?: 30
        while (state.fpsLocked(fps) && fps > ExportMath.FRAME_RATES.first()) fps = ExportMath.FRAME_RATES[ExportMath.FRAME_RATES.indexOf(fps) - 1]
        val codec = if (s.codec == VideoCodec.HEVC && !state.hevcSupported) VideoCodec.H264 else s.codec
        return s.copy(resolutionShortSide = res, frameRate = fps, codec = codec)
    }

    override fun setResolution(shortSide: Int) {
        val s = state.value
        if (s.resolutionLocked(shortSide)) {
            _events.trySend(ExportEvent.RequirePro(ProFeature.EXPORT_4K))
            return
        }
        update { it.copy(resolutionShortSide = shortSide) }
    }

    override fun setFrameRate(fps: Int) {
        if (state.value.fpsLocked(fps)) {
            _events.trySend(ExportEvent.RequirePro(ProFeature.EXPORT_60FPS))
            return
        }
        update { it.copy(frameRate = fps) }
    }

    override fun setCodec(codec: VideoCodec) { update { it.copy(codec = codec) } }
    override fun setAutoBitrate(auto: Boolean) { update { s -> s.copy(videoBitrateBps = if (auto) null else state.value.videoBitrate) } }
    override fun setBitrate(bps: Int) { update { it.copy(videoBitrateBps = bps.coerceIn(ExportMath.MIN_CUSTOM_BITRATE, ExportMath.MAX_CUSTOM_BITRATE)) } }
    override fun setIncludeAudio(on: Boolean) { update { it.copy(includeAudio = on) } }
    override fun setExportSrt(on: Boolean) { update { it.copy(exportSrt = on) } }
    override fun removeWatermark() {
        _events.trySend(ExportEvent.RequirePro(ProFeature.EXPORT_NO_WATERMARK))
    }

    private fun update(transform: (ExportSettings) -> ExportSettings) = _state.update { it.copy(settings = transform(it.settings)) }

    override fun startExport() {
        val s = state.value
        val doc = s.document ?: return
        if (s.otherExportRunning || s.export.isActive) return
        val updated = doc.copy(export = s.settings)
        viewModelScope.launch {
            drafts.save(projectId, updated)
            val srt = if (s.settings.exportSrt && s.hasCues) runCatching { subtitleBuilder.toSrt(doc.subtitles.cues) }.getOrNull() else null
            controller.start(ExportRequest(projectId, s.title, updated, s.settings, srt))
        }
    }

    override fun cancel() { controller.cancel() }

    /** Returns to the settings form after a result was shown. */
    override fun reset() { controller.acknowledge() }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    override fun share(chooserTitle: String) {
        val done = state.value.export as? ExportState.Succeeded ?: return
        runCatching {
            val uri = uriFor(done.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _events.trySend(ExportEvent.Launch(Intent.createChooser(send, chooserTitle)))
        }.onFailure { RgLog.e(TAG, "Share failed", it) }
    }

    override fun shareSrt(chooserTitle: String) {
        val done = state.value.export as? ExportState.Succeeded ?: return
        val srt = done.srtFile ?: return
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-subrip"
                putExtra(Intent.EXTRA_STREAM, uriFor(srt))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _events.trySend(ExportEvent.Launch(Intent.createChooser(send, chooserTitle)))
        }.onFailure { RgLog.e(TAG, "Share SRT failed", it) }
    }

    override fun openInGallery() {
        val done = state.value.export as? ExportState.Succeeded ?: return
        val uri = done.galleryUri?.let(Uri::parse) ?: runCatching { uriFor(done.file) }.getOrNull() ?: return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        _events.trySend(ExportEvent.Launch(view))
    }

    private companion object {
        const val TAG = "ExportVM"
    }
}
