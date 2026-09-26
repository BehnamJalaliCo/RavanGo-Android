package com.ravango.core.data.repository

import com.ravango.core.data.mapper.toEntity
import com.ravango.core.data.mapper.toModel
import com.ravango.core.database.dao.DraftDao
import com.ravango.core.database.dao.MediaAssetDao
import com.ravango.core.database.dao.ProjectDao
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.Clock
import com.ravango.core.model.Draft
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface ProjectRepository {
    fun observeProjects(status: ProjectStatus? = null): Flow<List<Project>>
    fun observeRecent(limit: Int = 8): Flow<List<Project>>
    fun observeProject(id: String): Flow<Project?>
    suspend fun getProject(id: String): Project?

    suspend fun create(title: String, aspectRatio: AspectRatioSpec, scriptId: String? = null, templateId: String? = null): Project
    suspend fun update(project: Project): Project
    suspend fun rename(id: String, title: String)
    suspend fun duplicate(id: String, copySuffix: String): Project?
    /** Soft-deletes the project and its assets; files owned by the app are removed. */
    suspend fun delete(id: String)

    fun observeAssets(projectId: String): Flow<List<MediaAsset>>
    suspend fun assets(projectId: String): List<MediaAsset>
    fun observeAllAssets(kind: MediaKind? = null): Flow<List<MediaAsset>>
    fun observeStorageBytes(): Flow<Long>
    suspend fun addAsset(asset: MediaAsset)
    suspend fun updateAsset(asset: MediaAsset)
    suspend fun getAsset(id: String): MediaAsset?
    suspend fun deleteAsset(id: String)

    suspend fun getDraft(projectId: String): Draft?
    fun observeDraft(projectId: String): Flow<Draft?>
    fun observeDrafts(): Flow<List<Draft>>
    /** Auto-save: persists the document with an incremented revision. */
    suspend fun saveDraft(projectId: String, document: EditorDocument): Draft
    suspend fun deleteDraft(projectId: String)
}

@Singleton
class OfflineFirstProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val assetDao: MediaAssetDao,
    private val draftDao: DraftDao,
    private val clock: Clock,
    private val changes: LocalChangeBus,
) : ProjectRepository {

    override fun observeProjects(status: ProjectStatus?) = projectDao.observe(status?.name).map { l -> l.map { it.toModel() } }
    override fun observeRecent(limit: Int) = projectDao.observeRecent(limit).map { l -> l.map { it.toModel() } }
    override fun observeProject(id: String) = projectDao.observeById(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }
    override suspend fun getProject(id: String) = projectDao.getById(id)?.takeIf { it.deletedAt == null }?.toModel()

    override suspend fun create(title: String, aspectRatio: AspectRatioSpec, scriptId: String?, templateId: String?): Project {
        val now = clock.now()
        val project = Project(id = newId(), title = title, aspectRatio = aspectRatio, scriptId = scriptId, templateId = templateId, createdAt = now, updatedAt = now)
        projectDao.upsert(project.toEntity())
        changes.notifyChanged(SyncCollection.PROJECTS)
        return project
    }

    override suspend fun update(project: Project): Project {
        val updated = project.copy(updatedAt = clock.now(), syncStatus = SyncStatus.PENDING)
        projectDao.upsert(updated.toEntity())
        changes.notifyChanged(SyncCollection.PROJECTS)
        return updated
    }

    override suspend fun rename(id: String, title: String) {
        getProject(id)?.let { update(it.copy(title = title)) }
    }

    override suspend fun duplicate(id: String, copySuffix: String): Project? {
        val original = getProject(id) ?: return null
        val now = clock.now()
        val copy = original.copy(id = newId(), title = "${original.title} $copySuffix".trim(), createdAt = now, updatedAt = now, lastExportUri = null, syncStatus = SyncStatus.PENDING)
        projectDao.upsert(copy.toEntity())
        assets(id).forEach { asset -> assetDao.upsert(asset.copy(id = newId(), projectId = copy.id, createdAt = now, syncStatus = SyncStatus.PENDING, cloudPath = null).toEntity()) }
        getDraft(id)?.let { draft -> draftDao.upsert(draft.copy(projectId = copy.id, document = draft.document.copy(id = newId()), revision = 1, updatedAt = now, syncStatus = SyncStatus.PENDING).toEntity()) }
        changes.notifyChanged(SyncCollection.PROJECTS)
        return copy
    }

    override suspend fun delete(id: String) {
        val now = clock.now()
        val assets = assets(id)
        projectDao.softDelete(id, now)
        assetDao.softDeleteForProject(id, now)
        draftDao.delete(id)
        // Only delete files that live in app-private storage; never touch gallery/imported originals.
        assets.forEach { asset -> if (asset.uri.startsWith("/") || asset.uri.startsWith("file:")) runCatching { File(asset.uri.removePrefix("file://")).takeIf { it.exists() }?.delete() } }
        changes.notifyChanged(SyncCollection.PROJECTS)
        changes.notifyChanged(SyncCollection.MEDIA)
    }

    override fun observeAssets(projectId: String) = assetDao.observeForProject(projectId).map { l -> l.map { it.toModel() } }
    override suspend fun assets(projectId: String) = assetDao.forProject(projectId).map { it.toModel() }
    override fun observeAllAssets(kind: MediaKind?) = assetDao.observeAll(kind?.name).map { l -> l.map { it.toModel() } }
    override fun observeStorageBytes() = assetDao.observeTotalBytes()
    override suspend fun getAsset(id: String) = assetDao.getById(id)?.toModel()

    override suspend fun addAsset(asset: MediaAsset) {
        assetDao.upsert(asset.toEntity())
        changes.notifyChanged(SyncCollection.MEDIA)
    }

    override suspend fun updateAsset(asset: MediaAsset) {
        assetDao.upsert(asset.copy(syncStatus = SyncStatus.PENDING).toEntity())
        changes.notifyChanged(SyncCollection.MEDIA)
    }

    override suspend fun deleteAsset(id: String) {
        assetDao.softDelete(id, clock.now())
        changes.notifyChanged(SyncCollection.MEDIA)
    }

    override suspend fun getDraft(projectId: String) = draftDao.get(projectId)?.toModel()
    override fun observeDraft(projectId: String) = draftDao.observe(projectId).map { it?.toModel() }
    override fun observeDrafts() = draftDao.observeAll().map { l -> l.mapNotNull { it.toModel() } }

    override suspend fun saveDraft(projectId: String, document: EditorDocument): Draft {
        val revision = (draftDao.get(projectId)?.revision ?: 0) + 1
        val draft = Draft(projectId, document, revision, clock.now(), SyncStatus.PENDING)
        draftDao.upsert(draft.toEntity())
        getProject(projectId)?.let { p ->
            if (p.status == ProjectStatus.RECORDED || p.durationUs != document.durationUs) {
                projectDao.upsert(p.copy(status = if (p.status == ProjectStatus.EXPORTED) p.status else ProjectStatus.EDITING, durationUs = document.durationUs, updatedAt = clock.now(), syncStatus = SyncStatus.PENDING).toEntity())
            }
        }
        changes.notifyChanged(SyncCollection.DRAFTS)
        return draft
    }

    override suspend fun deleteDraft(projectId: String) {
        draftDao.delete(projectId)
        changes.notifyChanged(SyncCollection.DRAFTS)
    }
}
