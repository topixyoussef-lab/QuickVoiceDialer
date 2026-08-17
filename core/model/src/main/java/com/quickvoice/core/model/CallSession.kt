package com.quickvoice.core.model

/**
 * A single active call session, normalised from either a Telecom SIM call
 * or an in-app WebRTC VoIP call.
 */
data class CallSession(
    val id: String,
    val type: CallType,
    val direction: CallDirection,
    val number: String,
    val displayName: String,
    val state: CallState,
    val audioRoute: AudioRoute = AudioRoute.UNKNOWN,
    val isMicMuted: Boolean = false,
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
    val voipPeerId: String? = null,
    /** True when the session is a one-way intercom (no caller audio until PTT). */
    val isIntercom: Boolean = false,
) {
    val isActive: Boolean get() = state == CallState.ACTIVE
    val isLive: Boolean get() = state in setOf(CallState.CONNECTING, CallState.RINGING, CallState.ACTIVE, CallState.HOLD)
}
