package com.ravango.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ravango.core.database.entity.AiUsageEntity
import com.ravango.core.database.entity.BeautyPresetEntity
import com.ravango.core.database.entity.DraftEntity
import com.ravango.core.database.entity.FolderEntity
import com.ravango.core.database.entity.MediaAssetEntity
import com.ravango.core.database.entity.ProjectEntity
import com.ravango.core.database.entity.PrompterPresetEntity
import com.ravango.core.database.entity.ScriptEntity
import com.ravango.core.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {
    @Query(
        """
        SELECT * FROM scripts WHERE deleted_at IS NULL
          AND (:folderId IS NULL OR folder_id = :folderId)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
          AND (:favoritesOnly = 0 OR is_favorite = 1)
        ORDER BY
          CASE WHEN :sort = 'TITLE_ASC' THEN title END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'CREATED_DESC' THEN created_at END DESC,
          CASE WHEN :sort = 'LAST_OPENED_DESC' THEN COALESCE(last_opened_at, 0) END DESC,
          updated_at DESC
        """,
    )
    fun observe(folderId: String?, query: String, favoritesOnly: Boolean, sort: String): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE deleted_at IS NULL ORDER BY COALESCE(last_opened_at, updated_at) DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    fun observeById(id: String): Flow<ScriptEntity?>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getById(id: String): ScriptEntity?

    @Query("SELECT COUNT(*) FROM scripts WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>

    @Upsert suspend fun upsert(entity: ScriptEntity)
    @Upsert suspend fun upsertAll(entities: List<ScriptEntity>)

    @Query("UPDATE scripts SET deleted_at = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE scripts SET folder_id = NULL, updated_at = :now, sync_status = 'PENDING' WHERE folder_id = :folderId")
    suspend fun detachFolder(folderId: String, now: Long)

    @Query("UPDATE scripts SET last_opened_at = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("SELECT * FROM scripts WHERE sync_status != 'SYNCED'")
    suspend fun pending(): List<ScriptEntity>

    @Query("UPDATE scripts SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("DELETE FROM scripts WHERE deleted_at IS NOT NULL AND sync_status = 'SYNCED'")
    suspend fun purgeSyncedTombstones()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM script_folders WHERE deleted_at IS NULL ORDER BY sort_index, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM script_folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Upsert suspend fun upsert(entity: FolderEntity)
    @Upsert suspend fun upsertAll(entities: List<FolderEntity>)

    @Query("UPDATE script_folders SET deleted_at = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT * FROM script_folders WHERE sync_status != 'SYNCED'")
    suspend fun pending(): List<FolderEntity>

    @Query("UPDATE script_folders SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE deleted_at IS NULL AND (:status IS NULL OR status = :status) ORDER BY updated_at DESC")
    fun observe(status: String?): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Upsert suspend fun upsert(entity: ProjectEntity)
    @Upsert suspend fun upsertAll(entities: List<ProjectEntity>)

    @Query("UPDATE projects SET deleted_at = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT * FROM projects WHERE sync_status != 'SYNCED'")
    suspend fun pending(): List<ProjectEntity>

    @Query("UPDATE projects SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets WHERE deleted_at IS NULL AND project_id = :projectId ORDER BY created_at")
    fun observeForProject(projectId: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE deleted_at IS NULL AND project_id = :projectId ORDER BY created_at")
    suspend fun forProject(projectId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE deleted_at IS NULL AND (:kind IS NULL OR kind = :kind) ORDER BY created_at DESC")
    fun observeAll(kind: String?): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getById(id: String): MediaAssetEntity?

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media_assets WHERE deleted_at IS NULL")
    fun observeTotalBytes(): Flow<Long>

    @Upsert suspend fun upsert(entity: MediaAssetEntity)

    @Query("UPDATE media_assets SET deleted_at = :now, sync_status = 'PENDING' WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE media_assets SET deleted_at = :now, sync_status = 'PENDING' WHERE project_id = :projectId")
    suspend fun softDeleteForProject(projectId: String, now: Long)

    @Query("SELECT * FROM media_assets WHERE sync_status != 'SYNCED'")
    suspend fun pending(): List<MediaAssetEntity>

    @Query("UPDATE media_assets SET sync_status = 'SYNCED', cloud_path = :cloudPath WHERE id = :id")
    suspend fun markSynced(id: String, cloudPath: String?)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE project_id = :projectId")
    suspend fun get(projectId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE project_id = :projectId")
    fun observe(projectId: String): Flow<DraftEntity?>

    @Query("SELECT d.* FROM drafts d JOIN projects p ON p.id = d.project_id WHERE p.deleted_at IS NULL ORDER BY d.updated_at DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Upsert suspend fun upsert(entity: DraftEntity)

    @Query("DELETE FROM drafts WHERE project_id = :projectId")
    suspend fun delete(projectId: String)

    @Query("SELECT * FROM drafts WHERE sync_status != 'SYNCED'")
    suspend fun pending(): List<DraftEntity>

    @Query("UPDATE drafts SET sync_status = 'SYNCED' WHERE project_id = :projectId AND revision = :revision")
    suspend fun markSynced(projectId: String, revision: Long)
}

@Dao
interface BeautyPresetDao {
    @Query("SELECT * FROM beauty_presets WHERE deleted_at IS NULL ORDER BY built_in DESC, updated_at DESC")
    fun observeAll(): Flow<List<BeautyPresetEntity>>

    @Upsert suspend fun upsert(entity: BeautyPresetEntity)
    @Upsert suspend fun upsertAll(entities: List<BeautyPresetEntity>)

    @Query("UPDATE beauty_presets SET deleted_at = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id AND built_in = 0")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM beauty_presets WHERE deleted_at IS NULL AND built_in = 0")
    suspend fun customCount(): Int

    @Query("SELECT * FROM beauty_presets WHERE sync_status != 'SYNCED' AND built_in = 0")
    suspend fun pending(): List<BeautyPresetEntity>

    @Query("UPDATE beauty_presets SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface PrompterPresetDao {
    @Query("SELECT * FROM prompter_presets WHERE deleted_at IS NULL ORDER BY built_in DESC, updated_at DESC")
    fun observeAll(): Flow<List<PrompterPresetEntity>>

    @Upsert suspend fun upsert(entity: PrompterPresetEntity)
    @Upsert suspend fun upsertAll(entities: List<PrompterPresetEntity>)

    @Query("UPDATE prompter_presets SET deleted_at = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id AND built_in = 0")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT * FROM prompter_presets WHERE sync_status != 'SYNCED' AND built_in = 0")
    suspend fun pending(): List<PrompterPresetEntity>

    @Query("UPDATE prompter_presets SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)
}

@Dao
interface SyncCursorDao {
    @Query("SELECT last_pulled_at FROM sync_cursors WHERE collection = :collection")
    suspend fun get(collection: String): Long?

    @Upsert suspend fun upsert(entity: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors")
    suspend fun clear()
}

@Dao
interface AiUsageDao {
    @Upsert suspend fun insert(entity: AiUsageEntity)

    @Query("SELECT COALESCE(SUM(credits), 0) FROM ai_usage WHERE created_at >= :since")
    fun observeUsedSince(since: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(credits), 0) FROM ai_usage WHERE created_at >= :since")
    suspend fun usedSince(since: Long): Int
}

/** Transaction helpers spanning DAOs. */
@Dao
interface MaintenanceDao {
    @Transaction
    @Query("DELETE FROM scripts WHERE deleted_at IS NOT NULL AND deleted_at < :before AND sync_status = 'SYNCED'")
    suspend fun purgeOldScriptTombstones(before: Long)

    @Query("DELETE FROM projects WHERE deleted_at IS NOT NULL AND deleted_at < :before AND sync_status = 'SYNCED'")
    suspend fun purgeOldProjectTombstones(before: Long)
}
