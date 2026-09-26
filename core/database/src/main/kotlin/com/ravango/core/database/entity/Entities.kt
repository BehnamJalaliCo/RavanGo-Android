package com.ravango.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities. Complex values (settings, editor documents, beauty state) are stored as JSON produced by
 * kotlinx.serialization in the data layer, keeping the schema stable while models evolve.
 * Every syncable table has updated_at, sync_status and deleted_at (tombstone) columns.
 */

@Entity(tableName = "scripts", indices = [Index("folder_id"), Index("updated_at"), Index("sync_status")])
data class ScriptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val direction: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "prompter_settings_json") val prompterSettingsJson: String?,
    @ColumnInfo(name = "start_char_offset") val startCharOffset: Int,
    @ColumnInfo(name = "word_count") val wordCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "script_folders", indices = [Index("sync_status")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "projects", indices = [Index("updated_at"), Index("sync_status")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    @ColumnInfo(name = "aspect_w") val aspectWidth: Int,
    @ColumnInfo(name = "aspect_h") val aspectHeight: Int,
    @ColumnInfo(name = "script_id") val scriptId: String?,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "last_export_uri") val lastExportUri: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "media_assets", indices = [Index("project_id"), Index("sync_status")])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String?,
    val uri: String,
    val kind: String,
    val origin: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "frame_rate") val frameRate: Float,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "has_audio") val hasAudio: Boolean,
    @ColumnInfo(name = "published_uri") val publishedUri: String?,
    @ColumnInfo(name = "cloud_path") val cloudPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "drafts", indices = [Index("sync_status")])
data class DraftEntity(
    @PrimaryKey @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "document_json") val documentJson: String,
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
)

@Entity(tableName = "beauty_presets", indices = [Index("sync_status")])
data class BeautyPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "state_json") val stateJson: String,
    @ColumnInfo(name = "built_in") val builtIn: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(tableName = "prompter_presets", indices = [Index("sync_status")])
data class PrompterPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    @ColumnInfo(name = "built_in") val builtIn: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** Per-collection watermark for incremental pulls from the cloud. */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val collection: String,
    @ColumnInfo(name = "last_pulled_at") val lastPulledAt: Long,
)

/** Local AI usage ledger (mirrors server-side accounting when signed in; authoritative for offline/BYOK mode). */
@Entity(tableName = "ai_usage", indices = [Index("created_at")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String,
    val credits: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
