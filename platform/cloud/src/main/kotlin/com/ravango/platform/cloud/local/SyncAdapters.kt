package com.ravango.platform.cloud.local

import com.ravango.core.data.repository.SyncCollection
import com.ravango.core.database.dao.BeautyPresetDao
import com.ravango.core.database.dao.DraftDao
import com.ravango.core.database.dao.FolderDao
import com.ravango.core.database.dao.MediaAssetDao
import com.ravango.core.database.dao.ProjectDao
import com.ravango.core.database.dao.PrompterPresetDao
import com.ravango.core.database.dao.ScriptDao
import com.ravango.core.database.entity.BeautyPresetEntity
import com.ravango.core.database.entity.DraftEntity
import com.ravango.core.database.entity.FolderEntity
import com.ravango.core.database.entity.MediaAssetEntity
import com.ravango.core.database.entity.ProjectEntity
import com.ravango.core.database.entity.PrompterPresetEntity
import com.ravango.core.database.entity.ScriptEntity
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.newId
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.cloud.merge.ConflictStrategy
import com.ravango.platform.cloud.merge.MergePolicy
import com.ravango.platform.cloud.remote.RemoteRow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** A local row in wire form. [revision] is only used by drafts (their markSynced key). */
data class LocalRecord(
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val status: SyncStatus,
    val data: JsonObject,
    val revision: Long = 0,
    /** Cloud object path (media only), to delete the backed-up file with the tombstone. */
    val cloudPath: String? = null,
)

/**
 * Bridges one Room table and its Supabase table. `data` holds the entity's portable fields; device-local fields
 * (file paths, gallery URIs, thumbnails, "last opened") never leave the device and are preserved on apply.
 */
interface SyncAdapter {
    val collection: SyncCollection
    val table: String get() = collection.remoteName
    /** Plan feature required to sync this collection; null = available on every plan. */
    val requiredFeature: ProFeature?
    val strategy: ConflictStrategy

    suspend fun pending(): List<LocalRecord>
    suspend fun local(id: String): LocalRecord?
    suspend fun markSynced(record: LocalRecord)
    /** Writes the remote version locally as SYNCED. */
    suspend fun applyRemote(row: RemoteRow)
    /** Stores [data] as a new PENDING record titled as a conflict copy; returns the new id. */
    suspend fun saveConflictCopy(data: JsonObject, now: Long, suffix: String): String? = null
    /** Every live local row (used to re-upload everything when a different account signs in). */
    suspend fun markAllPending() {}
}

private val WireJson = SupabaseJson

private fun <T> T.toJsonObject(serializer: KSerializer<T>): JsonObject = WireJson.encodeToJsonElement(serializer, this).jsonObject
private fun <T> JsonObject.decode(serializer: KSerializer<T>): T = WireJson.decodeFromJsonElement(serializer, this)
private fun parseJsonOr(raw: String?, fallback: JsonElement): JsonElement = raw?.let { runCatching { WireJson.parseToJsonElement(it) }.getOrNull() } ?: fallback
private fun JsonElement?.asStoredString(): String? = if (this == null || this is JsonNull) null else WireJson.encodeToString(JsonElement.serializer(), this)

// ---------------------------------------------------------------- scripts

@Serializable
internal data class ScriptData(
    val title: String,
    val body: String,
    val folderId: String? = null,
    val direction: String = "AUTO",
    val tags: JsonElement = JsonArray(emptyList()),
    val isFavorite: Boolean = false,
    val prompterSettings: JsonElement? = null,
    val startCharOffset: Int = 0,
    val wordCount: Int = 0,
    val createdAt: Long = 0,
)

@Singleton
class ScriptSyncAdapter @Inject constructor(private val dao: ScriptDao) : SyncAdapter {
    override val collection = SyncCollection.SCRIPTS
    override val requiredFeature: ProFeature? = null
    override val strategy = ConflictStrategy.KEEP_CONFLICT_COPY

    private fun ScriptEntity.toRecord() = LocalRecord(
        id, updatedAt, deletedAt, SyncStatus.valueOf(syncStatus),
        ScriptData(title, body, folderId, direction, parseJsonOr(tagsJson, JsonArray(emptyList())), isFavorite, parseJsonOr(prompterSettingsJson, JsonNull).takeIf { it !is JsonNull }, startCharOffset, wordCount, createdAt)
            .toJsonObject(ScriptData.serializer()),
    )

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.getById(id)?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.updatedAt)

    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(ScriptData.serializer())
        val existing = dao.getById(row.id)
        dao.upsert(d.toEntity(row.id, row.updatedAt, row.deletedAt, SyncStatus.SYNCED, lastOpenedAt = existing?.lastOpenedAt))
    }

    override suspend fun saveConflictCopy(data: JsonObject, now: Long, suffix: String): String {
        val d = data.decode(ScriptData.serializer())
        val id = newId()
        dao.upsert(d.copy(title = MergePolicy.conflictCopyTitle(d.title, suffix), createdAt = now).toEntity(id, now, null, SyncStatus.PENDING, null))
        return id
    }

    override suspend fun markAllPending() {
        val all = dao.observe(null, "", false, "UPDATED_DESC").first()
        dao.upsertAll(all.map { it.copy(syncStatus = SyncStatus.PENDING.name) })
    }

    private fun ScriptData.toEntity(id: String, updatedAt: Long, deletedAt: Long?, status: SyncStatus, lastOpenedAt: Long?) = ScriptEntity(
        id = id, title = title, body = body, folderId = folderId, direction = direction,
        tagsJson = tags.asStoredString() ?: "[]", isFavorite = isFavorite, prompterSettingsJson = prompterSettings.asStoredString(),
        startCharOffset = startCharOffset, wordCount = wordCount, createdAt = createdAt, updatedAt = updatedAt,
        lastOpenedAt = lastOpenedAt, syncStatus = status.name, deletedAt = deletedAt,
    )
}

// ---------------------------------------------------------------- folders

@Serializable
internal data class FolderData(val name: String, val colorArgb: Long = 0, val sortIndex: Int = 0, val createdAt: Long = 0)

@Singleton
class FolderSyncAdapter @Inject constructor(private val dao: FolderDao) : SyncAdapter {
    override val collection = SyncCollection.FOLDERS
    override val requiredFeature: ProFeature? = null
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun FolderEntity.toRecord() = LocalRecord(id, updatedAt, deletedAt, SyncStatus.valueOf(syncStatus), FolderData(name, colorArgb, sortIndex, createdAt).toJsonObject(FolderData.serializer()))

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.getById(id)?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.updatedAt)
    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(FolderData.serializer())
        dao.upsert(FolderEntity(row.id, d.name, d.colorArgb, d.sortIndex, d.createdAt, row.updatedAt, SyncStatus.SYNCED.name, row.deletedAt))
    }
    override suspend fun markAllPending() {
        dao.upsertAll(dao.observeAll().first().map { it.copy(syncStatus = SyncStatus.PENDING.name) })
    }
}

// ---------------------------------------------------------------- projects

@Serializable
internal data class ProjectData(
    val title: String,
    val status: String = "RECORDED",
    val aspectWidth: Int = 9,
    val aspectHeight: Int = 16,
    val scriptId: String? = null,
    val templateId: String? = null,
    val durationUs: Long = 0,
    val createdAt: Long = 0,
)

@Singleton
class ProjectSyncAdapter @Inject constructor(private val dao: ProjectDao) : SyncAdapter {
    override val collection = SyncCollection.PROJECTS
    override val requiredFeature = ProFeature.CLOUD_PROJECT_SYNC
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun ProjectEntity.toRecord() = LocalRecord(
        id, updatedAt, deletedAt, SyncStatus.valueOf(syncStatus),
        ProjectData(title, status, aspectWidth, aspectHeight, scriptId, templateId, durationUs, createdAt).toJsonObject(ProjectData.serializer()),
    )

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.getById(id)?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.updatedAt)
    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(ProjectData.serializer())
        val existing = dao.getById(row.id)
        dao.upsert(
            ProjectEntity(
                id = row.id, title = d.title, status = d.status, aspectWidth = d.aspectWidth, aspectHeight = d.aspectHeight,
                scriptId = d.scriptId, templateId = d.templateId, thumbnailPath = existing?.thumbnailPath, durationUs = d.durationUs,
                lastExportUri = existing?.lastExportUri, createdAt = d.createdAt, updatedAt = row.updatedAt,
                syncStatus = SyncStatus.SYNCED.name, deletedAt = row.deletedAt,
            ),
        )
    }
    override suspend fun markAllPending() {
        dao.upsertAll(dao.observe(null).first().map { it.copy(syncStatus = SyncStatus.PENDING.name) })
    }
}

// ---------------------------------------------------------------- drafts

@Serializable
internal data class DraftData(val document: JsonElement, val revision: Long)

@Singleton
class DraftSyncAdapter @Inject constructor(private val dao: DraftDao) : SyncAdapter {
    override val collection = SyncCollection.DRAFTS
    override val requiredFeature = ProFeature.CLOUD_PROJECT_SYNC
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun DraftEntity.toRecord() = LocalRecord(
        projectId, updatedAt, null, SyncStatus.valueOf(syncStatus),
        DraftData(parseJsonOr(documentJson, JsonObject(emptyMap())), revision).toJsonObject(DraftData.serializer()),
        revision = revision,
    )

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.get(id)?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.revision)
    override suspend fun applyRemote(row: RemoteRow) {
        if (row.deletedAt != null) {
            dao.delete(row.id)
            return
        }
        val d = row.data.decode(DraftData.serializer())
        val localRevision = dao.get(row.id)?.revision ?: 0
        // Revisions only grow so later local auto-saves never collide with the pulled one.
        dao.upsert(DraftEntity(row.id, d.document.asStoredString() ?: "{}", maxOf(d.revision, localRevision), row.updatedAt, SyncStatus.SYNCED.name))
    }
    override suspend fun markAllPending() {
        dao.observeAll().first().forEach { dao.upsert(it.copy(syncStatus = SyncStatus.PENDING.name)) }
    }
}

// ---------------------------------------------------------------- media metadata

@Serializable
internal data class MediaData(
    val projectId: String? = null,
    val kind: String,
    val origin: String,
    val mimeType: String,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val sizeBytes: Long = 0,
    val hasAudio: Boolean = true,
    val cloudPath: String? = null,
    val createdAt: Long = 0,
)

/** Media assets have no updated_at column locally; their version is the tombstone time or creation time. */
internal val MediaAssetEntity.version: Long get() = deletedAt ?: createdAt

/** URI prefix for assets known from the cloud whose file is not on this device yet. */
const val CLOUD_URI_PREFIX = "cloud:"

@Singleton
class MediaSyncAdapter @Inject constructor(private val dao: MediaAssetDao) : SyncAdapter {
    override val collection = SyncCollection.MEDIA
    override val requiredFeature = ProFeature.CLOUD_PROJECT_SYNC
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun MediaAssetEntity.toRecord() = LocalRecord(
        id, version, deletedAt, SyncStatus.valueOf(syncStatus),
        MediaData(projectId, kind, origin, mimeType, durationUs, width, height, frameRate, sizeBytes, hasAudio, cloudPath, createdAt).toJsonObject(MediaData.serializer()),
        cloudPath = cloudPath,
    )

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.getById(id)?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.cloudPath)
    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(MediaData.serializer())
        val existing = dao.getById(row.id)
        val uri = existing?.uri ?: d.cloudPath?.let { CLOUD_URI_PREFIX + it } ?: ""
        dao.upsert(
            MediaAssetEntity(
                id = row.id, projectId = d.projectId, uri = uri, kind = d.kind, origin = d.origin, mimeType = d.mimeType,
                durationUs = d.durationUs, width = d.width, height = d.height, frameRate = d.frameRate, sizeBytes = d.sizeBytes,
                hasAudio = d.hasAudio, publishedUri = existing?.publishedUri, cloudPath = d.cloudPath ?: existing?.cloudPath,
                createdAt = d.createdAt, syncStatus = SyncStatus.SYNCED.name, deletedAt = row.deletedAt,
            ),
        )
    }
    override suspend fun markAllPending() {
        dao.observeAll(null).first().forEach { dao.upsert(it.copy(syncStatus = SyncStatus.PENDING.name)) }
    }
}

// ---------------------------------------------------------------- presets

@Serializable
internal data class PresetData(val name: String, val payload: JsonElement)

@Singleton
class BeautyPresetSyncAdapter @Inject constructor(private val dao: BeautyPresetDao) : SyncAdapter {
    override val collection = SyncCollection.BEAUTY_PRESETS
    override val requiredFeature: ProFeature? = null
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun BeautyPresetEntity.toRecord() = LocalRecord(id, updatedAt, deletedAt, SyncStatus.valueOf(syncStatus), PresetData(name, parseJsonOr(stateJson, JsonObject(emptyMap()))).toJsonObject(PresetData.serializer()))

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.observeAll().first().firstOrNull { it.id == id }?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.updatedAt)
    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(PresetData.serializer())
        dao.upsert(BeautyPresetEntity(row.id, d.name, d.payload.asStoredString() ?: "{}", false, row.updatedAt, SyncStatus.SYNCED.name, row.deletedAt))
    }
    override suspend fun markAllPending() {
        dao.upsertAll(dao.observeAll().first().filter { !it.builtIn }.map { it.copy(syncStatus = SyncStatus.PENDING.name) })
    }
}

@Singleton
class PrompterPresetSyncAdapter @Inject constructor(private val dao: PrompterPresetDao) : SyncAdapter {
    override val collection = SyncCollection.PROMPTER_PRESETS
    override val requiredFeature: ProFeature? = null
    override val strategy = ConflictStrategy.LAST_WRITER_WINS

    private fun PrompterPresetEntity.toRecord() = LocalRecord(id, updatedAt, deletedAt, SyncStatus.valueOf(syncStatus), PresetData(name, parseJsonOr(settingsJson, JsonObject(emptyMap()))).toJsonObject(PresetData.serializer()))

    override suspend fun pending() = dao.pending().map { it.toRecord() }
    override suspend fun local(id: String) = dao.observeAll().first().firstOrNull { it.id == id }?.toRecord()
    override suspend fun markSynced(record: LocalRecord) = dao.markSynced(record.id, record.updatedAt)
    override suspend fun applyRemote(row: RemoteRow) {
        val d = row.data.decode(PresetData.serializer())
        dao.upsert(PrompterPresetEntity(row.id, d.name, d.payload.asStoredString() ?: "{}", false, row.updatedAt, SyncStatus.SYNCED.name, row.deletedAt))
    }
    override suspend fun markAllPending() {
        dao.upsertAll(dao.observeAll().first().filter { !it.builtIn }.map { it.copy(syncStatus = SyncStatus.PENDING.name) })
    }
}

/** All adapters in dependency order (folders before scripts, projects before drafts/media). */
@Singleton
class SyncAdapters @Inject constructor(
    folders: FolderSyncAdapter,
    scripts: ScriptSyncAdapter,
    projects: ProjectSyncAdapter,
    drafts: DraftSyncAdapter,
    media: MediaSyncAdapter,
    beauty: BeautyPresetSyncAdapter,
    prompter: PrompterPresetSyncAdapter,
) {
    val all: List<SyncAdapter> = listOf(folders, scripts, projects, drafts, media, beauty, prompter)
}
