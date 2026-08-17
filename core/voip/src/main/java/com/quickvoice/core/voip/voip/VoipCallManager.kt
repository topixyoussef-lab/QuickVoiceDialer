package com.quickvoice.core.voip.voip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.quickvoice.core.audio.CallAudioManager
import com.quickvoice.core.audio.CallRecorder
import com.quickvoice.core.audio.CallRingTone
import com.quickvoice.core.call.CallCommandSink
import com.quickvoice.core.call.CallController
import com.quickvoice.core.data.repository.CallHistoryRepository
import com.quickvoice.core.data.repository.SettingsRepository
import com.quickvoice.core.model.AudioRoute
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.RecentCall
import com.quickvoice.core.model.VoiceMessage
import com.quickvoice.core.voip.model.IncomingCallInfo
import com.quickvoice.core.voip.model.SignalingState
import com.quickvoice.core.voip.signaling.FirebaseSignalingClient
import com.quickvoice.core.voip.signaling.SignalEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC engine for Wi-Fi / data VoIP calls.
 *
 * Audio quality: echo cancellation, noise suppression and automatic gain control are
 * enabled through the WebRTC audio processing pipeline and the hardware AEC/NS on the
 * AudioDeviceModule. Firebase Realtime Database provides the signaling layer and the
 * PeerConnection restarts ICE when the transport changes (Wi-Fi <-> mobile data).
 *
 * Quick Voice over VoIP is delivered through a "quickvoice" WebRTC DataChannel during a
 * live call, and through the Firebase signaling (voice message) when the peer is offline.
 */
@Singleton
class VoipCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callAudioManager: CallAudioManager,
    private val callRingTone: CallRingTone,
    private val callRecorder: CallRecorder,
    private val settings: SettingsRepository,
    private val callController: CallController,
    private val callHistoryRepository: CallHistoryRepository,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val audioDeviceModule: JavaAudioDeviceModule,
    private val signalingClient: FirebaseSignalingClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _signalingState = MutableStateFlow(SignalingState.DISCONNECTED)
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()
    private val _userId = MutableStateFlow("")
    val userId: StateFlow<String> = _userId.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    private val _incomingVoiceMessages = MutableSharedFlow<VoiceMessage>(extraBufferCapacity = 16)
    val incomingVoiceMessages: SharedFlow<VoiceMessage> = _incomingVoiceMessages.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var myUserId = ""
    private var myDisplayName = ""

    /** ICE servers resolved from Settings (STUN + optional TURN) before a call starts. */
    @Volatile
    private var resolvedIceServers: List<PeerConnection.IceServer> = DEFAULT_STUN

    // --- per-call WebRTC state ---
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localSender: RtpSender? = null
    private var quickVoiceChannel: DataChannel? = null
    private var currentCallId = ""
    private var peerId = ""
    private var peerDisplayName = ""
    private var isCaller = false
    private var wasActive = false
    private var sessionId = ""
    private var callStartedAtRealtime = 0L
    private var callStartedAtWall = 0L
    private var restartIceJob: Job? = null
    private var callMode = "call"
    private var intercomMode = false

    init {
        scope.launch { collectSignals() }
        observeNetworkForIceRestart()
    }

    // ------------------------------------------------------------------ server

    /** Connects to the Firebase signaling backend. */
    suspend fun startServerConnection() {
        myUserId = settings.voipUserId.first()
        myDisplayName = settings.voipDisplayName.first()
        resolveIceServers()
        signalingClient.start("")
        _signalingState.value = SignalingState.CONNECTING
    }

    /** Reads the STUN + TURN configuration and rebuilds the ICE server list. */
    private suspend fun resolveIceServers() {
        val turnUrl = settings.voipTurnUrl.first().trim()
        val defaultTurnUrl = "turn:openrelay.metered.ca:443?transport=tcp"

        val url = if (turnUrl.isNotBlank()) turnUrl else defaultTurnUrl
        val username = if (turnUrl.isNotBlank()) {
            settings.voipTurnUsername.first().trim()
        } else {
            "openrelayproject"
        }
        val password = if (turnUrl.isNotBlank()) {
            settings.voipTurnPassword.first()
        } else {
            "openrelayproject"
        }

        val turn = PeerConnection.IceServer(
            url,
            username.ifEmpty { null },
            password.ifEmpty { null },
        )
        val turns = PeerConnection.IceServer(
            "turns:openrelay.metered.ca:443?transport=tcp",
            username.ifEmpty { null },
            password.ifEmpty { null },
        )
        resolvedIceServers = DEFAULT_STUN + listOf(turn, turns)
    }

    fun stopServerConnection() {
        signalingClient.stop()
        _signalingState.value = SignalingState.DISCONNECTED
    }

    private suspend fun collectSignals() {
        signalingClient.events.collect { event ->
            when (event) {
                is SignalEvent.SocketOpen -> {
                    _signalingState.value = SignalingState.CONNECTING
                    sendRegister()
                }

                is SignalEvent.SocketClosed -> {
                    _signalingState.value = SignalingState.DISCONNECTED
                }

                is SignalEvent.SocketFailure -> {
                    _lastError.value = event.message
                }

                is SignalEvent.Registered -> {
                    _userId.value = event.userId
                    _signalingState.value = SignalingState.REGISTERED
                    if (event.userId != myUserId) {
                        myUserId = event.userId
                        settings.setVoipUserId(event.userId)
                    }
                }

                is SignalEvent.IncomingCall -> handleIncomingCall(event)
                is SignalEvent.RemoteAnswer -> onRemoteAnswer(event)
                is SignalEvent.RemoteOffer -> onRemoteOffer(event)
                is SignalEvent.RemoteIce -> onRemoteIce(event)
                is SignalEvent.RemoteHangup -> endCallInternal("remote hung up")
                is SignalEvent.RemoteDecline -> endCallInternal("declined")
                is SignalEvent.PeerOffline -> onPeerOffline(event.peerId)
                is SignalEvent.VoiceMessageReceived -> {
                    _incomingVoiceMessages.emit(event.message)
                }

                is SignalEvent.ServerError -> _lastError.value = event.message
            }
        }
    }

    private fun sendRegister() {
        signalingClient.register(myDisplayName)
    }

    // ---------------------------------------------------------------- outgoing

    fun placeCall(peerId: String, displayName: String) = placeCallInternal(peerId, displayName, mode = "call")

    /**
     * Places a one-way intercom call: the callee's phone rings and auto-answers,
     * so the caller can talk without the callee pressing anything. The callee's mic
     * stays muted; they can reply with the hold-to-talk button in the call screen.
     */
    fun placeIntercom(peerId: String, displayName: String) =
        placeCallInternal(peerId, displayName, mode = "intercom")

    private fun placeCallInternal(peerId: String, displayName: String, mode: String) {
        if (peerId.isBlank()) return
        if (_signalingState.value != SignalingState.REGISTERED) {
            _lastError.value = "VoIP server is not connected"
            return
        }
        this.peerId = peerId
        this.peerDisplayName = displayName.ifBlank { peerId }
        isCaller = true
        currentCallId = "call-${System.nanoTime()}"
        sessionId = "voip-$currentCallId"
        callStartedAtRealtime = SystemClock.elapsedRealtime()
        callStartedAtWall = System.currentTimeMillis()
        wasActive = false
        callMode = mode
        intercomMode = mode == "intercom"

        if (!hasRecordAudioPermission()) {
            _lastError.value = "Microphone permission is required for VoIP calls"
            return
        }

        callAudioManager.enterCommunicationMode()
        callRingTone.startRingback()
        VoipCallService.startCall(context)
        createPeerConnection()
        createAndSendOffer(isRenegotiation = false)
        publishSession(CallState.CONNECTING, isIntercom = intercomMode)
    }

    // ---------------------------------------------------------------- incoming

    fun acceptIncomingCall() {
        val incoming = _incomingCall.value ?: return
        _incomingCall.value = null
        callRingTone.stop()
        answerIncomingCallInternal(
            callId = incoming.callId,
            fromUserId = incoming.fromUserId,
            fromName = incoming.fromName,
            offerSdp = incoming.offerSdp,
            isIntercom = incoming.mode == "intercom",
        )
    }

    fun declineIncomingCall() {
        val incoming = _incomingCall.value ?: return
        _incomingCall.value = null
        callRingTone.stop()
        signalingClient.send(
            "decline",
            JSONObject().put("to", incoming.fromUserId).put("callId", incoming.callId)
        )
        callController.publish(null, null)
        VoipCallService.stop(context)
    }

    /**
     * A remote peer is calling us. If we are already on a call we politely decline;
     * an intercom is auto-answered silently (mic muted) so the caller can talk
     * hands-free; otherwise we surface the incoming call to the UI and post the
     * full-screen incoming-call notification (see VoipCallService.showIncoming).
     */
    private suspend fun handleIncomingCall(event: SignalEvent.IncomingCall) {
        if (_incomingCall.value != null || callController.activeSession.value != null) {
            signalingClient.send(
                "decline",
                JSONObject().put("to", event.from).put("callId", event.callId)
            )
            return
        }

        if (event.mode == "intercom") {
            answerIncomingCallInternal(
                callId = event.callId,
                fromUserId = event.from,
                fromName = event.fromName,
                offerSdp = event.sdp,
                isIntercom = true,
            )
            callRingTone.startIntercomAlert()
            return
        }

        callRingTone.startIncomingRingtone(settings.ringtoneUri.first())
        val incoming = IncomingCallInfo(
            callId = event.callId,
            fromUserId = event.from,
            fromName = event.fromName,
            offerSdp = event.sdp,
            mode = event.mode,
        )
        _incomingCall.value = incoming
        isCaller = false
        currentCallId = event.callId
        peerId = event.from
        peerDisplayName = event.fromName
        sessionId = "voip-${event.callId}"
        callMode = event.mode
        intercomMode = false
        publishSession(CallState.RINGING)
        try {
            VoipCallService.showIncoming(context, event.fromName, event.from)
        } catch (t: Throwable) {
            // On Android 12+ starting a foreground service from the background can be
            // rejected (ForegroundServiceStartNotAllowedException). The call stays
            // published to the UI, so the user still sees it if the app is foreground.
            Log.w(TAG, "Could not start incoming-call notification", t)
            _lastError.value = "Incoming call while app is in the background (needs push for production)"
        }
    }

    private fun answerIncomingCallInternal(
        callId: String,
        fromUserId: String,
        fromName: String,
        offerSdp: String,
        isIntercom: Boolean,
    ) {
        isCaller = false
        currentCallId = callId
        peerId = fromUserId
        peerDisplayName = fromName
        sessionId = "voip-$callId"
        callStartedAtRealtime = SystemClock.elapsedRealtime()
        callStartedAtWall = System.currentTimeMillis()
        wasActive = false
        callMode = if (isIntercom) "intercom" else "call"
        intercomMode = isIntercom

        callAudioManager.enterCommunicationMode()
        VoipCallService.startCall(context)
        createPeerConnection()
        setRemoteDescription(SessionDescription.Type.OFFER, offerSdp) {
            createAndSendAnswer()
        }
        publishSession(CallState.CONNECTING, isIntercom = isIntercom)
        if (isIntercom) setMicMutedPublic(true)
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
        val currentlyMuted = callController.activeSession.value?.isMicMuted ?: false
        setMicMutedPublic(!currentlyMuted)
    }

    /** Direct mic mute/unmute (used for intercom hold-to-talk). */
    fun setMicMutedPublic(muted: Boolean) {
        val sender = localSender ?: return
        val params = sender.getParameters()
        params.encodings.forEach { it.active = !muted }
        sender.setParameters(params)
        callController.update { it.copy(isMicMuted = muted) }
    }

    // ---------------------------------------------------------------- recording

    /** Starts recording the current VoIP call; returns the file path or null. */
    fun startRecording(): String? {
        if (intercomMode) {
            _lastError.value = "Intercom calls are not recorded"
            return null
        }
        if (callController.activeSession.value?.state != CallState.ACTIVE) {
            _lastError.value = "Recording can only start while the call is active"
            return null
        }
        val path = callRecorder.start(peerDisplayName)
        if (path == null) {
            _lastError.value = "Could not start call recording"
            return null
        }
        _recording.value = true
        return path
    }

    /** Stops recording and returns the final file path (or null if nothing was recorded). */
    fun stopRecording(): String? {
        if (!callRecorder.isRecording()) return null
        callRecorder.stop()
        _recording.value = false
        return callRecorder.lastRecordingPath()
    }

    fun setSpeaker(on: Boolean) {
        callAudioManager.setSpeakerForAppAudio(on)
        callController.update { it.copy(audioRoute = if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE) }
    }

    // --------------------------------------------------------------- quick voice

    /** True when the quickvoice DataChannel is open, so a clip can be sent live. */
    fun isLiveChannelReady(): Boolean =
        quickVoiceChannel?.state() == DataChannel.State.OPEN

    /** Send a recorded clip during a live call through the WebRTC DataChannel. */
    fun sendQuickVoiceLive(message: VoiceMessage) {
        val channel = quickVoiceChannel ?: return
        val payload = JSONObject()            .put("kind", "quickvoice")
            .put("id", message.id)
            .put("durationMs", message.durationMs)
            .put("mime", message.mimeType)
            .put("media", Base64.encodeToString(message.mediaBytes, Base64.NO_WRAP))
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(payload.toString().toByteArray(StandardCharsets.UTF_8)),
            false,
        )
        channel.send(buffer)
    }

    /** Deliver a voice message through the server (works even if the peer is offline). */
    fun sendVoiceMessage(message: VoiceMessage) {
        signalingClient.send(
            "voicemessage",
            JSONObject()
                .put("from", myUserId)
                .put("fromName", myDisplayName)
                .put("to", message.toUserId)
                .put("media", Base64.encodeToString(message.mediaBytes, Base64.NO_WRAP))
                .put("durationMs", message.durationMs)
                .put("mime", message.mimeType)
        )
    }

    fun queryPresence(peerId: String) {
        signalingClient.send("presence", JSONObject().put("to", peerId))
    }

    // ------------------------------------------------------------- webrtc internals

    private fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(resolvedIceServers)
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        val pc = peerConnectionFactory.createPeerConnection(config, observer)
        if (pc == null) {
            _lastError.value = "Could not create peer connection"
            endCallInternal("peer connection creation failed")
            return
        }
        peerConnection = pc

        val source = peerConnectionFactory.createAudioSource(createAudioConstraints())
        localAudioSource = source
        val track = peerConnectionFactory.createAudioTrack("quickvoice_audio", source)
        localSender = pc.addTrack(track, emptyList())

        if (isCaller) {
            val channel = pc.createDataChannel(QUICK_VOICE_CHANNEL, DataChannel.Init())
            registerDataChannel(channel)
            quickVoiceChannel = channel
        }
    }

    private fun createAudioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
    }

    private fun createSdpConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun createAndSendOffer(isRenegotiation: Boolean) {
        val pc = peerConnection ?: return
        val type = if (isRenegotiation) "offer" else "call"
        pc.createOffer(offerObserver(type), createSdpConstraints())
    }

    private fun offerObserver(type: String) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            val pc = peerConnection ?: return
            pc.setLocalDescription(setObserver(), desc)
            val payload = JSONObject()
                .put("to", peerId)
                .put("callId", currentCallId)
                .put("sdp", desc.description)
                .put("mode", callMode)
            signalingClient.send(type, payload)
        }

        override fun onCreateFailure(error: String) {
            Log.w(TAG, "createOffer failed: $error")
        }

        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private fun createAndSendAnswer() {
        val pc = peerConnection ?: return
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(setObserver(), desc)
                val payload = JSONObject()
                    .put("to", peerId)
                    .put("callId", currentCallId)
                    .put("sdp", desc.description)
                signalingClient.send("answer", payload)
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }, createSdpConstraints())
    }

    private fun setObserver() = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onReady: () -> Unit) {
        val pc = peerConnection ?: return
        val desc = SessionDescription(type, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
            override fun onSetSuccess() = onReady()
            override fun onSetFailure(error: String) = Unit
        }, desc)
    }

    private fun onRemoteAnswer(event: SignalEvent.RemoteAnswer) {
        if (event.callId != currentCallId) return
        setRemoteDescription(SessionDescription.Type.ANSWER, event.sdp) { /* media flowing */ }
    }

    private fun onRemoteOffer(event: SignalEvent.RemoteOffer) {
        if (event.callId != currentCallId) return
        setRemoteDescription(SessionDescription.Type.OFFER, event.sdp) { createAndSendAnswer() }
    }

    private fun onRemoteIce(event: SignalEvent.RemoteIce) {
        if (event.callId != currentCallId) return
        val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
        peerConnection?.addIceCandidate(candidate)
    }

    private fun onPeerOffline(peerId: String) {
        if (peerId != this.peerId) return
        _lastError.value = "User $peerId is offline or does not exist"
        endCallInternal("peer offline")
    }

    private fun registerDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteBuffer.allocateDirect(buffer.data.remaining())
                data.put(buffer.data)
                data.flip()
                val text = String(ByteArray(data.remaining()).also { data.get(it) }, StandardCharsets.UTF_8)
                handleQuickVoiceMessage(text)
            }
        })
    }

    private fun handleQuickVoiceMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("kind") != "quickvoice") return
            val media = Base64.decode(json.optString("media"), Base64.NO_WRAP)
            val message = VoiceMessage(
                id = json.optString("id", UUID.randomUUID().toString()),
                fromUserId = peerId,
                fromName = peerDisplayName,
                toUserId = myUserId,
                mediaBytes = media,
                durationMs = json.optLong("durationMs", 0L),
                mimeType = json.optString("mime", "audio/3gpp"),
            )
            scope.launch { _incomingVoiceMessages.emit(message) }
        } catch (t: Throwable) {
            Log.w(TAG, "Bad quick voice message", t)
        }
    }

    // ------------------------------------------------------------------ observer

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(signalingState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    if (!wasActive) {
                        wasActive = true
                        callRingTone.stop()
                        val muted = callController.activeSession.value?.isMicMuted ?: false
                        publishSession(CallState.ACTIVE, isIntercom = intercomMode, muted = muted)
                    }
                }

                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED,
                -> {
                    if (wasActive) scheduleIceRestart()
                }

                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
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

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(mediaStream: MediaStream) = Unit
        override fun onRemoveStream(mediaStream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            registerDataChannel(channel)
            quickVoiceChannel = channel
        }

        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver) = Unit
        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private fun scheduleIceRestart() {
        restartIceJob?.cancel()
        restartIceJob = scope.launch {
            delay(3_000)
            val pc = peerConnection ?: return@launch
            if (pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED) return@launch
            Log.i(TAG, "Restarting ICE after transport change")
            pc.createOffer(offerObserver("offer"), MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            })
        }
    }

    private fun observeNetworkForIceRestart() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val pc = peerConnection
                if (pc != null && wasActive) scheduleIceRestart()
            }
        })
    }

    // ------------------------------------------------------------------ session

    private fun publishSession(state: CallState, isIntercom: Boolean = false, muted: Boolean = false) {
        val session = CallSession(
            id = sessionId,
            type = CallType.VOIP,
            direction = if (isCaller) CallDirection.OUTGOING else CallDirection.INCOMING,
            number = peerId,
            displayName = peerDisplayName,
            state = state,
            audioRoute = AudioRoute.EARPIECE,
            isMicMuted = muted,
            startedAtEpochMillis = callStartedAtWall,
            voipPeerId = peerId,
            isIntercom = isIntercom,
        )
        callController.publish(session, VoipSink())
    }

    private inner class VoipSink : CallCommandSink {
        override fun endCall(sessionId: String) = hangup()
        override fun answer(sessionId: String) = acceptIncomingCall()
        override fun reject(sessionId: String) = declineIncomingCall()
        override fun setMicMuted(sessionId: String, muted: Boolean) = setMicMutedPublic(muted)
        override fun setAudioRoute(sessionId: String, route: AudioRoute) =
            setSpeaker(route == AudioRoute.SPEAKER)
    }

    private fun endCallInternal(reason: String) {
        Log.i(TAG, "Ending VoIP call ($reason)")
        val duration = if (callStartedAtRealtime > 0) {
            (SystemClock.elapsedRealtime() - callStartedAtRealtime).coerceAtLeast(0L)
        } else 0L
        val finalState = when {
            wasActive -> CallState.DISCONNECTED
            isCaller -> CallState.DISCONNECTED
            else -> CallState.MISSED
        }
        val direction = if (isCaller) CallDirection.OUTGOING else CallDirection.INCOMING

        scope.launch {
            if (peerId.isNotBlank()) {
                callHistoryRepository.add(
                    RecentCall(
                        number = peerId,
                        displayName = peerDisplayName.ifBlank { null },
                        type = CallType.VOIP,
                        direction = direction,
                        state = finalState,
                        durationMs = duration,
                        timestamp = callStartedAtWall,
                    )
                )
            }
        }

        teardownPeerConnection()
        callRingTone.stop()
        if (callRecorder.isRecording()) {
            callRecorder.stop()
            _recording.value = false
        }
        callAudioManager.exitCommunicationMode()
        VoipCallService.stop(context)

        currentCallId = ""
        peerId = ""
        wasActive = false
        sessionId = ""
        callMode = "call"
        intercomMode = false
        callController.publish(null, null)
    }

    private fun teardownPeerConnection() {
        restartIceJob?.cancel()
        restartIceJob = null
        try {
            quickVoiceChannel?.close()
        } catch (_: Throwable) {
        }
        quickVoiceChannel = null
        try {
            localSender = null
            localAudioSource?.dispose()
        } catch (_: Throwable) {
        }
        localAudioSource = null
        try {
            peerConnection?.close()
        } catch (_: Throwable) {
        }
        peerConnection = null
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VoipCallManager"
        const val QUICK_VOICE_CHANNEL = "quickvoice"
        val DEFAULT_STUN = listOf(
            PeerConnection.IceServer("stun:stun.l.google.com:19302"),
            PeerConnection.IceServer("stun:stun1.l.google.com:19302"),
        )
    }
}
