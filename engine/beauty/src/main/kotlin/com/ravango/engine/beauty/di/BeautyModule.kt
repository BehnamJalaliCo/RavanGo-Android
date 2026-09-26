package com.ravango.engine.beauty.di

import com.ravango.engine.beauty.BeautyEngine
import com.ravango.engine.beauty.DefaultBeautyEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BeautyModule {
    @Binds
    abstract fun bindBeautyEngine(impl: DefaultBeautyEngine): BeautyEngine
}
