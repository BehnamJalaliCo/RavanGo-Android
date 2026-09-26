package com.ravango.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ravango.core.database.dao.AiUsageDao
import com.ravango.core.database.dao.BeautyPresetDao
import com.ravango.core.database.dao.DraftDao
import com.ravango.core.database.dao.FolderDao
import com.ravango.core.database.dao.MaintenanceDao
import com.ravango.core.database.dao.MediaAssetDao
import com.ravango.core.database.dao.ProjectDao
import com.ravango.core.database.dao.PrompterPresetDao
import com.ravango.core.database.dao.ScriptDao
import com.ravango.core.database.dao.SyncCursorDao
import com.ravango.core.database.entity.AiUsageEntity
import com.ravango.core.database.entity.BeautyPresetEntity
import com.ravango.core.database.entity.DraftEntity
import com.ravango.core.database.entity.FolderEntity
import com.ravango.core.database.entity.MediaAssetEntity
import com.ravango.core.database.entity.ProjectEntity
import com.ravango.core.database.entity.PrompterPresetEntity
import com.ravango.core.database.entity.ScriptEntity
import com.ravango.core.database.entity.SyncCursorEntity

@Database(
    entities = [
        ScriptEntity::class,
        FolderEntity::class,
        ProjectEntity::class,
        MediaAssetEntity::class,
        DraftEntity::class,
        BeautyPresetEntity::class,
        PrompterPresetEntity::class,
        SyncCursorEntity::class,
        AiUsageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RavanGoDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun folderDao(): FolderDao
    abstract fun projectDao(): ProjectDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun draftDao(): DraftDao
    abstract fun beautyPresetDao(): BeautyPresetDao
    abstract fun prompterPresetDao(): PrompterPresetDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun maintenanceDao(): MaintenanceDao
}
