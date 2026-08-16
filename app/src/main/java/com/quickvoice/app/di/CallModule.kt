package com.quickvoice.app.di

import com.quickvoice.core.call.CallController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the single [CallController] instance. It is the process-wide source of
 * truth for the active call and must therefore be scoped to the singleton
 * component so the UI, the transports and Quick Voice all see the same session.
 */
@Module
@InstallIn(SingletonComponent::class)
object CallModule {

    @Provides
    @Singleton
    fun provideCallController(): CallController = CallController()
}
