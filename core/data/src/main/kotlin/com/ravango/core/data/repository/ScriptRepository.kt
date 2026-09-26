package com.ravango.core.data.repository

import com.ravango.core.data.mapper.toEntity
import com.ravango.core.data.mapper.toModel
import com.ravango.core.database.dao.FolderDao
import com.ravango.core.database.dao.ScriptDao
import com.ravango.core.model.Clock
import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.core.model.ScriptSortOrder
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ScriptFilter(
    val folderId: String? = null,
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val sort: ScriptSortOrder = ScriptSortOrder.UPDATED_DESC,
)

interface ScriptRepository {
    fun observeScripts(filter: ScriptFilter = ScriptFilter()): Flow<List<Script>>
    fun observeRecent(limit: Int = 5): Flow<List<Script>>
    fun observeScript(id: String): Flow<Script?>
    fun observeCount(): Flow<Int>
    suspend fun getScript(id: String): Script?

    suspend fun create(title: String, body: String, folderId: String? = null): Script
    /** Saves user edits; bumps updatedAt and marks for sync. */
    suspend fun save(script: Script): Script
    suspend fun duplicate(id: String, copySuffix: String): Script?
    suspend fun delete(id: String)
    suspend fun move(id: String, folderId: String?)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun markOpened(id: String)
    suspend fun saveStartOffset(id: String, charOffset: Int)
    suspend fun savePrompterSettings(id: String, settings: TeleprompterSettings?)

    fun observeFolders(): Flow<List<ScriptFolder>>
    suspend fun createFolder(name: String, colorArgb: Long): ScriptFolder
    suspend fun renameFolder(id: String, name: String)
    /** Deletes the folder; its scripts move to "no folder". */
    suspend fun deleteFolder(id: String)
}

@Singleton
class OfflineFirstScriptRepository @Inject constructor(
    private val scriptDao: ScriptDao,
    private val folderDao: FolderDao,
    private val clock: Clock,
    private val changes: LocalChangeBus,
) : ScriptRepository {

    override fun observeScripts(filter: ScriptFilter): Flow<List<Script>> =
        scriptDao.observe(filter.folderId, filter.query.trim(), filter.favoritesOnly, filter.sort.name).map { list -> list.map { it.toModel() } }

    override fun observeRecent(limit: Int): Flow<List<Script>> = scriptDao.observeRecent(limit).map { list -> list.map { it.toModel() } }
    override fun observeScript(id: String): Flow<Script?> = scriptDao.observeById(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }
    override fun observeCount(): Flow<Int> = scriptDao.observeCount()
    override suspend fun getScript(id: String): Script? = scriptDao.getById(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun create(title: String, body: String, folderId: String?): Script {
        val now = clock.now()
        val script = Script(id = newId(), title = title, body = body, folderId = folderId, createdAt = now, updatedAt = now)
        scriptDao.upsert(script.toEntity())
        changes.notifyChanged(SyncCollection.SCRIPTS)
        return script
    }

    override suspend fun save(script: Script): Script {
        val updated = script.copy(updatedAt = clock.now(), syncStatus = SyncStatus.PENDING)
        scriptDao.upsert(updated.toEntity())
        changes.notifyChanged(SyncCollection.SCRIPTS)
        return updated
    }

    override suspend fun duplicate(id: String, copySuffix: String): Script? {
        val original = getScript(id) ?: return null
        val now = clock.now()
        val copy = original.copy(id = newId(), title = "${original.title} $copySuffix".trim(), createdAt = now, updatedAt = now, lastOpenedAt = null, syncStatus = SyncStatus.PENDING)
        scriptDao.upsert(copy.toEntity())
        changes.notifyChanged(SyncCollection.SCRIPTS)
        return copy
    }

    override suspend fun delete(id: String) {
        scriptDao.softDelete(id, clock.now())
        changes.notifyChanged(SyncCollection.SCRIPTS)
    }

    override suspend fun move(id: String, folderId: String?) = mutate(id) { it.copy(folderId = folderId) }
    override suspend fun setFavorite(id: String, favorite: Boolean) = mutate(id) { it.copy(isFavorite = favorite) }
    override suspend fun saveStartOffset(id: String, charOffset: Int) = mutate(id) { it.copy(startCharOffset = charOffset.coerceAtLeast(0)) }
    override suspend fun savePrompterSettings(id: String, settings: TeleprompterSettings?) = mutate(id) { it.copy(prompterSettings = settings) }

    override suspend fun markOpened(id: String) = scriptDao.markOpened(id, clock.now())

    private suspend fun mutate(id: String, transform: (Script) -> Script) {
        val current = getScript(id) ?: return
        save(transform(current))
    }

    override fun observeFolders(): Flow<List<ScriptFolder>> = folderDao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun createFolder(name: String, colorArgb: Long): ScriptFolder {
        val now = clock.now()
        val folder = ScriptFolder(id = newId(), name = name, colorArgb = colorArgb, createdAt = now, updatedAt = now)
        folderDao.upsert(folder.toEntity())
        changes.notifyChanged(SyncCollection.FOLDERS)
        return folder
    }

    override suspend fun renameFolder(id: String, name: String) {
        val folder = folderDao.getById(id)?.toModel() ?: return
        folderDao.upsert(folder.copy(name = name, updatedAt = clock.now(), syncStatus = SyncStatus.PENDING).toEntity())
        changes.notifyChanged(SyncCollection.FOLDERS)
    }

    override suspend fun deleteFolder(id: String) {
        val now = clock.now()
        scriptDao.detachFolder(id, now)
        folderDao.softDelete(id, now)
        changes.notifyChanged(SyncCollection.FOLDERS)
        changes.notifyChanged(SyncCollection.SCRIPTS)
    }
}
