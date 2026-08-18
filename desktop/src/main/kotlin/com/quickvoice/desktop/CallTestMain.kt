package com.quickvoice.desktop

import com.quickvoice.core.call.CallController
import com.quickvoice.core.model.CallState
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
import java.io.File
import java.util.concurrent.TimeUnit

/** Headless end-to-end test: two desktop clients on the LAN, A calls B, B answers. */
fun main() {
    val fileA = File("D:/tmp/opencode/qvtestA.properties")
    val fileB = File("D:/tmp/opencode/qvtestB.properties")
    fileA.delete()
    fileB.delete()

    val settingsA = DesktopSettings(fileA).apply {
        setUserId("desktopA")
        setDisplayName("Desktop A")
    }
    val settingsB = DesktopSettings(fileB).apply {
        setUserId("desktopB")
        setDisplayName("Desktop B")
    }

    fun newClient() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val callControllerA = CallController()
    val callControllerB = CallController()
    val managerA = DesktopVoipManager(settingsA, FirebaseRestSignalingClient(newClient()), callControllerA)
    val managerB = DesktopVoipManager(settingsB, FirebaseRestSignalingClient(newClient()), callControllerB)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch { runCatching { managerA.startServerConnection() } }
    scope.launch { runCatching { managerB.startServerConnection() } }

    // B auto-answers incoming calls
    scope.launch {
        managerB.incomingCall.collect { info ->
            if (info != null) {
                println("[CallTest] B answering call from ${info.fromName}")
                managerB.acceptIncomingCall()
            }
        }
    }

    val deadline = System.currentTimeMillis() + 30_000
    fun waitFor(cond: () -> Boolean): Boolean {
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(300)
        }
        return false
    }

    println("[CallTest] waiting for both to register...")
    if (!waitFor { managerA.signalingState.value == SignalingState.REGISTERED && managerB.signalingState.value == SignalingState.REGISTERED }) {
        println("[CallTest] FAILED to register A=${managerA.signalingState.value} B=${managerB.signalingState.value}")
        kotlin.system.exitProcess(1)
    }
    println("[CallTest] A=${managerA.userId.value} B=${managerB.userId.value} registered")

    println("[CallTest] A placing call to desktopB...")
    managerA.placeCall("desktopB", "Desktop B")

    if (!waitFor { callControllerB.activeSession.value?.state == CallState.ACTIVE || callControllerA.activeSession.value?.state == CallState.ACTIVE }) {
        println("[CallTest] FAILED: call never became ACTIVE. A=${callControllerA.activeSession.value?.state} B=${callControllerB.activeSession.value?.state} errA=${managerA.lastError.value} errB=${managerB.lastError.value}")
        kotlin.system.exitProcess(1)
    }
    println("[CallTest] SUCCESS: call is ACTIVE on A=${callControllerA.activeSession.value?.state} and B=${callControllerB.activeSession.value?.state}")

    Thread.sleep(2_000)
    managerA.hangup()
    Thread.sleep(1_000)
    println("[CallTest] after hangup A=${callControllerA.activeSession.value?.state} B=${callControllerB.activeSession.value?.state}")

    managerA.stopServerConnection()
    managerB.stopServerConnection()
    Thread.sleep(500)
    println("[CallTest] done")
    kotlin.system.exitProcess(0)
}
