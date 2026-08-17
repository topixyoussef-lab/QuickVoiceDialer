package com.quickvoice.core.model

/** Lifecycle state of a call, normalised across SIM and VoIP transports. */
enum class CallState {
    IDLE,
    CONNECTING,
    RINGING,
    ACTIVE,
    HOLD,
    DISCONNECTING,
    DISCONNECTED,
    MISSED,
    BLOCKED,
}
