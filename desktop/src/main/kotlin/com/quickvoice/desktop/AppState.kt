package com.quickvoice.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.quickvoice.core.call.CallController
import com.quickvoice.core.model.CallSession
import com.quickvoice.desktop.settings.DesktopSettings
import com.quickvoice.desktop.voip.DesktopVoipManager
import com.quickvoice.desktop.voip.IncomingCallInfo
import com.quickvoice.desktop.voip.SignalingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Holds the UI-facing state and wires the desktop app together. */
class AppState(
    val settings: DesktopSettings,
    val manager: DesktopVoipManager,
    val callController: CallController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val signalingState: StateFlow<SignalingState> = manager.signalingState
    val userId: StateFlow<String> = manager.userId
    val incomingCall: StateFlow<IncomingCallInfo?> = manager.incomingCall
    val lastError: StateFlow<String?> = manager.lastError
    val activeSession: StateFlow<CallSession?> = callController.activeSession

    var user by mutableStateOf(settings.currentUserId())
    var displayName by mutableStateOf(settings.currentDisplayName())

    fun connect() {
        scope.launch { manager.startServerConnection() }
    }

    fun disconnect() {
        manager.stopServerConnection()
    }

    fun saveSettingsAndConnect() {
        settings.setUserId(user)
        settings.setDisplayName(displayName)
        scope.launch { manager.startServerConnection() }
    }

    fun call(peer: String) {
        val trimmed = peer.trim()
        if (trimmed.isBlank()) {
            manager.showError("Enter a number to call first")
            return
        }
        manager.placeCall(trimmed, trimmed)
    }

    fun acceptIncoming() = manager.acceptIncomingCall()
    fun declineIncoming() = manager.declineIncomingCall()
    fun hangup() = manager.hangup()
    fun toggleMute() = manager.toggleMute()
    fun clearError() = manager.clearError()
}
