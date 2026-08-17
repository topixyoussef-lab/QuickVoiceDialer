package com.quickvoice.core.quickvoice

import com.quickvoice.core.call.CallController
import com.quickvoice.core.data.repository.ContactsRepository
import com.quickvoice.core.data.repository.SettingsRepository
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.VoiceMessage
import com.quickvoice.core.quickvoice.player.QuickVoicePlayer
import com.quickvoice.core.quickvoice.recorder.QuickVoiceRecorder
import com.quickvoice.core.telecom.sim.SimCallBridge
import com.quickvoice.core.voip.voip.VoipCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the Quick Voice button currently shows to the user. */
sealed class QuickVoiceStatus {
    data object Idle : QuickVoiceStatus()
    data class Armed(val reason: String) : QuickVoiceStatus()
    data class Recording(val elapsedMs: Long) : QuickVoiceStatus()
    data class Sending(val detail: String) : QuickVoiceStatus()
    data class Sent(val detail: String) : QuickVoiceStatus()
    data class Error(val message: String) : QuickVoiceStatus()
}

data class VoiceMessageTarget(val peerId: String, val displayName: String)

data class QuickVoiceUiState(
    val enabled: Boolean = false,
    val maxDurationMs: Long = 3_000L,
    val status: QuickVoiceStatus = QuickVoiceStatus.Idle,
    val isRecording: Boolean = false,
    val messageTarget: VoiceMessageTarget? = null,
)

/**
 * The Quick Voice brain. It:
 *  1. auto-arms when a call stays unanswered for [QuickVoiceSettings.autoActivateAfterMs];
 *  2. turns the loudspeaker on automatically (Telecom Call API for SIM, WebRTC ADM for VoIP);
 *  3. records a 2-5s clip on press, and on release delivers it:
 *       - VoIP live call  -> WebRTC DataChannel (real-time, no server hop);
 *       - otherwise       -> voice message via the signaling server;
 *       - SIM live without the peer having VoIP -> plays the clip aloud on the speaker
 *         (the honest closest legal alternative: SIM call audio cannot be injected).
 */
@Singleton
class QuickVoiceController @Inject constructor(
    private val settings: SettingsRepository,
    private val callController: CallController,
    private val simCallBridge: SimCallBridge,
    private val voipCallManager: VoipCallManager,
    private val contactsRepository: ContactsRepository,
    private val recorder: QuickVoiceRecorder,
    private val player: QuickVoicePlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(QuickVoiceUiState())
    val uiState: StateFlow<QuickVoiceUiState> = _uiState.asStateFlow()

    private var autoActivateMs = 15_000L
    private var autoEnableSpeaker = true
    private var armJob: Job? = null

    init {
        scope.launch { collectSettings() }
        scope.launch { observeCallForAutoArm() }
        scope.launch { collectFinishedRecordings() }
        scope.launch {
            voipCallManager.incomingVoiceMessages.collect { message -> player.play(message) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch { settings.setQuickVoiceEnabled(enabled) }
    }

    // ------------------------------------------------------------- recording

    /** A completed recording (finger lifted or max duration reached) is delivered once. */
    private suspend fun collectFinishedRecordings() {
        recorder.finished.collect { file ->
            if (file != null) {
                finalizeRecording(file)
            }
        }
    }

    fun startHoldToTalk() {
        val state = _uiState.value
        if (!state.enabled || state.isRecording) return
        startRecording()
    }

    /** Called when the user lifts their finger; the finished file is delivered by the collector. */
    fun stopHoldToTalk() {
        if (!_uiState.value.isRecording) return
        if (!recorder.stop()) {
            _uiState.value = _uiState.value.copy(isRecording = false, status = QuickVoiceStatus.Idle)
        }
    }

    // ------------------------------------------------------ leave-a-message mode

    /** Arms a "leave a message" recording for [peerId] without requiring an active call. */
    fun beginMessageMode(peerId: String, displayName: String) {
        val trimmed = peerId.trim()
        if (trimmed.isBlank()) return
        _uiState.value = _uiState.value.copy(
            messageTarget = VoiceMessageTarget(peerId = trimmed, displayName = displayName),
            status = QuickVoiceStatus.Armed("Hold to talk"),
        )
    }

    /** Starts recording a message for [peerId] right away (press). */
    fun startMessageTo(peerId: String, displayName: String) {
        if (peerId.isBlank()) return
        beginMessageMode(peerId, displayName)
        if (_uiState.value.isRecording) return
        startRecording()
    }

    /** Stops the leave-a-message recording (release); the file is delivered by the collector. */
    fun stopMessageTo() {
        stopHoldToTalk()
    }

    /** Aborts an active leave-a-message recording and clears the target. */
    fun cancelMessageMode() {
        recorder.cancel()
        _uiState.value = _uiState.value.copy(isRecording = false, messageTarget = null, status = QuickVoiceStatus.Idle)
    }

    private fun startRecording() {
        val state = _uiState.value
        if (state.isRecording) return
        recorder.prepare(state.maxDurationMs)
        if (!recorder.start()) {
            _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Error("Could not open the microphone"))
            return
        }

        scope.launch {
            while (recorder.isRecording()) {
                _uiState.value = _uiState.value.copy(
                    isRecording = true,
                    status = QuickVoiceStatus.Recording(recorder.elapsedMs.value),
                )
                delay(50)
            }
        }
    }

    private fun finalizeRecording(file: File) {
        val session = callController.activeSession.value
        val target = _uiState.value.messageTarget
        val bytes = file.readBytes()
        file.delete()
        val durationMs = recorder.elapsedMs.value.coerceAtMost(_uiState.value.maxDurationMs)
        val mimeType = "audio/3gpp"
        val fromName = voipCallManager.userId.value

        _uiState.value = _uiState.value.copy(isRecording = false, status = QuickVoiceStatus.Sending("Sending..."))

        scope.launch {
            when {
                // Leave-a-message mode: no call needed, delivered via the server.
                target != null -> {
                    voipCallManager.sendVoiceMessage(
                        VoiceMessage(
                            id = UUID.randomUUID().toString(),
                            fromUserId = fromName,
                            fromName = target.displayName,
                            toUserId = target.peerId,
                            mediaBytes = bytes,
                            durationMs = durationMs,
                            mimeType = mimeType,
                        )
                    )
                    _uiState.value = _uiState.value.copy(
                        messageTarget = null,
                        status = QuickVoiceStatus.Sent("Voice message sent to ${target.displayName.ifBlank { target.peerId }}"),
                    )
                }

                // VoIP call is live and the DataChannel is open -> instant delivery.
                session != null && session.type == CallType.VOIP && session.isLive &&
                    voipCallManager.isLiveChannelReady() -> {
                    voipCallManager.sendQuickVoiceLive(
                        VoiceMessage(
                            id = UUID.randomUUID().toString(),
                            fromUserId = fromName,
                            fromName = session.displayName,
                            toUserId = session.voipPeerId.orEmpty(),
                            mediaBytes = bytes,
                            durationMs = durationMs,
                            mimeType = mimeType,
                        )
                    )
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Sent("Sent"))
                }

                // Otherwise deliver through the server (works even if the peer is offline).
                session != null && session.type == CallType.VOIP -> {
                    voipCallManager.sendVoiceMessage(
                        VoiceMessage(
                            id = UUID.randomUUID().toString(),
                            fromUserId = fromName,
                            fromName = session.displayName,
                            toUserId = session.voipPeerId.orEmpty(),
                            mediaBytes = bytes,
                            durationMs = durationMs,
                            mimeType = mimeType,
                        )
                    )
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Sent("Voice message sent"))
                }

                // SIM call in progress: injection into the cellular uplink is not possible.
                session != null && session.type == CallType.SIM && session.isLive -> {
                    val peerVoipId = contactsRepository.voipIdForNumber(session.number)
                    if (peerVoipId != null) {
                        voipCallManager.sendVoiceMessage(
                            VoiceMessage(
                                id = UUID.randomUUID().toString(),
                                fromUserId = fromName,
                                fromName = session.displayName,
                                toUserId = peerVoipId,
                                mediaBytes = bytes,
                                durationMs = durationMs,
                                mimeType = mimeType,
                            )
                        )
                        _uiState.value = _uiState.value.copy(
                            status = QuickVoiceStatus.Sent("Sent to the recipient as a voice message")
                        )
                    } else {
                        callController.setSpeaker(true)
                        player.play(
                            VoiceMessage(
                                id = UUID.randomUUID().toString(),
                                fromUserId = fromName,
                                fromName = session.displayName,
                                toUserId = "",
                                mediaBytes = bytes,
                                durationMs = durationMs,
                                mimeType = mimeType,
                            )
                        )
                        _uiState.value = _uiState.value.copy(
                            status = QuickVoiceStatus.Sent(
                                "SIM calls can't inject audio - played aloud on the speaker"
                            )
                        )
                    }
                }

                session == null -> {
                    _uiState.value = _uiState.value.copy(
                        status = QuickVoiceStatus.Error("The call ended before the message could be delivered")
                    )
                }

                else -> {
                    _uiState.value = _uiState.value.copy(
                        status = QuickVoiceStatus.Error("No supported delivery channel")
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- auto-arm

    private suspend fun collectSettings() {
        settings.quickVoiceSettings.collect { s ->
            autoActivateMs = s.autoActivateAfterMs
            autoEnableSpeaker = s.autoEnableSpeaker
            _uiState.value = _uiState.value.copy(enabled = s.enabled, maxDurationMs = s.maxMessageDurationMs)
        }
    }

    private suspend fun observeCallForAutoArm() {
        callController.activeSession.collect { session ->
            if (session == null) {
                armJob?.cancel()
                armJob = null
                if (!_uiState.value.isRecording && _uiState.value.messageTarget == null) {
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Idle)
                }
                return@collect
            }
            if (!_uiState.value.enabled) return@collect

            when {
                session.type == CallType.SIM &&
                    session.direction == com.quickvoice.core.model.CallDirection.OUTGOING &&
                    session.state == CallState.RINGING -> {
                    val ringingFor = System.currentTimeMillis() - session.startedAtEpochMillis
                    val remaining = autoActivateMs - ringingFor
                    if (remaining <= 0L) {
                        armForSim("No answer yet - hold to talk")
                    } else {
                        armJob?.cancel()
                        armJob = scope.launch {
                            delay(remaining)
                            armForSim("No answer yet - hold to talk")
                        }
                    }
                }

                session.state == CallState.ACTIVE -> {
                    armJob?.cancel()
                    armJob = null
                    val reason = if (session.type == CallType.SIM) {
                        "Hold to talk (SIM can't inject audio)"
                    } else {
                        "Hold to talk"
                    }
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Armed(reason))
                }

                session.type == CallType.VOIP && session.state == CallState.RINGING -> {
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Armed("Ringing - hold to talk"))
                }

                else -> {
                    armJob?.cancel()
                    armJob = null
                    _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Idle)
                }
            }
        }
    }

    private fun armForSim(reason: String) {
        if (autoEnableSpeaker) {
            simCallBridge.setSpeakerOn()
        }
        _uiState.value = _uiState.value.copy(status = QuickVoiceStatus.Armed(reason))
    }
}
