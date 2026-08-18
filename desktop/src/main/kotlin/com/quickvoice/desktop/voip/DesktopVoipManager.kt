package com.quickvoice.desktop.voip

import com.quickvoice.core.call.CallCommandSink
import com.quickvoice.core.call.CallController
import com.quickvoice.core.model.AudioRoute
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.desktop.settings.DesktopSettings
import com.quickvoice.desktop.signaling.SignalEvent
import com.quickvoice.desktop.signaling.FirebaseRestSignalingClient
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

enum class SignalingState { DISCONNECTED, CONNECTING, REGISTERED }

data class IncomingCallInfo(
    val callId: String,
    val fromUserId: String,
    val fromName: String,
    val offerSdp: String,
)

/**
 * WebRTC engine for the desktop client. Uses dev.onvoid.webrtc (libwebrtc for the
 * JVM) with the same signaling protocol as the Android app, so desktop and phone
 * can call each other through the same server.
 */
class DesktopVoipManager(
    private val settings: DesktopSettings,
    private val signalingClient: FirebaseRestSignalingClient,
    private val callController: CallController,
) {

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _signalingState = MutableStateFlow(SignalingState.DISCONNECTED)
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()

    private val _userId = MutableStateFlow("")
    val userId: StateFlow<String> = _userId.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun showError(message: String) {
        _lastError.value = message
    }

    private val audioModule = AudioDeviceModule()
    private val factory = PeerConnectionFactory(audioModule)

    private var myUserId = ""
    private var myDisplayName = ""

    @Volatile
    private var resolvedIceServers: List<RTCIceServer> = defaultIceServers()

    private var peerConnection: RTCPeerConnection? = null
    private var localSender: AudioTrack? = null
    private var quickVoiceChannel: RTCDataChannel? = null
    private var currentCallId = ""
    private var peerId = ""
    private var peerDisplayName = ""
    private var isCaller = false
    private var wasActive = false
    private var callStartedAtNanos = 0L
    private var callStartedAtWall = 0L
    private var tearingDown = false

    init {
        initAudioModule()
        scope.launch { collectSignals() }
    }

    private fun initAudioModule() {
        runCatching {
            MediaDevices.getDefaultAudioCaptureDevice()?.let { audioModule.setRecordingDevice(it) }
            MediaDevices.getDefaultAudioRenderDevice()?.let { audioModule.setPlayoutDevice(it) }
            audioModule.initRecording()
            audioModule.initPlayout()
        }.onFailure { t ->
            println("[DesktopVoipManager] Audio init failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- server

    suspend fun startServerConnection() {
        myUserId = settings.userId.first()
        myDisplayName = settings.displayName.first()
        println("[DesktopVoipManager] connecting as '$myUserId'")
        resolveIceServers()
        signalingClient.start()
        signalingClient.register(myDisplayName)
        _signalingState.value = SignalingState.CONNECTING
    }

    fun stopServerConnection() {
        signalingClient.stop()
        _signalingState.value = SignalingState.DISCONNECTED
    }

    private suspend fun resolveIceServers() {
        val turnUrl = settings.turnUrl.first().trim()
        if (turnUrl.isBlank()) {
            resolvedIceServers = defaultIceServers()
            return
        }
        val username = settings.turnUsername.first().trim()
        val password = settings.turnPassword.first()
        val turn = RTCIceServer()
        turn.urls.add(turnUrl)
        turn.username = username.ifEmpty { null }
        turn.password = password.ifEmpty { null }
        resolvedIceServers = defaultIceServers() + turn
    }

    private suspend fun collectSignals() {
        signalingClient.events.collect { event ->
            when (event) {
                is SignalEvent.SocketOpen -> {
                    _signalingState.value = SignalingState.CONNECTING
                }

                is SignalEvent.SocketClosed -> {
                    println("[DesktopVoipManager] socket closed")
                    _signalingState.value = SignalingState.DISCONNECTED
                }

                is SignalEvent.SocketFailure -> {
                    println("[DesktopVoipManager] socket failure: ${event.message}")
                    _lastError.value = event.message
                }

                is SignalEvent.Registered -> {
                    println("[DesktopVoipManager] REGISTERED as ${event.userId}")
                    _userId.value = event.userId
                    _signalingState.value = SignalingState.REGISTERED
                    if (event.userId != myUserId) {
                        myUserId = event.userId
                        settings.setUserId(event.userId)
                    }
                }

                is SignalEvent.IncomingCall -> handleIncomingCall(event)
                is SignalEvent.RemoteAnswer -> onRemoteAnswer(event)
                is SignalEvent.RemoteOffer -> onRemoteOffer(event)
                is SignalEvent.RemoteIce -> onRemoteIce(event)
                is SignalEvent.RemoteHangup -> endCallInternal("remote hung up")
                is SignalEvent.RemoteDecline -> endCallInternal("declined")
                is SignalEvent.PeerOffline -> onPeerOffline(event.peerId)
                is SignalEvent.VoiceMessageReceived -> Unit
                is SignalEvent.ServerError -> _lastError.value = event.message
            }
        }
    }

    // ---------------------------------------------------------------- outgoing

    fun placeCall(peerId: String, displayName: String) {
        if (peerId.isBlank()) return
        if (_signalingState.value != SignalingState.REGISTERED) {
            _lastError.value = "VoIP server is not connected"
            return
        }
        this.peerId = peerId
        this.peerDisplayName = displayName.ifBlank { peerId }
        isCaller = true
        currentCallId = "call-${System.nanoTime()}"
        callStartedAtNanos = System.nanoTime()
        callStartedAtWall = System.currentTimeMillis()
        wasActive = false
        createPeerConnection()
        createAndSendOffer()
        publishSession(CallState.CONNECTING)
    }

    // ---------------------------------------------------------------- incoming

    fun acceptIncomingCall() {
        val incoming = _incomingCall.value ?: return
        _incomingCall.value = null
        isCaller = false
        currentCallId = incoming.callId
        peerId = incoming.fromUserId
        peerDisplayName = incoming.fromName
        callStartedAtNanos = System.nanoTime()
        callStartedAtWall = System.currentTimeMillis()
        wasActive = false
        createPeerConnection()
        setRemoteDescription(RTCSdpType.OFFER, incoming.offerSdp) { createAndSendAnswer() }
        publishSession(CallState.CONNECTING)
    }

    fun declineIncomingCall() {
        val incoming = _incomingCall.value ?: return
        _incomingCall.value = null
        signalingClient.send(
            "decline",
            JSONObject().put("to", incoming.fromUserId).put("callId", incoming.callId)
        )
        callController.publish(null, null)
    }

    private fun handleIncomingCall(event: SignalEvent.IncomingCall) {
        if (_incomingCall.value != null || callController.activeSession.value != null) {
            signalingClient.send(
                "decline",
                JSONObject().put("to", event.from).put("callId", event.callId)
            )
            return
        }
        _incomingCall.value = IncomingCallInfo(
            callId = event.callId,
            fromUserId = event.from,
            fromName = event.fromName,
            offerSdp = event.sdp,
        )
        isCaller = false
        currentCallId = event.callId
        peerId = event.from
        peerDisplayName = event.fromName
        publishSession(CallState.RINGING)
    }

    // ------------------------------------------------------------------ control

    fun hangup() {
        if (currentCallId.isNotEmpty() && peerId.isNotEmpty()) {
            signalingClient.send(
                "hangup",
                JSONObject().put("to", peerId).put("callId", currentCallId)
            )
        }
        endCallInternal("ended")
    }

    fun toggleMute() {
        val session = callController.activeSession.value ?: return
        val muted = !session.isMicMuted
        audioModule.setMicrophoneMute(muted)
        callController.update { it.copy(isMicMuted = muted) }
    }

    // ------------------------------------------------------------- webrtc internals

    private fun createPeerConnection() {
        val config = RTCConfiguration()
        config.iceServers.addAll(resolvedIceServers)
        val pc = factory.createPeerConnection(config, observer)
        if (pc == null) {
            _lastError.value = "Could not create peer connection"
            endCallInternal("peer connection creation failed")
            return
        }
        peerConnection = pc

        val source = factory.createAudioSource(audioOptions())
        val track = factory.createAudioTrack("quickvoice_audio", source)
        localSender = track
        pc.addTrack(track, listOf("quickvoice"))

        if (isCaller) {
            val channel = pc.createDataChannel(QUICK_VOICE_CHANNEL, RTCDataChannelInit())
            registerDataChannel(channel)
            quickVoiceChannel = channel
        }
    }

    private fun audioOptions(): AudioOptions = AudioOptions().apply {
        echoCancellation = true
        noiseSuppression = true
        autoGainControl = true
    }

    private fun createAndSendOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(description, setObserver())
                val payload = JSONObject()
                    .put("to", peerId)
                    .put("callId", currentCallId)
                    .put("sdp", description.sdp)
                signalingClient.send("call", payload)
            }

            override fun onFailure(error: String) {
                println("[DesktopVoipManager] createOffer failed: $error")
            }
        })
    }

    private fun createAndSendAnswer() {
        val pc = peerConnection ?: return
        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(description, setObserver())
                val payload = JSONObject()
                    .put("to", peerId)
                    .put("callId", currentCallId)
                    .put("sdp", description.sdp)
                signalingClient.send("answer", payload)
            }

            override fun onFailure(error: String) {
                println("[DesktopVoipManager] createAnswer failed: $error")
            }
        })
    }

    private fun setObserver() = object : SetSessionDescriptionObserver {
        override fun onSuccess() = Unit
        override fun onFailure(error: String) {
            println("[DesktopVoipManager] setLocalDescription failed: $error")
        }
    }

    private fun setRemoteDescription(type: RTCSdpType, sdp: String, onReady: () -> Unit) {
        val pc = peerConnection ?: return
        val desc = RTCSessionDescription(type, sdp)
        pc.setRemoteDescription(desc, object : SetSessionDescriptionObserver {
            override fun onSuccess() = onReady()
            override fun onFailure(error: String) {
                println("[DesktopVoipManager] setRemoteDescription failed: $error")
            }
        })
    }

    private fun onRemoteAnswer(event: SignalEvent.RemoteAnswer) {
        if (event.callId != currentCallId) return
        setRemoteDescription(RTCSdpType.ANSWER, event.sdp) { /* media flowing */ }
    }

    private fun onRemoteOffer(event: SignalEvent.RemoteOffer) {
        if (event.callId != currentCallId) return
        setRemoteDescription(RTCSdpType.OFFER, event.sdp) { createAndSendAnswer() }
    }

    private fun onRemoteIce(event: SignalEvent.RemoteIce) {
        if (event.callId != currentCallId) return
        val candidate = RTCIceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
        peerConnection?.addIceCandidate(candidate)
    }

    private fun onPeerOffline(peerId: String) {
        if (peerId != this.peerId) return
        _lastError.value = "User $peerId is offline or does not exist"
        endCallInternal("peer offline")
    }

    private fun registerDataChannel(channel: RTCDataChannel) {
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                // Quick Voice clips are ignored on desktop for now.
            }
        })
    }

    // ------------------------------------------------------------------ observer

    private val observer = object : PeerConnectionObserver {
        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            when (state) {
                RTCIceConnectionState.CONNECTED -> {
                    if (!wasActive) {
                        wasActive = true
                        publishSession(CallState.ACTIVE)
                    }
                }

                RTCIceConnectionState.DISCONNECTED,
                RTCIceConnectionState.FAILED,
                RTCIceConnectionState.CLOSED,
                -> endCallInternal("connection lost")

                else -> Unit
            }
        }

        override fun onIceCandidate(candidate: RTCIceCandidate) {
            signalingClient.send(
                "ice",
                JSONObject()
                    .put("to", peerId)
                    .put("callId", currentCallId)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
            )
        }

        override fun onDataChannel(channel: RTCDataChannel) {
            registerDataChannel(channel)
            quickVoiceChannel = channel
        }
    }

    // ------------------------------------------------------------------ session

    private fun publishSession(state: CallState) {
        val session = CallSession(
            id = "voip-$currentCallId",
            type = CallType.VOIP,
            direction = if (isCaller) CallDirection.OUTGOING else CallDirection.INCOMING,
            number = peerId,
            displayName = peerDisplayName,
            state = state,
            audioRoute = AudioRoute.SPEAKER,
            isMicMuted = false,
            startedAtEpochMillis = callStartedAtWall,
            voipPeerId = peerId,
        )
        callController.publish(session, sink)
    }

    private val sink = object : CallCommandSink {
        override fun endCall(sessionId: String) = hangup()
        override fun answer(sessionId: String) = acceptIncomingCall()
        override fun reject(sessionId: String) = declineIncomingCall()
        override fun setMicMuted(sessionId: String, muted: Boolean) {
            audioModule.setMicrophoneMute(muted)
        }

        override fun setAudioRoute(sessionId: String, route: AudioRoute) = Unit
    }

    private fun endCallInternal(reason: String) {
        if (tearingDown) return
        if (currentCallId.isEmpty() && peerId.isEmpty() && peerConnection == null) return
        tearingDown = true
        try {
            println("[DesktopVoipManager] Ending VoIP call ($reason)")
            val duration = if (callStartedAtNanos > 0) {
                ((System.nanoTime() - callStartedAtNanos) / 1_000_000).coerceAtLeast(0L)
            } else 0L
            val finalState = when {
                wasActive -> CallState.DISCONNECTED
                isCaller -> CallState.DISCONNECTED
                else -> CallState.MISSED
            }
            println("[DesktopVoipManager] Call duration: ${duration}ms (${finalState})")

            teardownPeerConnection()
            audioModule.setMicrophoneMute(false)

            currentCallId = ""
            peerId = ""
            wasActive = false
            callController.publish(null, null)
        } finally {
            tearingDown = false
        }
    }

    private fun teardownPeerConnection() {
        try { quickVoiceChannel?.close() } catch (_: Throwable) {}
        quickVoiceChannel = null
        try { localSender?.dispose() } catch (_: Throwable) {}
        localSender = null
        try { peerConnection?.close() } catch (_: Throwable) {}
        peerConnection = null
    }

    private companion object {
        const val QUICK_VOICE_CHANNEL = "quickvoice"

        fun defaultIceServers(): List<RTCIceServer> = listOf(
            RTCIceServer().apply { urls.add("stun:stun.l.google.com:19302") },
            RTCIceServer().apply { urls.add("stun:stun1.l.google.com:19302") },
        )
    }
}
