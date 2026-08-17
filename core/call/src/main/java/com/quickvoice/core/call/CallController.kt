package com.quickvoice.core.call

import com.quickvoice.core.model.AudioRoute
import com.quickvoice.core.model.CallSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the currently active call, regardless of whether it
 * is a SIM call (Telecom) or a VoIP call (WebRTC).
 *
 * The owning transport publishes its [CallSession] plus a [CallCommandSink]; the UI
 * only talks to [CallController]. Thread-safety: all state is read/written on the
 * main thread, so the value objects are safe to emit.
 */
class CallController {

    private val _activeSession = MutableStateFlow<CallSession?>(null)
    val activeSession: StateFlow<CallSession?> = _activeSession.asStateFlow()

    /** One-shot signal used to navigate the UI back after a call ends. */
    private val _justEnded = MutableStateFlow<CallSession?>(null)
    val justEnded: StateFlow<CallSession?> = _justEnded.asStateFlow()

    @Volatile
    private var activeSink: CallCommandSink? = null

    /**
     * Publish or clear the active call. [session] == null clears the current call
     * only if it is the one this transport owns.
     */
    fun publish(session: CallSession?, sink: CallCommandSink?) {
        if (session == null) {
            _activeSession.value?.let { _justEnded.value = it }
            _activeSession.value = null
            activeSink = null
        } else {
            activeSink = sink
            _activeSession.value = session
        }
    }

    /** Apply an immutable transform to the active session. */
    fun update(transform: (CallSession) -> CallSession) {
        val current = _activeSession.value ?: return
        _activeSession.value = transform(current)
    }

    fun consumeJustEnded() {
        _justEnded.value = null
    }

    fun endCall() {
        val session = _activeSession.value ?: return
        activeSink?.endCall(session.id)
    }

    fun answer() {
        val session = _activeSession.value ?: return
        activeSink?.answer(session.id)
    }

    fun reject() {
        val session = _activeSession.value ?: return
        activeSink?.reject(session.id)
    }

    fun toggleMute() {
        val session = _activeSession.value ?: return
        activeSink?.setMicMuted(session.id, !session.isMicMuted)
    }

    /** Direct mute/unmute (used for intercom hold-to-talk). */
    fun setMicMuted(muted: Boolean) {
        val session = _activeSession.value ?: return
        activeSink?.setMicMuted(session.id, muted)
    }

    fun setSpeaker(on: Boolean) {
        val session = _activeSession.value ?: return
        val route = if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE
        activeSink?.setAudioRoute(session.id, route)
    }

    fun toggleSpeaker() {
        val session = _activeSession.value ?: return
        val on = session.audioRoute != AudioRoute.SPEAKER
        activeSink?.setAudioRoute(session.id, if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE)
    }
}
