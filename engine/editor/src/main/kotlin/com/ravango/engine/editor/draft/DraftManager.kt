package com.ravango.engine.editor.draft

import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.log.RgLog
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.TemplateRepository
import com.ravango.core.media.MediaProbe
import com.ravango.core.model.CanvasSpec
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.MediaSource
import com.ravango.core.model.Project
import com.ravango.core.model.SubtitleTrack
import com.ravango.core.model.VideoClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedDraft(val project: Project, val document: EditorDocument, val createdFromAssets: Boolean)

/**
 * Loads the editor document for a project (creating it from the project's recordings when no draft exists) and
 * auto-saves it: [scheduleSave] debounces by [DEBOUNCE_MS]; [flush] writes immediately (used when the app goes to the
 * background or the editor closes). Saves run in the application scope so they complete even if the screen is gone.
 */
@Singleton
class DraftManager @Inject constructor(
    private val projects: ProjectRepository,
    private val templates: TemplateRepository,
    private val probe: MediaProbe,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val saveMutex = Mutex()
    private var pendingJob: Job? = null
    private var pending: Pair<String, EditorDocument>? = null
    private val lastSaved = HashMap<String, EditorDocument>()

    suspend fun load(projectId: String): LoadedDraft? {
        val project = projects.getProject(projectId) ?: return null
        val draft = projects.getDraft(projectId)
        if (draft != null) {
            lastSaved[projectId] = draft.document
            return LoadedDraft(project, draft.document, createdFromAssets = false)
        }
        val doc = InitialDocumentFactory.create(project, sourcesFor(projects.assets(projectId)), templates.template(project.templateId ?: "")?.subtitleStyle)
        return LoadedDraft(project, doc, createdFromAssets = true)
    }

    /** Converts assets to media sources, probing any whose metadata is incomplete. */
    suspend fun sourcesFor(assets: List<MediaAsset>): List<MediaSource> = assets
        .filter { it.deletedAt == null && it.origin != MediaOrigin.EXPORTED && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.IMAGE) }
        .sortedBy { it.createdAt }
        .mapNotNull { asset ->
            val needsProbe = (asset.kind == MediaKind.VIDEO && (asset.durationUs <= 0 || asset.width <= 0)) || (asset.kind == MediaKind.IMAGE && asset.width <= 0)
            if (needsProbe) probe.probe(asset.uri)?.toMediaSource(asset.id)
            else MediaSource(asset.uri, asset.kind, asset.durationUs, asset.width, asset.height, 0, asset.hasAudio, asset.frameRate, asset.id)
        }

    fun scheduleSave(projectId: String, document: EditorDocument) {
        synchronized(this) {
            pending = projectId to document
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(DEBOUNCE_MS)
                flushPending()
            }
        }
    }

    /** Saves any pending change now. */
    fun flush() {
        synchronized(this) { pendingJob?.cancel() }
        scope.launch { flushPending() }
    }

    private suspend fun flushPending() = withContext(NonCancellable) {
        val job = synchronized(this@DraftManager) { pending.also { pending = null } } ?: return@withContext
        save(job.first, job.second)
    }

    suspend fun save(projectId: String, document: EditorDocument) = saveMutex.withLock {
        if (lastSaved[projectId] == document) return@withLock
        runCatching { projects.saveDraft(projectId, document) }
            .onSuccess { lastSaved[projectId] = document }
            .onFailure { RgLog.e(TAG, "Draft save failed", it) }
    }

    companion object {
        private const val TAG = "Drafts"
        const val DEBOUNCE_MS = 1_000L
    }
}

/** Builds the first document for a project: its recordings in order on the main track, canvas = project aspect. */
object InitialDocumentFactory {
    fun create(project: Project, sources: List<MediaSource>, templateSubtitleStyle: com.ravango.core.model.SubtitleStyle?): EditorDocument {
        val clips = sources.map { s ->
            if (s.kind == MediaKind.IMAGE) VideoClip(source = s) else VideoClip(source = s, trimStartUs = 0, trimEndUs = s.durationUs)
        }
        return EditorDocument(
            canvas = CanvasSpec(aspectRatio = project.aspectRatio),
            mainTrack = clips,
            subtitles = SubtitleTrack(style = templateSubtitleStyle ?: SubtitleTrack().style),
        )
    }
}
