package com.quickvoice.core.voip.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.quickvoice.core.voip.signaling.FirebaseSignalingClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoipModule {

    @Provides
    @Singleton
    fun provideFirebaseSignalingClient(): FirebaseSignalingClient =
        FirebaseSignalingClient(
            database = FirebaseDatabase.getInstance(),
            auth = FirebaseAuth.getInstance(),
            messaging = FirebaseMessaging.getInstance(),
        )

    @Provides
    @Singleton
    fun provideAudioDeviceModule(@ApplicationContext context: Context): JavaAudioDeviceModule =
        JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

    @Provides
    @Singleton
    fun providePeerConnectionFactory(
        @ApplicationContext context: Context,
        audioDeviceModule: JavaAudioDeviceModule,
    ): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }
}
