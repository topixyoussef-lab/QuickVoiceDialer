package com.quickvoice.core.telecom.di

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelecomModule {

    @Provides
    @Singleton
    fun provideTelecomManager(@ApplicationContext context: Context): TelecomManager =
        context.getSystemService(TelecomManager::class.java)

    @Provides
    @Singleton
    fun provideTelephonyManager(@ApplicationContext context: Context): TelephonyManager =
        context.getSystemService(TelephonyManager::class.java)
}
