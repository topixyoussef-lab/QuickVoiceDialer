package com.quickvoice.core.voip.signaling

import android.util.Base64
import android.util.Log
import com.quickvoice.core.model.VoiceMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for our self-hosted signaling server.
 * Reconnects automatically with exponential backoff while started, and keeps the
 * single [events] stream alive across reconnects.
 */
class SignalingClient(
    private val okHttpClient: OkHttpClient,
) {
    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var url: String = ""
    @Volatile private var reconnectAttempt = 0

    fun start(serverUrl: String) {
        if (started && url == serverUrl) return
        url = serverUrl
        started = true
        connectNow()
    }

    fun stop() {
        started = false
        reconnectAttempt = 0
        webSocket?.close(1000, "client stopped")
        webSocket = null
    }

    private fun connectNow() {
        if (!started || url.isBlank()) return
        runCatching {
            val request = Request.Builder().url(url).build()
            _events.tryEmit(SignalEvent.SocketOpen(""))
            webSocket = okHttpClient.newWebSocket(request, listener)
        }.onFailure {
            _events.tryEmit(SignalEvent.SocketFailure(it.message ?: "Invalid server URL"))
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        val delay = minOf(1000L * (1L shl reconnectAttempt), 10_000L)
        reconnectAttempt++
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule({
            if (started) connectNow()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun send(type: String, payload: JSONObject) {
        val message = JSONObject().put("type", type).apply {
            val it = payload.keys()
            while (it.hasNext()) {
                val key = it.next()
                put(key, payload.get(key))
            }
        }.toString()
        webSocket?.send(message)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            _events.tryEmit(SignalEvent.SocketOpen(""))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (val type = json.optString("type")) {
                    "registered" -> _events.tryEmit(
                        SignalEvent.Registered(
                            json.optString("userId"),
                            json.optString("displayName"),
                        )
                    )

                    "incoming" -> _events.tryEmit(
                        SignalEvent.IncomingCall(
                            callId = json.optString("callId"),
                            from = json.optString("from"),
                            fromName = json.optString("fromName"),
                            sdp = json.optString("sdp"),
                            mode = json.optString("mode", "call"),
                        )
                    )

                    "answer" -> _events.tryEmit(
                        SignalEvent.RemoteAnswer(json.optString("callId"), json.optString("sdp"))
                    )

                    "offer" -> _events.tryEmit(
                        SignalEvent.RemoteOffer(json.optString("callId"), json.optString("sdp"))
                    )

                    "ice" -> _events.tryEmit(
                        SignalEvent.RemoteIce(
                            callId = json.optString("callId"),
                            sdpMid = if (json.has("sdpMid")) json.optString("sdpMid") else null,
                            sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                            candidate = json.optString("candidate"),
                        )
                    )

                    "hangup" -> _events.tryEmit(SignalEvent.RemoteHangup(json.optString("callId")))
                    "decline" -> _events.tryEmit(SignalEvent.RemoteDecline(json.optString("callId")))
                    "offline" -> _events.tryEmit(SignalEvent.PeerOffline(json.optString("peerId")))

                    "voicemessage" -> _events.tryEmit(
                        SignalEvent.VoiceMessageReceived(
                            VoiceMessage(
                                id = json.optString("id", UUID.randomUUID().toString()),
                                fromUserId = json.optString("from"),
                                fromName = json.optString("fromName"),
                                toUserId = json.optString("to"),
                                mediaBytes = Base64.decode(json.optString("media"), Base64.NO_WRAP),
                                durationMs = json.optLong("durationMs", 0L),
                                mimeType = json.optString("mime", "audio/3gpp"),
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    )

                    else -> _events.tryEmit(SignalEvent.ServerError("Unknown message type: $type"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to parse signal", t)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _events.tryEmit(SignalEvent.SocketClosed(code, reason))
            if (started) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _events.tryEmit(SignalEvent.SocketFailure(t.message ?: "network failure"))
            if (started) scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "SignalingClient"
    }
}
