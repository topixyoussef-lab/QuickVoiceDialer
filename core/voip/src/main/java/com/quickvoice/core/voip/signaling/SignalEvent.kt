package com.quickvoice.core.voip.signaling

import com.quickvoice.core.model.VoiceMessage

/** Events produced by the signaling client, regardless of reconnects. */
sealed class SignalEvent {
    data class SocketOpen(val userIdHint: String) : SignalEvent()
    data class SocketClosed(val code: Int, val reason: String) : SignalEvent()
    data class SocketFailure(val message: String) : SignalEvent()

    data class Registered(val userId: String, val displayName: String) : SignalEvent()

    data class IncomingCall(val callId: String, val from: String, val fromName: String, val sdp: String, val mode: String = "call") : SignalEvent()
    data class RemoteAnswer(val callId: String, val sdp: String) : SignalEvent()
    data class RemoteOffer(val callId: String, val sdp: String) : SignalEvent()
    data class RemoteIce(val callId: String, val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : SignalEvent()
    data class RemoteHangup(val callId: String) : SignalEvent()
    data class RemoteDecline(val callId: String) : SignalEvent()
    data class PeerOffline(val peerId: String) : SignalEvent()

    data class VoiceMessageReceived(val message: VoiceMessage) : SignalEvent()
    data class ServerError(val message: String) : SignalEvent()
}
