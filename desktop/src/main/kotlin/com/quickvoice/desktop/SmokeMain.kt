package com.quickvoice.desktop

import com.quickvoice.core.call.CallController
import com.quickvoice.desktop.settings.DesktopSettings
import com.quickvoice.desktop.signaling.FirebaseRestSignalingClient
import com.quickvoice.desktop.voip.DesktopVoipManager
import com.quickvoice.desktop.voip.SignalingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Headless smoke test: connects to the signaling server and prints state changes. */
fun main() {
    val settings = DesktopSettings(DesktopSettings.defaultFile())
    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val signalingClient = FirebaseRestSignalingClient(okHttpClient)
    val callController = CallController()
    val manager = DesktopVoipManager(settings, signalingClient, callController)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
        var last = SignalingState.DISCONNECTED
        manager.signalingState.collect { state ->
            if (state != last) {
                last = state
                println("[SmokeMain] signaling state -> $state")
            }
        }
    }

    println("[SmokeMain] starting...")
    scope.launch {
        runCatching { manager.startServerConnection() }.onFailure {
            println("[SmokeMain] start failed: ${it.message}")
        }
    }

    val deadline = System.currentTimeMillis() + 25_000
    var ok = false
    while (System.currentTimeMillis() < deadline) {
        if (manager.signalingState.value == SignalingState.REGISTERED) {
            println("[SmokeMain] SUCCESS: registered as ${manager.userId.value}")
            ok = true
            break
        }
        Thread.sleep(500)
    }

    if (!ok) {
        println("[SmokeMain] FAILED: last state = ${manager.signalingState.value}, error = ${manager.lastError.value}")
    }

    manager.stopServerConnection()
    Thread.sleep(500)
    println("[SmokeMain] done")
    kotlin.system.exitProcess(0)
}
