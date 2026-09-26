package com.ravango.core.data.di

import com.ravango.core.data.repository.DefaultPresetRepository
import com.ravango.core.data.repository.OfflineFirstProjectRepository
import com.ravango.core.data.repository.OfflineFirstScriptRepository
import com.ravango.core.data.repository.PresetRepository
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.ScriptRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun scripts(impl: OfflineFirstScriptRepository): ScriptRepository
    @Binds abstract fun projects(impl: OfflineFirstProjectRepository): ProjectRepository
    @Binds abstract fun presets(impl: DefaultPresetRepository): PresetRepository
}
