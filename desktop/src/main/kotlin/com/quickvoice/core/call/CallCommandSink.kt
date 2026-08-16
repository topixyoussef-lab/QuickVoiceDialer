package com.quickvoice.core.call

import com.quickvoice.core.model.AudioRoute

/**
 * Opaque command surface that a call transport (SIM Telecom or VoIP) implements
 * so the UI can drive whichever call is currently active without knowing its type.
 */
interface CallCommandSink {
    fun endCall(sessionId: String)
    fun answer(sessionId: String)
    fun reject(sessionId: String)
    fun setMicMuted(sessionId: String, muted: Boolean)
    fun setAudioRoute(sessionId: String, route: AudioRoute)
}
