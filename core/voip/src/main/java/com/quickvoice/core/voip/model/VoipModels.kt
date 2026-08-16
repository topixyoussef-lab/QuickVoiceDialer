package com.quickvoice.core.voip.model

/** Lifecycle of the connection to our self-hosted signaling server. */
enum class SignalingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REGISTERED,
}

/** An incoming VoIP call that the user has not yet answered. */
data class IncomingCallInfo(
    val callId: String,
    val fromUserId: String,
    val fromName: String,
    val offerSdp: String,
    /** "call" for a normal call, "intercom" for a one-way intercom. */
    val mode: String = "call",
)
