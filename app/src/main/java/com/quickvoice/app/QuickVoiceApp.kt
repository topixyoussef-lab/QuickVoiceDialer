package com.quickvoice.app

import android.app.Application
import android.util.Log
import com.quickvoice.core.data.repository.SettingsRepository
import com.quickvoice.core.voip.voip.BackgroundVoipService
import com.quickvoice.core.voip.voip.VoipCallManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import javax.inject.Inject

/**
 * Application entry point. Hilt wires the whole graph (repositories, call transports,
 * Quick Voice engine). It also auto-connects to the configured VoIP signaling server
 * so users don't have to press Connect, and it captures crashes to a file so the app
 * can show what went wrong instead of failing silently.
 */
@HiltAndroidApp
class QuickVoiceApp : Application() {

    @Inject lateinit var voipCallManager: VoipCallManager
    @Inject lateinit var settings: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = getExternalFilesDir(null)
                if (dir != null) {
                    val file = File(dir, "last_crash.txt")
                    file.writeText("${Date()}\n${Log.getStackTraceString(throwable)}")
                }
            }
            Log.e("QuickVoiceCrash", "Uncaught exception", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        appScope.launch {
            // Small delay so the launcher activity is up: starting the foreground
            // service counts as a foreground start (required on Android 12+).
            delay(500)
            val backgroundEnabled = runCatching { settings.backgroundServiceEnabled.first() }.getOrDefault(true)
            if (backgroundEnabled) {
                runCatching { BackgroundVoipService.start(this@QuickVoiceApp) }
            } else {
                runCatching { voipCallManager.startServerConnection() }
            }
        }
    }
}
