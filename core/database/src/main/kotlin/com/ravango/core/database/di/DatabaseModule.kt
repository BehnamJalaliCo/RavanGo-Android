package com.ravango.core.database.di

import android.content.Context
import androidx.room.Room
import com.ravango.core.database.RavanGoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RavanGoDatabase =
        Room.databaseBuilder(context, RavanGoDatabase::class.java, "ravango.db")
            // Explicit migrations are added per schema version; exported schemas live in core/database/schemas.
            .build()

    @Provides fun scriptDao(db: RavanGoDatabase) = db.scriptDao()
    @Provides fun folderDao(db: RavanGoDatabase) = db.folderDao()
    @Provides fun projectDao(db: RavanGoDatabase) = db.projectDao()
    @Provides fun mediaAssetDao(db: RavanGoDatabase) = db.mediaAssetDao()
    @Provides fun draftDao(db: RavanGoDatabase) = db.draftDao()
    @Provides fun beautyPresetDao(db: RavanGoDatabase) = db.beautyPresetDao()
    @Provides fun prompterPresetDao(db: RavanGoDatabase) = db.prompterPresetDao()
    @Provides fun syncCursorDao(db: RavanGoDatabase) = db.syncCursorDao()
    @Provides fun aiUsageDao(db: RavanGoDatabase) = db.aiUsageDao()
    @Provides fun maintenanceDao(db: RavanGoDatabase) = db.maintenanceDao()
}
