package com.quickvoice.feature.call

import androidx.lifecycle.ViewModel
import com.quickvoice.core.call.CallController
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.quickvoice.QuickVoiceController
import com.quickvoice.core.voip.voip.VoipCallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callController: CallController,
    private val voipCallManager: VoipCallManager,
    val quickVoiceController: QuickVoiceController,
) : ViewModel() {

    val session: StateFlow<CallSession?> = callController.activeSession
    val justEnded: StateFlow<CallSession?> = callController.justEnded
    val recording: StateFlow<Boolean> = voipCallManager.recording
    val lastError: StateFlow<String?> = voipCallManager.lastError

    fun endCall() = callController.endCall()
    fun answer() = callController.answer()
    fun reject() = callController.reject()
    fun toggleMute() = callController.toggleMute()
    fun setMicMuted(muted: Boolean) = callController.setMicMuted(muted)
    fun toggleSpeaker() = callController.toggleSpeaker()
    fun consumeJustEnded() = callController.consumeJustEnded()

    fun startRecording() = voipCallManager.startRecording()
    fun stopRecording() = voipCallManager.stopRecording()
}
