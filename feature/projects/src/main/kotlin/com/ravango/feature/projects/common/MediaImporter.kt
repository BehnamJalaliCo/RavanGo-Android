package com.ravango.feature.projects.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.result.AppException
import com.ravango.core.common.result.ErrorKind
import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.media.MediaInfo
import com.ravango.core.media.MediaProbe
import com.ravango.core.model.Clock
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Turns media picked with the system Photo Picker into a new project: keeps read access across restarts when the
 * provider allows it, probes every item, infers the canvas from the first visual item and registers the assets.
 * Originals are never copied or modified.
 */
class MediaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: MediaProbe,
    private val projects: ProjectRepository,
    private val clock: Clock,
) {
    suspend fun importAsProject(uris: List<Uri>, fallbackTitle: String): Outcome<Project> = outcomeOf {
        if (uris.isEmpty()) throw AppException(ErrorKind.CANCELLED)
        uris.forEach(::persistAccess)
        val infos = uris.mapNotNull { uri -> probe.probe(uri.toString()).also { if (it == null) RgLog.w(TAG, "Could not read $uri") } }
        if (infos.isEmpty()) throw AppException(ErrorKind.INVALID_INPUT, "No readable media")

        val firstVisual = infos.firstOrNull { it.kind != MediaKind.AUDIO && it.displayWidth > 0 && it.displayHeight > 0 }
        val aspect = inferAspectRatio(firstVisual?.displayWidth ?: 0, firstVisual?.displayHeight ?: 0)
        val title = titleFrom(infos, fallbackTitle)
        val created = projects.create(title, aspect)
        val now = clock.now()
        infos.forEach { info -> projects.addAsset(info.toAsset(created.id, now)) }
        val totalUs = infos.filter { it.kind == MediaKind.VIDEO }.sumOf { it.durationUs }
        if (totalUs > 0) projects.update(created.copy(durationUs = totalUs)) else created
    }

    private fun persistAccess(uri: Uri) {
        if (uri.scheme != "content") return
        // Photo Picker URIs support persistable grants on recent platforms; older providers may refuse — reading
        // still works for this session, so failure is not an error.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { RgLog.d(TAG, "Persistable permission not available for $uri") }
    }

    private fun titleFrom(infos: List<MediaInfo>, fallback: String): String {
        val name = infos.first().displayName?.substringBeforeLast('.')?.trim().orEmpty()
        // Camera file names ("VID_20240101_120000") make poor titles; keep human names only.
        val looksGenerated = name.isEmpty() || Regex("^(VID|IMG|PXL|MVIMG|video|image)[_-]?\\d", RegexOption.IGNORE_CASE).containsMatchIn(name) || name.all { it.isDigit() || it == '_' || it == '-' }
        return if (looksGenerated) fallback else name.take(80)
    }

    private fun MediaInfo.toAsset(projectId: String, now: Long) = MediaAsset(
        projectId = projectId,
        uri = uri,
        kind = kind,
        origin = MediaOrigin.IMPORTED,
        mimeType = mimeType,
        durationUs = durationUs,
        width = displayWidth,
        height = displayHeight,
        frameRate = frameRate,
        sizeBytes = sizeBytes,
        hasAudio = hasAudio,
        createdAt = now,
    )

    private companion object {
        const val TAG = "MediaImporter"
    }
}
