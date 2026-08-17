package com.quickvoice.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.quickvoice.core.call.CallController
import com.quickvoice.desktop.settings.DesktopSettings
import com.quickvoice.desktop.signaling.SignalingClient
import com.quickvoice.desktop.ui.App
import com.quickvoice.desktop.voip.DesktopVoipManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun main() = application {
    val settings = DesktopSettings(DesktopSettings.defaultFile())
    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val signalingClient = SignalingClient(okHttpClient)
    val callController = CallController()
    val manager = DesktopVoipManager(settings, signalingClient, callController)
    val appState = AppState(settings, manager, callController)

    LaunchedEffect(Unit) {
        runCatching { manager.startServerConnection() }
    }

    Window(
        onCloseRequest = {
            manager.stopServerConnection()
            exitApplication()
        },
        title = "QuickVoice Dialer (Windows)",
        state = rememberWindowState(width = 900.dp, height = 640.dp),
    ) {
        App(appState)
    }
}
