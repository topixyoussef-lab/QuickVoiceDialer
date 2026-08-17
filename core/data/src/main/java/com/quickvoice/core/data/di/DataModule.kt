package com.quickvoice.core.data.di

import android.content.Context
import androidx.room.Room
import com.quickvoice.core.data.db.CallDao
import com.quickvoice.core.data.db.CallDatabase
import com.quickvoice.core.data.db.ContactVoipLinkDao
import com.quickvoice.core.data.db.SavedNumberDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CallDatabase =
        Room.databaseBuilder(context, CallDatabase::class.java, "quickvoice.db")
            .addMigrations(CallDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideCallDao(db: CallDatabase): CallDao = db.callDao()

    @Provides
    fun provideContactVoipLinkDao(db: CallDatabase): ContactVoipLinkDao = db.contactVoipLinkDao()

    @Provides
    fun provideSavedNumberDao(db: CallDatabase): SavedNumberDao = db.savedNumberDao()
}
