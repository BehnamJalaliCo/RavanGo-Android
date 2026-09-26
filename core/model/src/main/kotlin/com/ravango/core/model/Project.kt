package com.ravango.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus { RECORDED, EDITING, EXPORTED }

@Serializable
enum class MediaKind { VIDEO, AUDIO, IMAGE }

@Serializable
enum class MediaOrigin { RECORDED, IMPORTED, EXPORTED, GENERATED }

/** A production: recordings + edit document + exports. */
@Serializable
data class Project(
    val id: String = newId(),
    val title: String,
    val status: ProjectStatus = ProjectStatus.RECORDED,
    val aspectRatio: AspectRatioSpec = AspectRatioSpec.Portrait9x16,
    val scriptId: String? = null,
    val templateId: String? = null,
    val thumbnailPath: String? = null,
    val durationUs: Long = 0,
    val lastExportUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

/** A media file known to the app (recording, import or export). */
@Serializable
data class MediaAsset(
    val id: String = newId(),
    val projectId: String? = null,
    /** `file://` path in app storage or a `content://` URI. */
    val uri: String,
    val kind: MediaKind,
    val origin: MediaOrigin,
    val mimeType: String,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val sizeBytes: Long = 0,
    val hasAudio: Boolean = true,
    /** Public gallery URI once published to MediaStore. */
    val publishedUri: String? = null,
    /** Remote object path once backed up to cloud storage. */
    val cloudPath: String? = null,
    val createdAt: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

/** Auto-saved editor state for a project. */
@Serializable
data class Draft(
    val projectId: String,
    val document: EditorDocument,
    val revision: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
)

/** A starting point offered on Home (e.g. "Instagram Reel", "YouTube talking head"). */
@Serializable
data class ProjectTemplate(
    val id: String,
    val nameKey: String,
    val descriptionKey: String,
    val aspectRatio: AspectRatioSpec,
    val suggestedDurationSec: Int,
    val scriptOutline: String,
    val cameraFrameRate: Int = 30,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val autoCaptions: Boolean = true,
    val accentArgb: ArgbColor = 0xFF8B7CF6,
)
