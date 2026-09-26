package com.ravango.core.data.repository

import com.ravango.core.data.mapper.toEntity
import com.ravango.core.data.mapper.toModel
import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.database.dao.BeautyPresetDao
import com.ravango.core.database.dao.PrompterPresetDao
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.BeautyState
import com.ravango.core.model.Clock
import com.ravango.core.model.SyncStatus
import com.ravango.core.model.TeleprompterPreset
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.model.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface PresetRepository {
    fun observeBeautyPresets(): Flow<List<BeautyPreset>>
    suspend fun saveBeautyPreset(name: String, state: BeautyState, id: String? = null): BeautyPreset
    suspend fun deleteBeautyPreset(id: String)
    suspend fun customBeautyPresetCount(): Int

    fun observePrompterPresets(): Flow<List<TeleprompterPreset>>
    suspend fun savePrompterPreset(name: String, settings: TeleprompterSettings, id: String? = null): TeleprompterPreset
    suspend fun deletePrompterPreset(id: String)
}

@Singleton
class DefaultPresetRepository @Inject constructor(
    private val beautyDao: BeautyPresetDao,
    private val prompterDao: PrompterPresetDao,
    private val clock: Clock,
    private val changes: LocalChangeBus,
) : PresetRepository {

    private val seedMutex = Mutex()
    @Volatile private var seeded = false

    /** Built-in presets are (re)written on first observation so updates to defaults ship with app updates. */
    private suspend fun ensureSeeded() = seedMutex.withLock {
        if (seeded) return@withLock
        beautyDao.upsertAll(BuiltInPresets.beauty.map { it.copy(syncStatus = SyncStatus.SYNCED).toEntity() })
        prompterDao.upsertAll(BuiltInPresets.prompter.map { it.copy(syncStatus = SyncStatus.SYNCED).toEntity() })
        seeded = true
    }

    override fun observeBeautyPresets(): Flow<List<BeautyPreset>> =
        beautyDao.observeAll().onStart { ensureSeeded() }.map { l -> l.map { it.toModel() } }

    override suspend fun saveBeautyPreset(name: String, state: BeautyState, id: String?): BeautyPreset {
        val preset = BeautyPreset(id = id ?: newId(), name = name, state = state, builtIn = false, updatedAt = clock.now(), syncStatus = SyncStatus.PENDING)
        beautyDao.upsert(preset.toEntity())
        changes.notifyChanged(SyncCollection.BEAUTY_PRESETS)
        return preset
    }

    override suspend fun deleteBeautyPreset(id: String) {
        beautyDao.softDelete(id, clock.now())
        changes.notifyChanged(SyncCollection.BEAUTY_PRESETS)
    }

    override suspend fun customBeautyPresetCount(): Int = beautyDao.customCount()

    override fun observePrompterPresets(): Flow<List<TeleprompterPreset>> =
        prompterDao.observeAll().onStart { ensureSeeded() }.map { l -> l.map { it.toModel() } }

    override suspend fun savePrompterPreset(name: String, settings: TeleprompterSettings, id: String?): TeleprompterPreset {
        val preset = TeleprompterPreset(id = id ?: newId(), name = name, settings = settings, builtIn = false, updatedAt = clock.now(), syncStatus = SyncStatus.PENDING)
        prompterDao.upsert(preset.toEntity())
        changes.notifyChanged(SyncCollection.PROMPTER_PRESETS)
        return preset
    }

    override suspend fun deletePrompterPreset(id: String) {
        prompterDao.softDelete(id, clock.now())
        changes.notifyChanged(SyncCollection.PROMPTER_PRESETS)
    }
}
