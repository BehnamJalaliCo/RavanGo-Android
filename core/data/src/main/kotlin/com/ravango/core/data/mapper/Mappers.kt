package com.ravango.core.data.mapper

import com.ravango.core.database.entity.BeautyPresetEntity
import com.ravango.core.database.entity.DraftEntity
import com.ravango.core.database.entity.FolderEntity
import com.ravango.core.database.entity.MediaAssetEntity
import com.ravango.core.database.entity.ProjectEntity
import com.ravango.core.database.entity.PrompterPresetEntity
import com.ravango.core.database.entity.ScriptEntity
import com.ravango.core.datastore.PersistenceJson
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.BeautyState
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.Draft
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaOrigin
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.countWords
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val tagsSerializer = ListSerializer(String.serializer())

private inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E =
    runCatching { enumValueOf<E>(value) }.getOrDefault(default)

fun ScriptEntity.toModel() = Script(
    id = id,
    title = title,
    body = body,
    folderId = folderId,
    direction = enumOr(direction, ContentDirection.AUTO),
    tags = runCatching { PersistenceJson.decodeFromString(tagsSerializer, tagsJson) }.getOrDefault(emptyList()),
    isFavorite = isFavorite,
    prompterSettings = prompterSettingsJson?.let { runCatching { PersistenceJson.decodeFromString(TeleprompterSettings.serializer(), it) }.getOrNull() },
    startCharOffset = startCharOffset,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = lastOpenedAt,
    syncStatus = enumOr(syncStatus, SyncStatus.PENDING),
    deletedAt = deletedAt,
)

fun Script.toEntity() = ScriptEntity(
    id = id,
    title = title,
    body = body,
    folderId = folderId,
    direction = direction.name,
    tagsJson = PersistenceJson.encodeToString(tagsSerializer, tags),
    isFavorite = isFavorite,
    prompterSettingsJson = prompterSettings?.let { PersistenceJson.encodeToString(TeleprompterSettings.serializer(), it) },
    startCharOffset = startCharOffset,
    wordCount = countWords(body),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = lastOpenedAt,
    syncStatus = syncStatus.name,
    deletedAt = deletedAt,
)

fun FolderEntity.toModel() = ScriptFolder(id, name, colorArgb, sortIndex, createdAt, updatedAt, enumOr(syncStatus, SyncStatus.PENDING), deletedAt)
fun ScriptFolder.toEntity() = FolderEntity(id, name, colorArgb, sortIndex, createdAt, updatedAt, syncStatus.name, deletedAt)

fun ProjectEntity.toModel() = Project(
    id = id,
    title = title,
    status = enumOr(status, ProjectStatus.RECORDED),
    aspectRatio = AspectRatioSpec(aspectWidth, aspectHeight),
    scriptId = scriptId,
    templateId = templateId,
    thumbnailPath = thumbnailPath,
    durationUs = durationUs,
    lastExportUri = lastExportUri,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStatus = enumOr(syncStatus, SyncStatus.PENDING),
    deletedAt = deletedAt,
)

fun Project.toEntity() = ProjectEntity(
    id = id,
    title = title,
    status = status.name,
    aspectWidth = aspectRatio.width,
    aspectHeight = aspectRatio.height,
    scriptId = scriptId,
    templateId = templateId,
    thumbnailPath = thumbnailPath,
    durationUs = durationUs,
    lastExportUri = lastExportUri,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncStatus = syncStatus.name,
    deletedAt = deletedAt,
)

fun MediaAssetEntity.toModel() = MediaAsset(
    id = id,
    projectId = projectId,
    uri = uri,
    kind = enumOr(kind, MediaKind.VIDEO),
    origin = enumOr(origin, MediaOrigin.IMPORTED),
    mimeType = mimeType,
    durationUs = durationUs,
    width = width,
    height = height,
    frameRate = frameRate,
    sizeBytes = sizeBytes,
    hasAudio = hasAudio,
    publishedUri = publishedUri,
    cloudPath = cloudPath,
    createdAt = createdAt,
    syncStatus = enumOr(syncStatus, SyncStatus.PENDING),
    deletedAt = deletedAt,
)

fun MediaAsset.toEntity() = MediaAssetEntity(
    id, projectId, uri, kind.name, origin.name, mimeType, durationUs, width, height, frameRate, sizeBytes, hasAudio,
    publishedUri, cloudPath, createdAt, syncStatus.name, deletedAt,
)

fun DraftEntity.toModel(): Draft? = runCatching {
    Draft(projectId, PersistenceJson.decodeFromString(EditorDocument.serializer(), documentJson), revision, updatedAt, enumOr(syncStatus, SyncStatus.PENDING))
}.getOrNull()

fun Draft.toEntity() = DraftEntity(projectId, PersistenceJson.encodeToString(EditorDocument.serializer(), document), revision, updatedAt, syncStatus.name)

fun BeautyPresetEntity.toModel(): BeautyPreset = BeautyPreset(
    id = id,
    name = name,
    state = runCatching { PersistenceJson.decodeFromString(BeautyState.serializer(), stateJson) }.getOrDefault(BeautyState()),
    builtIn = builtIn,
    updatedAt = updatedAt,
    syncStatus = enumOr(syncStatus, SyncStatus.PENDING),
    deletedAt = deletedAt,
)

fun BeautyPreset.toEntity() = BeautyPresetEntity(id, name, PersistenceJson.encodeToString(BeautyState.serializer(), state), builtIn, updatedAt, syncStatus.name, deletedAt)

fun PrompterPresetEntity.toModel(): TeleprompterPreset = TeleprompterPreset(
    id = id,
    name = name,
    settings = runCatching { PersistenceJson.decodeFromString(TeleprompterSettings.serializer(), settingsJson) }.getOrDefault(TeleprompterSettings()),
    builtIn = builtIn,
    updatedAt = updatedAt,
    syncStatus = enumOr(syncStatus, SyncStatus.PENDING),
    deletedAt = deletedAt,
)

fun TeleprompterPreset.toEntity() = PrompterPresetEntity(id, name, PersistenceJson.encodeToString(TeleprompterSettings.serializer(), settings), builtIn, updatedAt, syncStatus.name, deletedAt)
