package com.ravango.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.media.MediaProbe
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.AudioClip
import com.ravango.core.model.AudioTrackKind
import com.ravango.core.model.CanvasBackground
import com.ravango.core.model.ColorAdjustments
import com.ravango.core.model.ContentFit
import com.ravango.core.model.CropRect
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.FilterPreset
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.MediaSource
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.TextStyleSpec
import com.ravango.core.model.Transform2D
import com.ravango.core.model.Transition
import com.ravango.core.model.TransitionType
import com.ravango.core.model.VideoClip
import com.ravango.core.model.service.EntitlementProvider
import com.ravango.core.navigation.EditorRoute
import com.ravango.engine.ai.api.AiTextService
import com.ravango.engine.ai.api.AudioCleanupService
import com.ravango.engine.ai.api.CapabilityState
import com.ravango.engine.ai.api.EyeContactService
import com.ravango.engine.ai.api.ShortSuggestion
import com.ravango.engine.ai.api.SpeechToTextService
import com.ravango.engine.ai.api.SubtitleBuilder
import com.ravango.engine.ai.api.Transcript
import com.ravango.engine.ai.api.VideoIntelligence
import com.ravango.engine.audio.AudioEngine
import com.ravango.engine.audio.AudioRecordingHandle
import com.ravango.engine.editor.composition.CanvasSizing
import com.ravango.engine.editor.composition.CompositionBuilder
import com.ravango.engine.editor.draft.DraftManager
import com.ravango.engine.editor.export.EditorError
import com.ravango.engine.editor.export.toEditorError
import com.ravango.engine.editor.history.EditorHistory
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.media.WaveformProvider
import com.ravango.engine.editor.ops.Edge
import com.ravango.engine.editor.ops.EditOps
import com.ravango.engine.editor.ops.EditResult
import com.ravango.engine.editor.ops.MediaOps
import com.ravango.engine.editor.ops.ProjectFiles
import com.ravango.engine.editor.ops.ReverseProcessor
import com.ravango.engine.editor.ops.withAnimations
import com.ravango.engine.editor.ops.withTransform
import com.ravango.engine.editor.preview.PreviewController
import com.ravango.engine.editor.timeline.TimelineMath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Navigation requests from the editor. */
sealed interface EditorEvent {
    data class RequirePro(val feature: ProFeature) : EditorEvent
    data object OpenExport : EditorEvent
    data object OpenSettings : EditorEvent
    data class ProjectCreated(val projectId: String) : EditorEvent
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val projects: ProjectRepository,
    private val drafts: DraftManager,
    val preview: PreviewController,
    private val reverse: ReverseProcessor,
    private val mediaOps: MediaOps,
    private val files: ProjectFiles,
    private val probe: MediaProbe,
    private val builder: CompositionBuilder,
    private val entitlementProvider: EntitlementProvider,
    private val audioEngine: AudioEngine,
    private val speech: SpeechToTextService,
    private val subtitleBuilder: SubtitleBuilder,
    private val intelligence: VideoIntelligence,
    private val audioCleanup: AudioCleanupService,
    private val eyeContact: EyeContactService,
    private val aiText: AiTextService,
    val thumbnails: ThumbnailProvider,
    val waveforms: WaveformProvider,
) : ViewModel() {

    val projectId: String = savedStateHandle.toRoute<EditorRoute>().projectId

    private val _state = MutableStateFlow(EditorUiState(projectId = projectId))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Playhead in timeline microseconds (fast-changing; read in draw/layout lambdas). */
    val playheadUs: StateFlow<Long> = preview.positionUs
    val isPlaying: StateFlow<Boolean> = preview.isPlaying

    private val history = EditorHistory(EditorDocument())
    private val previewDoc = MutableStateFlow<EditorDocument?>(null)
    private var busyJob: Job? = null
    private val reverseJobs = HashMap<String, Job>()
    private val transcripts = HashMap<String, Transcript>()
    private var recording: AudioRecordingHandle? = null
    private var recordingJob: Job? = null
    private var loaded = false

    val bitmapFactory get() = builder.bitmapFactory

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            previewDoc.filterNotNull().debounce(PREVIEW_DEBOUNCE_MS).collectLatest { doc ->
                preview.setDocument(doc, watermark())
            }
        }
        viewModelScope.launch {
            entitlementProvider.entitlements.collect { e ->
                val changed = _state.value.entitlements.watermarkOnExport != e.watermarkOnExport
                _state.update { it.copy(entitlements = e) }
                // Rebuild directly: the same document would be de-duplicated by the preview flow.
                if (changed && loaded) preview.setDocument(history.current, watermark())
            }
        }
        viewModelScope.launch {
            speech.availability.collect { a ->
                _state.update { it.copy(services = it.services.copy(speechConfigured = a.configured, speechConsentRequired = a.consentRequired, speechDetail = a.detail)) }
            }
        }
        viewModelScope.launch {
            aiText.availability.collect { a -> _state.update { it.copy(services = it.services.copy(aiConfigured = a.configured, aiDetail = a.detail)) } }
        }
        _state.update { it.copy(services = it.services.copy(eyeContact = runCatching { eyeContact.state }.getOrDefault(CapabilityState.UNSUPPORTED), eyeContactService = runCatching { eyeContact.requiredService }.getOrDefault(""))) }
        viewModelScope.launch { preview.building.collect { b -> _state.update { it.copy(previewBuilding = b) } } }
        viewModelScope.launch { preview.error.collect { e -> _state.update { it.copy(previewError = e != null) } } }
    }

    private fun watermark(): Boolean {
        val e = entitlementProvider.entitlements.value
        return e.watermarkOnExport && !e.has(ProFeature.EXPORT_NO_WATERMARK)
    }

    private suspend fun load() {
        val loadedDraft = drafts.load(projectId)
        if (loadedDraft == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        history.reset(loadedDraft.document)
        loaded = true
        _state.update { it.copy(loading = false, projectTitle = loadedDraft.project.title) }
        publish(loadedDraft.document, save = loadedDraft.createdFromAssets && loadedDraft.document.mainTrack.isNotEmpty())
    }

    // ------------------------------------------------------------------ document plumbing

    private fun publish(doc: EditorDocument, save: Boolean = true) {
        _state.update { s ->
            s.copy(
                document = doc,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                selection = s.selection.takeIf { sel -> selectionExists(doc, sel) } ?: Selection.None,
            )
        }
        previewDoc.value = doc
        if (save) drafts.scheduleSave(projectId, doc)
        ensureReversed(doc)
        refreshOverlaySize()
    }

    private fun selectionExists(doc: EditorDocument, sel: Selection): Boolean = when (sel) {
        Selection.None -> true
        is Selection.Clip -> doc.mainTrack.any { it.id == sel.id }
        is Selection.Overlay -> EditOps.findOverlay(doc, sel.id) != null
        is Selection.Audio -> EditOps.findAudioClip(doc, sel.id) != null
        is Selection.Cue -> doc.subtitles.cues.any { it.id == sel.id }
    }

    /** Applies an edit as one undo step (or merged into the current gesture when [coalesceKey] repeats). */
    fun edit(coalesceKey: String? = null, transform: (EditorDocument) -> EditorDocument?) {
        val next = transform(history.current) ?: return
        if (history.commit(next, coalesceKey)) publish(next)
    }

    private fun editResult(transform: (EditorDocument) -> EditResult?) {
        val r = transform(history.current) ?: return
        if (history.commit(r.document)) {
            publish(r.document)
            r.selectId?.let { id -> select(selectionFor(r.document, id)) }
        }
    }

    private fun selectionFor(doc: EditorDocument, id: String): Selection = when {
        doc.mainTrack.any { it.id == id } -> Selection.Clip(id)
        EditOps.findOverlay(doc, id) != null -> Selection.Overlay(id)
        EditOps.findAudioClip(doc, id) != null -> Selection.Audio(id)
        doc.subtitles.cues.any { it.id == id } -> Selection.Cue(id)
        else -> Selection.None
    }

    /** Replaces the current document without an undo step (background results such as a reversed rendition). */
    private fun patch(transform: (EditorDocument) -> EditorDocument) {
        val next = transform(history.current)
        if (next == history.current) return
        history.replaceCurrent(next)
        publish(next)
    }

    /** Ends a continuous gesture so the next change is a new undo step. */
    fun endGesture() = history.seal()

    fun undo() {
        history.undo()?.let { publish(it) }
    }

    fun redo() {
        history.redo()?.let { publish(it) }
    }

    fun select(selection: Selection) {
        _state.update { s ->
            val tool = when (selection) {
                is Selection.Clip -> if (s.tool == null || s.tool in setOf(EditorTool.TEXT, EditorTool.MUSIC, EditorTool.CAPTIONS, EditorTool.STICKERS)) EditorTool.EDIT else s.tool
                is Selection.Overlay -> when (EditOps.findOverlay(s.document, selection.id)) {
                    is OverlayItem.Text -> EditorTool.TEXT
                    is OverlayItem.Sticker -> EditorTool.STICKERS
                    is OverlayItem.Image -> if ((EditOps.findOverlay(s.document, selection.id) as OverlayItem.Image).isWatermark) EditorTool.LOGO else EditorTool.OVERLAY
                    is OverlayItem.Video -> EditorTool.OVERLAY
                    null -> s.tool
                }
                is Selection.Audio -> if (s.tool == EditorTool.VOICEOVER) s.tool else EditorTool.MUSIC
                is Selection.Cue -> EditorTool.CAPTIONS
                Selection.None -> s.tool
            }
            s.copy(selection = selection, tool = tool)
        }
        refreshOverlaySize()
    }

    fun selectClipAtPlayhead() {
        TimelineMath.clipAt(history.current, playheadUs.value)?.let { select(Selection.Clip(it.clip.id)) }
    }

    /** Opens the paywall for [feature]. */
    fun upgrade(feature: ProFeature) {
        _events.trySend(EditorEvent.RequirePro(feature))
    }

    fun openTool(tool: EditorTool?) {
        _state.update { it.copy(tool = if (it.tool == tool) null else tool) }
    }

    fun consumeMessage(id: Long) {
        _state.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun message(text: Int? = null, kind: ErrorKind? = null, error: EditorError? = null, detail: String? = null) {
        _state.update { it.copy(message = UiMessage(text, kind, error, detail)) }
    }

    /** Returns true when allowed; otherwise asks the UI to show the paywall. */
    private fun requirePro(feature: ProFeature): Boolean {
        if (entitlementProvider.has(feature)) return true
        _events.trySend(EditorEvent.RequirePro(feature))
        return false
    }

    private fun runBusy(label: Int, cancellable: Boolean = true, block: suspend (progress: (Float) -> Unit) -> Unit) {
        if (busyJob?.isActive == true) return
        busyJob = viewModelScope.launch {
            _state.update { it.copy(busy = BusyTask(label, null, cancellable)) }
            try {
                block { p -> _state.update { s -> s.copy(busy = s.busy?.copy(progress = p.coerceIn(0f, 1f))) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.e(TAG, "Task failed", e)
                message(error = e.toEditorError(), detail = e.message)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancelBusy() {
        busyJob?.cancel()
    }

    private fun <T> Outcome<T>.valueOrReport(): T? = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> {
            message(kind = kind, detail = message)
            null
        }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() = preview.togglePlay()

    fun seekTo(timeUs: Long, scrubbing: Boolean = false) {
        if (scrubbing && preview.isPlaying.value) preview.pause()
        preview.seekTo(timeUs.coerceIn(0, history.current.durationUs), scrubbing)
    }

    fun stepFrame(forward: Boolean) {
        preview.pause()
        val doc = history.current
        val clip = TimelineMath.clipAt(doc, playheadUs.value)?.clip
        val step = TimelineMath.frameDurationUs(clip?.source?.frameRate ?: 30f)
        seekTo(playheadUs.value + if (forward) step else -step)
    }

    fun onStop() {
        preview.pause()
        drafts.flush()
    }

    // ------------------------------------------------------------------ import

    /** Adds picked photos/videos to the project and the timeline (at the playhead, or appended). */
    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runBusy(R.string.editor_busy_importing, cancellable = false) { progress ->
            val sources = ArrayList<MediaSource>()
            uris.forEachIndexed { i, uri ->
                val stored = persistOrCopy(uri) ?: return@forEachIndexed
                val info = probe.probe(stored) ?: return@forEachIndexed
                if (info.kind == MediaKind.AUDIO) return@forEachIndexed
                val asset = MediaAsset(
                    projectId = projectId, uri = stored, kind = info.kind, origin = MediaOrigin.IMPORTED, mimeType = info.mimeType,
                    durationUs = info.durationUs, width = info.displayWidth, height = info.displayHeight, frameRate = info.frameRate,
                    sizeBytes = info.sizeBytes, hasAudio = info.hasAudio, createdAt = System.currentTimeMillis(),
                )
                runCatching { projects.addAsset(asset) }
                sources += info.toMediaSource(asset.id)
                progress((i + 1f) / uris.size)
            }
            if (sources.isEmpty()) {
                message(R.string.editor_msg_import_failed)
                return@runBusy
            }
            val clips = sources.map { s -> if (s.kind == MediaKind.IMAGE) VideoClip(source = s) else VideoClip(source = s, trimEndUs = s.durationUs) }
            val doc = history.current
            editResult {
                if (doc.mainTrack.isEmpty() || playheadUs.value >= doc.durationUs - 50_000) EditOps.appendClips(it, clips)
                else EditOps.insertClips(it, clips, playheadUs.value)
            }
        }
    }

    /** Keeps a content:// URI readable across restarts, or copies it into project storage when that is impossible. */
    private suspend fun persistOrCopy(uri: Uri): String? = withContext(io) {
        if (uri.scheme != "content") return@withContext uri.toString()
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (persisted) return@withContext uri.toString()
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: ""
            val ext = when {
                mime.startsWith("image/png") -> "png"
                mime.startsWith("image/webp") -> "webp"
                mime.startsWith("image/") -> "jpg"
                mime.startsWith("audio/") -> "m4a"
                else -> "mp4"
            }
            val out = File(files.importsDir(projectId), "import_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: return@runCatching null
            ProjectFiles.uriOf(out)
        }.onFailure { RgLog.w(TAG, "Import copy failed", it) }.getOrNull()
    }

    // ------------------------------------------------------------------ main-track edits

    private fun selectedClip(): VideoClip? = (state.value.selection as? Selection.Clip)?.let { s -> history.current.mainTrack.firstOrNull { it.id == s.id } }
        ?: TimelineMath.clipAt(history.current, playheadUs.value)?.clip

    fun split() {
        preview.pause()
        val r = EditOps.split(history.current, playheadUs.value)
        if (r == null) message(R.string.editor_msg_split_edge) else editResult { r }
    }

    fun deleteSelection() {
        when (val sel = state.value.selection) {
            is Selection.Clip -> editResult { EditOps.deleteClip(it, sel.id) }
            is Selection.Overlay -> edit { EditOps.removeOverlay(it, sel.id) }
            is Selection.Audio -> edit { EditOps.removeAudioClip(it, sel.id) }
            is Selection.Cue -> edit { EditOps.deleteCue(it, sel.id) }
            Selection.None -> Unit
        }
    }

    fun duplicateSelection() {
        when (val sel = state.value.selection) {
            is Selection.Clip -> editResult { EditOps.duplicateClip(it, sel.id) }
            is Selection.Overlay -> editResult { EditOps.duplicateOverlay(it, sel.id) }
            else -> Unit
        }
    }

    fun updateSelectedClip(key: String? = null, transform: (VideoClip) -> VideoClip) {
        val clip = selectedClip() ?: return
        edit(key?.let { "$it:${clip.id}" }) { EditOps.updateClip(it, clip.id, transform) }
    }

    fun setSpeed(speed: Float, gesture: Boolean) {
        val clip = selectedClip() ?: return
        edit(if (gesture) "speed:${clip.id}" else null) { EditOps.setSpeed(it, clip.id, speed) }
    }

    fun rotateClip() = selectedClip()?.let { c -> edit { EditOps.rotate90(it, c.id) } }
    fun flipClip(horizontal: Boolean) = selectedClip()?.let { c -> edit { EditOps.flip(it, c.id, horizontal) } }
    fun setCrop(crop: CropRect, gesture: Boolean) = selectedClip()?.let { c -> edit(if (gesture) "crop:${c.id}" else null) { EditOps.setCrop(it, c.id, crop) } }
    fun resetClipTransform() = updateSelectedClip { it.copy(transform = Transform2D(), crop = CropRect(), rotationQuarterTurns = 0, flipHorizontal = false, flipVertical = false) }

    fun joinWithNext() {
        val c = selectedClip() ?: return
        val r = EditOps.joinWithNext(history.current, c.id)
        if (r == null) message(R.string.editor_msg_cannot_join) else editResult { r }
    }

    fun canJoin(clipId: String): Boolean = EditOps.canJoinWithNext(history.current, clipId)

    fun moveClip(clipId: String, toIndex: Int) = edit { EditOps.moveClip(it, clipId, toIndex) }

    fun trimClip(clipId: String, edge: Edge, deltaUs: Long) = edit("trim:$clipId:$edge") { EditOps.trimClip(it, clipId, edge, deltaUs) }

    fun setTransition(clipId: String, type: TransitionType, durationUs: Long) =
        edit { EditOps.setTransition(it, clipId, Transition(type, durationUs)) }

    /** Applies one transition to every cut in a single undo step. */
    fun setTransitionForAll(type: TransitionType, durationUs: Long) = edit { d ->
        d.copy(mainTrack = d.mainTrack.mapIndexed { i, c -> if (i < d.mainTrack.lastIndex) c.copy(transitionOut = Transition(type, durationUs)) else c })
    }

    fun toggleReverse() {
        val c = selectedClip() ?: return
        if (c.source.kind != MediaKind.VIDEO) return
        if (!c.reversed && !requirePro(ProFeature.EDITOR_REVERSE)) return
        edit { EditOps.updateClip(it, c.id) { clip -> clip.copy(reversed = !clip.reversed, reversedUri = if (clip.reversed) null else clip.reversedUri) } }
    }

    /** Renders missing reversed renditions in the background (cache hits are applied immediately). */
    private fun ensureReversed(doc: EditorDocument) {
        doc.mainTrack.filter { it.reversed && it.reversedUri == null && it.source.kind == MediaKind.VIDEO }.forEach { clip ->
            val cached = reverse.cachedReverse(projectId, clip)
            if (cached != null) {
                viewModelScope.launch { patch { d -> d.copy(mainTrack = d.mainTrack.map { if (it.id == clip.id && it.reversed) it.copy(reversedUri = cached) else it }) } }
                return@forEach
            }
            if (reverseJobs[clip.id]?.isActive == true) return@forEach
            _state.update { it.copy(reversing = it.reversing + clip.id) }
            reverseJobs[clip.id] = viewModelScope.launch {
                try {
                    val uri = reverse.reverse(projectId, clip) { }
                    patch { d ->
                        d.copy(mainTrack = d.mainTrack.map {
                            if (it.id == clip.id && it.reversed && it.trimStartUs == clip.trimStartUs && it.trimEndUs == clip.trimEndUs) it.copy(reversedUri = uri) else it
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RgLog.e(TAG, "Reverse failed", e)
                    message(error = e.toEditorError())
                    patch { d -> d.copy(mainTrack = d.mainTrack.map { if (it.id == clip.id) it.copy(reversed = false) else it }) }
                } finally {
                    _state.update { it.copy(reversing = it.reversing - clip.id) }
                }
            }
        }
    }

    fun freezeFrame(durationUs: Long = 2_000_000) {
        val doc = history.current
        val p = TimelineMath.clipAt(doc, playheadUs.value) ?: return
        if (p.clip.source.kind != MediaKind.VIDEO) {
            message(R.string.editor_msg_freeze_video_only)
            return
        }
        val at = playheadUs.value
        val sourceUs = TimelineMath.timelineToSource(p.clip, p.startUs, at)
        runBusy(R.string.editor_busy_freeze, cancellable = false) {
            val still = mediaOps.freezeFrame(projectId, p.clip, sourceUs)
            editResult { d -> mediaOps.insertFreezeFrame(d, at, still, durationUs) }
        }
    }

    fun setClipVolume(volume: Float, gesture: Boolean) = updateSelectedClip(if (gesture) "volume" else null) { it.copy(volume = volume, muted = false) }
    fun toggleMute() = updateSelectedClip { it.copy(muted = !it.muted) }
    fun setAudioFades(inUs: Long, outUs: Long, gesture: Boolean) = updateSelectedClip(if (gesture) "afade" else null) { it.copy(audioFadeInUs = inUs, audioFadeOutUs = outUs) }
    fun setVideoFades(inUs: Long, outUs: Long, gesture: Boolean) = updateSelectedClip(if (gesture) "vfade" else null) { it.copy(videoFadeInUs = inUs, videoFadeOutUs = outUs) }
    fun setStillDuration(us: Long, gesture: Boolean) = updateSelectedClip(if (gesture) "still" else null) { it.copy(stillDurationUs = us.coerceIn(TimelineMath.MIN_CLIP_US, EditOps.MAX_STILL_US)) }

    // ------------------------------------------------------------------ canvas & color

    fun setAspect(aspect: AspectRatioSpec) = edit { it.copy(canvas = it.canvas.copy(aspectRatio = aspect)) }
    fun setBackground(bg: CanvasBackground, gesture: Boolean = false) = edit(if (gesture) "bg" else null) { it.copy(canvas = it.canvas.copy(background = bg)) }
    fun setFit(fit: ContentFit) = edit { it.copy(canvas = it.canvas.copy(fit = fit)) }

    fun setFilter(filter: FilterPreset) = updateSelectedClip { it.copy(filter = filter) }
    fun setFilterIntensity(v: Float, gesture: Boolean) = updateSelectedClip(if (gesture) "filterIntensity" else null) { it.copy(filterIntensity = v) }
    fun applyFilterToAll() {
        val c = selectedClip() ?: return
        edit { d -> d.copy(mainTrack = d.mainTrack.map { it.copy(filter = c.filter, filterIntensity = c.filterIntensity, adjustments = c.adjustments) }) }
    }

    /** [advanced] adjustments need EDITOR_ADVANCED_COLOR. */
    fun setAdjustments(adjustments: ColorAdjustments, field: String, advanced: Boolean) {
        if (advanced && !requirePro(ProFeature.EDITOR_ADVANCED_COLOR)) return
        updateSelectedClip("adjust:$field") { it.copy(adjustments = adjustments) }
    }

    fun resetAdjustments() = updateSelectedClip { it.copy(adjustments = ColorAdjustments()) }

    // ------------------------------------------------------------------ overlays

    private fun defaultRange(lengthUs: Long = 3_000_000): Pair<Long, Long> {
        val total = history.current.durationUs.coerceAtLeast(lengthUs)
        val start = playheadUs.value.coerceIn(0, (total - 500_000).coerceAtLeast(0))
        return start to minOf(start + lengthUs, total).coerceAtLeast(start + 500_000)
    }

    /** Overlays that would need a second layer are a Pro feature. */
    private fun allowOverlay(item: OverlayItem): Boolean {
        val probeDoc = EditOps.addOverlay(history.current, item).document
        if (probeDoc.overlayTracks.size > 1 && !entitlementProvider.has(ProFeature.EDITOR_MULTI_LAYER)) {
            _events.trySend(EditorEvent.RequirePro(ProFeature.EDITOR_MULTI_LAYER))
            return false
        }
        return true
    }

    private fun addOverlay(item: OverlayItem) {
        if (!allowOverlay(item)) return
        editResult { EditOps.addOverlay(it, item) }
    }

    fun addText(text: String, style: TextStyleSpec) {
        if (text.isBlank()) return
        val (s, e) = defaultRange()
        addOverlay(OverlayItem.Text(startUs = s, endUs = e, text = text.trim(), style = style, transform = Transform2D(centerY = 0.4f)))
    }

    fun addSticker(emoji: String) {
        val (s, e) = defaultRange()
        addOverlay(OverlayItem.Sticker(startUs = s, endUs = e, emoji = emoji, transform = Transform2D(centerX = 0.5f, centerY = 0.35f, scale = 1f)))
    }

    fun updateOverlay(id: String, gestureKey: String? = null, transform: (OverlayItem) -> OverlayItem) =
        edit(gestureKey?.let { "$it:$id" }) { EditOps.updateOverlay(it, id, transform) }

    fun setOverlayAnimations(id: String, animIn: OverlayAnimation, animOut: OverlayAnimation) = updateOverlay(id) { it.withAnimations(animIn, animOut) }

    fun retimeOverlay(id: String, startUs: Long, endUs: Long) = edit("retime:$id") { EditOps.retimeOverlay(it, id, startUs, endUs) }

    /** Applies a preview pan/pinch/rotate gesture to the selected overlay or clip. */
    fun transformSelection(dx: Float, dy: Float, zoom: Float, rotation: Float) {
        when (val sel = state.value.selection) {
            is Selection.Overlay -> updateOverlay(sel.id, "xf") { item ->
                val t = item.transform
                item.withTransform(t.copy(centerX = (t.centerX + dx).coerceIn(-0.2f, 1.2f), centerY = (t.centerY + dy).coerceIn(-0.2f, 1.2f), scale = (t.scale * zoom).coerceIn(0.1f, 6f), rotationDegrees = normalizeDeg(t.rotationDegrees + rotation)))
            }
            is Selection.Clip -> updateSelectedClip("xf") { c ->
                val t = c.transform
                c.copy(transform = t.copy(centerX = (t.centerX + dx).coerceIn(-0.5f, 1.5f), centerY = (t.centerY + dy).coerceIn(-0.5f, 1.5f), scale = (t.scale * zoom).coerceIn(0.2f, 5f), rotationDegrees = normalizeDeg(t.rotationDegrees + rotation)))
            }
            else -> Unit
        }
    }

    private fun normalizeDeg(d: Float): Float {
        var v = d % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        // Gentle snap to straight.
        return if (kotlin.math.abs(v) < 1.5f) 0f else v
    }

    private fun refreshOverlaySize() {
        val sel = state.value.selection as? Selection.Overlay ?: return
        val doc = history.current
        val item = EditOps.findOverlay(doc, sel.id) ?: return
        viewModelScope.launch {
            val (w, h) = CanvasSizing.size(doc.canvas.aspectRatio, CanvasSizing.PREVIEW_SHORT_SIDE)
            val size = withContext(io) { runCatching { bitmapFactory.normalizedSize(item, w, h) }.getOrNull() } ?: return@launch
            _state.update { it.copy(overlaySizes = it.overlaySizes + (item.id to size)) }
        }
    }

    /** Photos become image overlays; videos become picture-in-picture (Pro). */
    fun addMediaOverlay(uri: Uri) {
        runBusy(R.string.editor_busy_importing, cancellable = false) {
            val stored = persistOrCopy(uri) ?: return@runBusy message(R.string.editor_msg_import_failed)
            val info = probe.probe(stored) ?: return@runBusy message(R.string.editor_msg_import_failed)
            val (s, e) = defaultRange(if (info.kind == MediaKind.VIDEO) info.durationUs.coerceAtMost(history.current.durationUs.coerceAtLeast(1_000_000)) else 3_000_000)
            when (info.kind) {
                MediaKind.VIDEO -> {
                    if (!requirePro(ProFeature.EDITOR_PIP)) return@runBusy
                    addOverlay(OverlayItem.Video(startUs = s, endUs = minOf(e, s + info.durationUs), source = info.toMediaSource()))
                }
                MediaKind.IMAGE -> addOverlay(OverlayItem.Image(startUs = s, endUs = e, uri = stored))
                MediaKind.AUDIO -> message(R.string.editor_msg_import_failed)
            }
        }
    }

    /** A logo spanning the whole video in a corner; [asWatermark] marks it as the creator's watermark. */
    fun addLogo(uri: Uri, asWatermark: Boolean) {
        runBusy(R.string.editor_busy_importing, cancellable = false) {
            val stored = persistOrCopy(uri) ?: return@runBusy message(R.string.editor_msg_import_failed)
            val total = history.current.durationUs.coerceAtLeast(1_000_000)
            addOverlay(
                OverlayItem.Image(
                    startUs = 0, endUs = total, uri = stored, isWatermark = asWatermark,
                    transform = Transform2D(centerX = 0.84f, centerY = 0.08f, scale = 0.45f, opacity = if (asWatermark) 0.7f else 1f),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ audio

    fun importMusic(uri: Uri) {
        runBusy(R.string.editor_busy_importing, cancellable = false) {
            val stored = persistOrCopy(uri) ?: return@runBusy message(R.string.editor_msg_import_failed)
            val info = probe.probe(stored)?.takeIf { it.hasAudio } ?: return@runBusy message(R.string.editor_msg_import_failed)
            val total = history.current.durationUs
            val start = if (playheadUs.value >= total - 500_000) 0 else playheadUs.value
            val source = info.toMediaSource().copy(kind = MediaKind.AUDIO)
            val clip = AudioClip(source = source, startUs = start, trimEndUs = info.durationUs, volume = 0.7f, fadeInUs = 500_000, fadeOutUs = 1_000_000, loop = info.durationUs < total - start)
            editResult { EditOps.addAudioClip(it, AudioTrackKind.MUSIC, clip) }
        }
    }

    fun updateAudioClip(id: String, gestureKey: String? = null, transform: (AudioClip) -> AudioClip) =
        edit(gestureKey?.let { "$it:$id" }) { EditOps.updateAudioClip(it, id, transform) }

    fun trimAudio(id: String, edge: Edge, deltaUs: Long) = edit("atrim:$id:$edge") { EditOps.trimAudioClip(it, id, edge, deltaUs) }

    fun moveAudio(id: String, startUs: Long) = updateAudioClip(id, "amove") { it.copy(startUs = startUs.coerceAtLeast(0)) }

    fun setTrackDucking(trackId: String, ducking: Boolean) = edit { EditOps.updateAudioTrack(it, trackId) { t -> t.copy(ducking = ducking) } }
    fun setTrackMuted(trackId: String, muted: Boolean) = edit { EditOps.updateAudioTrack(it, trackId) { t -> t.copy(muted = muted) } }

    fun extractAudio() {
        val c = selectedClip() ?: return
        if (c.source.kind != MediaKind.VIDEO || !c.source.hasAudio) return message(R.string.editor_msg_no_audio)
        runBusy(R.string.editor_busy_extract) { progress ->
            val audio = mediaOps.extractAudio(projectId, c.source, progress)
            editResult { EditOps.replaceClipAudio(it, c.id, audio, alignToSource = true) }
            message(R.string.editor_msg_audio_extracted)
        }
    }

    fun detachAudio() {
        val c = selectedClip() ?: return
        val r = EditOps.detachAudio(history.current, c.id)
        if (r == null) message(R.string.editor_msg_no_audio) else editResult { r }
    }

    fun setNoiseReduction(v: Float, gesture: Boolean) {
        if (v > 0f && !requirePro(ProFeature.AUDIO_NOISE_REDUCTION)) return
        updateSelectedClip(if (gesture) "nr" else null) { it.copy(noiseReduction = v) }
    }

    fun setVoiceEnhance(on: Boolean) = updateSelectedClip { it.copy(voiceEnhance = on) }

    /** Offline AI cleanup (denoise + enhance + loudness) to a new file used as the clip's audio. */
    fun aiAudioCleanup() {
        if (!requirePro(ProFeature.AUDIO_NOISE_REDUCTION)) return
        val c = selectedClip() ?: return
        if (c.source.kind != MediaKind.VIDEO || !c.source.hasAudio) return message(R.string.editor_msg_no_audio)
        runBusy(R.string.editor_busy_cleanup) { progress ->
            val out = File(files.audioDir(projectId), "clean_${Integer.toHexString(c.source.uri.hashCode())}.m4a")
            val file = audioCleanup.cleanup(c.source.uri, out, 0.6f, progress).valueOrReport() ?: return@runBusy
            val source = MediaSource(ProjectFiles.uriOf(file), MediaKind.AUDIO, c.source.durationUs, hasAudio = true)
            editResult { EditOps.replaceClipAudio(it, c.id, source, alignToSource = true) }
            message(R.string.editor_msg_cleanup_done)
        }
    }

    // ------------------------------------------------------------------ voice-over

    fun startVoiceOver() {
        if (recording != null) return
        val file = File(files.audioDir(projectId), "voice_${System.currentTimeMillis()}.m4a")
        val start = playheadUs.value
        val handle = try {
            audioEngine.startAudioOnlyRecording(file)
        } catch (e: Exception) {
            RgLog.e(TAG, "Voice-over start failed", e)
            message(kind = if (e is SecurityException) ErrorKind.PERMISSION else ErrorKind.UNKNOWN)
            return
        }
        recording = handle
        preview.setVolume(0f)
        preview.play()
        _state.update { it.copy(voiceOver = VoiceOverState(recording = true, startUs = start)) }
        recordingJob = viewModelScope.launch {
            launch { handle.durationUs.collect { d -> _state.update { s -> s.copy(voiceOver = s.voiceOver.copy(elapsedUs = d)) } } }
            audioEngine.level.collect { l ->
                val norm = ((l.peakDbfs + 60f) / 60f).coerceIn(0f, 1f)
                _state.update { s -> s.copy(voiceOver = s.voiceOver.copy(level = norm)) }
            }
        }
    }

    fun stopVoiceOver() {
        val handle = recording ?: return
        recording = null
        recordingJob?.cancel()
        preview.pause()
        preview.setVolume(1f)
        val start = state.value.voiceOver.startUs
        _state.update { it.copy(voiceOver = VoiceOverState()) }
        viewModelScope.launch {
            val file = runCatching { handle.stop() }.getOrNull()
            val info = file?.let { probe.probe(ProjectFiles.uriOf(it)) }
            if (info == null || info.durationUs <= 0) return@launch message(R.string.editor_msg_voiceover_failed)
            val clip = AudioClip(source = info.toMediaSource().copy(kind = MediaKind.AUDIO), startUs = start, trimEndUs = info.durationUs)
            editResult { EditOps.addAudioClip(it, AudioTrackKind.VOICEOVER, clip) }
        }
    }

    // ------------------------------------------------------------------ captions

    fun autoCaption(language: String?) {
        if (!requirePro(ProFeature.AUTO_CAPTIONS)) return
        val status = state.value.services
        if (!status.speechConfigured) return message(kind = ErrorKind.NOT_CONFIGURED, detail = status.speechDetail)
        val doc = history.current
        val targets = TimelineMath.placements(doc).filter { p ->
            val c = p.clip
            c.source.kind == MediaKind.VIDEO && c.source.hasAudio && !c.muted && !c.reversed
        }
        if (targets.isEmpty()) return message(R.string.editor_msg_no_speech_clips)
        runBusy(R.string.editor_busy_captions) { progress ->
            val parts = ArrayList<Transcript>()
            targets.forEachIndexed { i, p ->
                val c = p.clip
                val t = speech.transcribe(c.source.uri, language, c.trimStartUs, c.trimEndUs) { f -> progress((i + f) / targets.size) }.valueOrReport() ?: return@runBusy
                transcripts[c.id] = t
                parts += CaptionMapping.toTimeline(t, c, p.startUs)
            }
            val merged = CaptionMapping.merge(parts)
            val cues = subtitleBuilder.build(merged, history.current.subtitles.style)
            if (cues.isEmpty()) return@runBusy message(R.string.editor_msg_no_speech_found)
            edit { d -> EditOps.setCues(d, cues).let { it.copy(subtitles = it.subtitles.copy(language = merged.language, visible = true)) } }
            message(R.string.editor_msg_captions_done)
        }
    }

    fun updateCue(id: String, gestureKey: String? = null, transform: (SubtitleCue) -> SubtitleCue) =
        edit(gestureKey?.let { "$it:$id" }) { EditOps.updateCue(it, id, transform) }

    /** Moves (same length: word timings shift with it) or trims a cue from the timeline. */
    fun retimeCue(id: String, startUs: Long, endUs: Long) = updateCue(id, "retime") { cue ->
        val s = startUs.coerceAtLeast(0)
        val e = endUs.coerceAtLeast(s + 100_000)
        if (e - s == cue.endUs - cue.startUs) {
            val shift = s - cue.startUs
            cue.copy(startUs = s, endUs = e, words = cue.words.map { it.copy(startUs = it.startUs + shift, endUs = it.endUs + shift) })
        } else {
            cue.copy(startUs = s, endUs = e, words = cue.words.map { it.copy(startUs = it.startUs.coerceIn(s, e), endUs = it.endUs.coerceIn(s, e)) })
        }
    }

    fun addCueAtPlayhead(text: String) {
        val s = playheadUs.value
        editResult { EditOps.addCue(it, SubtitleCue(startUs = s, endUs = s + 2_000_000, text = text)) }
    }

    fun splitCueAtPlayhead(id: String) {
        val r = EditOps.splitCue(history.current, id, playheadUs.value)
        if (r == null) message(R.string.editor_msg_split_edge) else editResult { r }
    }

    fun mergeCueWithNext(id: String) = editResult { EditOps.mergeCueWithNext(it, id) }
    fun deleteCue(id: String) = edit { EditOps.deleteCue(it, id) }
    fun clearCues() = edit { EditOps.setCues(it, emptyList()) }
    fun setSubtitleStyle(style: SubtitleStyle, gesture: Boolean = false) = edit(if (gesture) "substyle" else null) { it.copy(subtitles = it.subtitles.copy(style = style)) }
    fun setSubtitlesVisible(visible: Boolean) = edit { it.copy(subtitles = it.subtitles.copy(visible = visible)) }
    fun setBurnIn(burn: Boolean) = edit { it.copy(subtitles = it.subtitles.copy(burnIn = burn)) }

    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(io) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            val cues = text?.let { runCatching { subtitleBuilder.parseSrt(it) }.getOrNull() }
            if (cues.isNullOrEmpty()) return@launch message(R.string.editor_msg_srt_invalid)
            edit { EditOps.setCues(it, cues) }
            message(R.string.editor_msg_srt_imported)
        }
    }

    fun exportSrt(uri: Uri) {
        val cues = history.current.subtitles.cues
        viewModelScope.launch {
            val ok = withContext(io) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(subtitleBuilder.toSrt(cues).toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            }
            message(if (ok) R.string.editor_msg_srt_saved else R.string.editor_msg_srt_save_failed)
        }
    }

    // ------------------------------------------------------------------ AI

    private fun requireAi(): Boolean = requirePro(ProFeature.AI_VIDEO_TOOLS)

    fun detectSilences() {
        if (!requireAi()) return
        val clips = history.current.mainTrack.filter { it.source.kind == MediaKind.VIDEO && it.source.hasAudio && !it.reversed }
        if (clips.isEmpty()) return message(R.string.editor_msg_no_speech_clips)
        runBusy(R.string.editor_busy_analyzing) { progress ->
            val result = LinkedHashMap<String, List<com.ravango.core.media.dsp.TimeRange>>()
            clips.forEachIndexed { i, c ->
                result[c.id] = intelligence.detectSilences(c.source.uri, c.trimStartUs, c.trimEndUs)
                    .filter { it.durationUs > 150_000 }
                progress((i + 1f) / clips.size)
            }
            _state.update { it.copy(ai = it.ai.copy(silences = result)) }
            if (result.values.all { it.isEmpty() }) message(R.string.editor_msg_no_silence)
        }
    }

    fun applySilenceRemoval() {
        val silences = state.value.ai.silences ?: return
        edit { start ->
            var d = start
            silences.forEach { (id, ranges) ->
                EditOps.removeSourceRanges(d, id, ranges.map { it.startUs..it.endUs })?.let { d = it.document }
            }
            d
        }
        _state.update { it.copy(ai = it.ai.copy(silences = null)) }
    }

    fun dismissAi() = _state.update { it.copy(ai = AiResults()) }

    private fun longestVideoClip(): VideoClip? = history.current.mainTrack.filter { it.source.kind == MediaKind.VIDEO && !it.reversed }.maxByOrNull { it.outputDurationUs }

    private suspend fun transcriptFor(clip: VideoClip, progress: (Float) -> Unit): Transcript? {
        transcripts[clip.id]?.let { return it }
        if (!state.value.services.speechConfigured) return null
        val t = speech.transcribe(clip.source.uri, null, clip.trimStartUs, clip.trimEndUs, progress).valueOrReport() ?: return null
        transcripts[clip.id] = t
        return t
    }

    fun findHighlights() {
        if (!requireAi()) return
        val clip = longestVideoClip() ?: return message(R.string.editor_msg_no_speech_clips)
        runBusy(R.string.editor_busy_analyzing) { progress ->
            val transcript = transcriptFor(clip) { progress(it * 0.6f) }
            val list = intelligence.findHighlights(clip.source.uri, transcript, 5).valueOrReport() ?: return@runBusy
            progress(1f)
            _state.update { it.copy(ai = it.ai.copy(highlights = list, highlightClipId = clip.id)) }
        }
    }

    /** Seeks to a highlight (its source range mapped onto the timeline). */
    fun jumpToSource(clipId: String, sourceUs: Long) {
        val p = TimelineMath.placementOf(history.current, clipId) ?: return
        TimelineMath.sourceToTimeline(p.clip, p.startUs, sourceUs.coerceIn(p.clip.trimStartUs, p.clip.trimEndUs))?.let { seekTo(it) }
    }

    fun suggestShorts() {
        if (!requireAi()) return
        val clip = longestVideoClip() ?: return message(R.string.editor_msg_no_speech_clips)
        if (!state.value.services.speechConfigured) return message(kind = ErrorKind.NOT_CONFIGURED, detail = state.value.services.speechDetail)
        runBusy(R.string.editor_busy_analyzing) { progress ->
            val transcript = transcriptFor(clip) { progress(it * 0.7f) } ?: return@runBusy
            val list = intelligence.suggestShorts(transcript, 45, 3).valueOrReport() ?: return@runBusy
            _state.update { it.copy(ai = it.ai.copy(shorts = list, shortsClipId = clip.id, createdShorts = emptySet())) }
        }
    }

    /** Creates a new 9:16 project for a short suggestion (clips for its ranges + captions). */
    fun createShort(index: Int) {
        val ai = state.value.ai
        val suggestion: ShortSuggestion = ai.shorts?.getOrNull(index) ?: return
        val clip = history.current.mainTrack.firstOrNull { it.id == ai.shortsClipId } ?: return
        runBusy(R.string.editor_busy_creating_short, cancellable = false) {
            val clips = suggestion.ranges.map { r ->
                clip.copy(id = com.ravango.core.model.newId(), trimStartUs = r.startUs.coerceIn(0, clip.source.durationUs), trimEndUs = r.endUs.coerceIn(0, clip.source.durationUs), transitionOut = Transition(), reversedUri = null)
            }.filter { it.trimEndUs - it.trimStartUs > TimelineMath.MIN_CLIP_US }
            if (clips.isEmpty()) return@runBusy
            val transcript = transcripts[clip.id]
            val base = EditorDocument(
                canvas = history.current.canvas.copy(aspectRatio = AspectRatioSpec.Portrait9x16, fit = ContentFit.FILL),
                mainTrack = clips,
                subtitles = history.current.subtitles.copy(cues = emptyList()),
            )
            val cues = if (transcript != null) {
                val parts = TimelineMath.placements(base).map { p -> CaptionMapping.toTimeline(transcript, p.clip, p.startUs) }
                runCatching { subtitleBuilder.build(CaptionMapping.merge(parts), base.subtitles.style) }.getOrDefault(emptyList())
            } else emptyList()
            val project = projects.create(suggestion.title.ifBlank { state.value.projectTitle }, AspectRatioSpec.Portrait9x16)
            projects.saveDraft(project.id, EditOps.setCues(base, cues))
            _state.update { it.copy(ai = it.ai.copy(createdShorts = it.ai.createdShorts + index)) }
            _events.trySend(EditorEvent.ProjectCreated(project.id))
            message(R.string.editor_msg_short_created)
        }
    }

    fun planAutoEdit() {
        if (!requireAi()) return
        runBusy(R.string.editor_busy_analyzing) { progress ->
            val plan = intelligence.planAutoEdit(history.current, progress).valueOrReport() ?: return@runBusy
            _state.update { it.copy(ai = it.ai.copy(plan = plan)) }
        }
    }

    fun applyAutoEdit() {
        val plan = state.value.ai.plan ?: return
        edit { start ->
            var d = if (plan.subtitles.isNotEmpty()) EditOps.setCues(start, plan.subtitles) else start
            plan.cuts.forEach { (clipId, cuts) ->
                EditOps.removeSourceRanges(d, clipId, cuts.map { it.range.startUs..it.range.endUs })?.let { d = it.document }
            }
            d
        }
        _state.update { it.copy(ai = it.ai.copy(plan = null)) }
    }

    fun correctEyeContact() {
        if (!requireAi()) return
        val c = selectedClip() ?: return
        if (state.value.services.eyeContact != CapabilityState.AVAILABLE) return
        runBusy(R.string.editor_busy_eye_contact) { progress ->
            val out = File(files.projectDir(projectId), "gaze_${System.currentTimeMillis()}.mp4")
            val file = eyeContact.correct(c.source.uri, out, progress).valueOrReport() ?: return@runBusy
            val info = probe.probe(ProjectFiles.uriOf(file)) ?: return@runBusy message(R.string.editor_msg_import_failed)
            edit { d -> EditOps.updateClip(d, c.id) { it.copy(source = info.toMediaSource(it.source.assetId), reversedUri = null) } }
        }
    }

    // ------------------------------------------------------------------ export

    fun openExport() {
        viewModelScope.launch {
            preview.pause()
            drafts.save(projectId, history.current)
            _events.send(EditorEvent.OpenExport)
        }
    }

    fun openSettings() {
        _events.trySend(EditorEvent.OpenSettings)
    }

    override fun onCleared() {
        // Finalize an in-progress voice-over so the file is not left corrupt (the clip is not added).
        recording?.let { h -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch { runCatching { h.stop() } } }
        drafts.scheduleSave(projectId, history.current)
        drafts.flush()
        preview.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "EditorVM"
        private const val PREVIEW_DEBOUNCE_MS = 150L
    }
}
