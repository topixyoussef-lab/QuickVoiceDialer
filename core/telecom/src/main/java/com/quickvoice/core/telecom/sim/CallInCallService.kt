package com.quickvoice.core.telecom.sim

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.quickvoice.core.call.CallCommandSink
import com.quickvoice.core.call.CallController
import com.quickvoice.core.data.repository.CallHistoryRepository
import com.quickvoice.core.data.repository.ContactsRepository
import com.quickvoice.core.model.AudioRoute
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.RecentCall
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * In-call service bound by the Telecom framework while a call is live — but only
 * when this app is the default dialer (role ROLE_DIALER).
 *
 * It translates each Telecom [Call] into our unified [CallSession] and publishes it
 * to the [CallController], plus implements [CallCommandSink] so the UI can drive the
 * call through the official Telecom API (answer, reject, disconnect, mute, audio route).
 */
@AndroidEntryPoint
class CallInCallService : InCallService() {

    @Inject lateinit var callController: CallController
    @Inject lateinit var simCallBridge: SimCallBridge
    @Inject lateinit var callHistoryRepository: CallHistoryRepository
    @Inject lateinit var contactsRepository: ContactsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val sessionIds = mutableMapOf<Call, String>()
    private val activeSinceRealtime = mutableMapOf<Call, Long>()
    private val startedAtWallTime = mutableMapOf<Call, Long>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (!callbacks.containsKey(call)) {
            sessionIds[call] = "sim-${System.nanoTime()}"
            val callback = CallbackImpl(call)
            callbacks[call] = callback
            call.registerCallback(callback)
        }
        push(call)
    }

    private fun push(call: Call) {
        val id = sessionIds.getOrPut(call) { "sim-${System.nanoTime()}" }
        val details = call.details
        val number = details.handle?.schemeSpecificPart ?: "unknown"
        val direction = if (details.callDirection == Call.Details.DIRECTION_OUTGOING) {
            CallDirection.OUTGOING
        } else {
            CallDirection.INCOMING
        }
        val name = details.callerDisplayName ?: number

        val session = CallSession(
            id = id,
            type = CallType.SIM,
            direction = direction,
            number = number,
            displayName = name,
            state = mapState(call.state),
            audioRoute = AudioRoute.UNKNOWN,
            isMicMuted = false,
            startedAtEpochMillis = startedAtWallTime[call] ?: System.currentTimeMillis(),
        )

        simCallBridge.setCall(call)
        callController.publish(session, SimSink(call))

        // Resolve the contact name asynchronously so the main thread is never blocked.
        if (name == number) {
            val sessionId = id
            scope.launch {
                val resolved = contactsRepository.lookupName(number)
                if (resolved != null) {
                    callController.update { s -> if (s.id == sessionId) s.copy(displayName = resolved) else s }
                }
            }
        }
    }

    private inner class CallbackImpl(private val call: Call) : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) {
                activeSinceRealtime.getOrPut(call) { SystemClock.elapsedRealtime() }
                startedAtWallTime.getOrPut(call) { System.currentTimeMillis() }
            }
            push(call)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) = push(call)

        override fun onCallDestroyed(call: Call) {
            recordRecentCall(call)
            callbacks.remove(call)?.let { call.unregisterCallback(it) }
            sessionIds.remove(call)
            activeSinceRealtime.remove(call)
            startedAtWallTime.remove(call)
            simCallBridge.setCall(null)
            callController.publish(null, null)
        }
    }

    private inner class SimSink(private val call: Call) : CallCommandSink {
        override fun endCall(sessionId: String) {
            call.disconnect()
        }

        override fun answer(sessionId: String) {
            if (call.state == Call.STATE_RINGING) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }

        override fun reject(sessionId: String) {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            }
        }

        override fun setMicMuted(sessionId: String, muted: Boolean) {
            routeAudioViaManager { it.setMicrophoneMute(muted) }
        }

        override fun setAudioRoute(sessionId: String, route: AudioRoute) {
            when (route) {
                AudioRoute.SPEAKER -> setSpeaker(on = true)
                AudioRoute.EARPIECE -> setSpeaker(on = false)
                else -> Unit
            }
        }

        private fun setSpeaker(on: Boolean) {
            try {
                val audioManager = getSystemService(AudioManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val device = audioManager.availableCommunicationDevices
                        .firstOrNull { it.isSink && it.type == type }
                    if (device != null) {
                        audioManager.setCommunicationDevice(device)
                        return
                    }
                }
                @Suppress("DEPRECATION")
                audioManager.setSpeakerphoneOn(on)
            } catch (t: Throwable) {
                // Ignored: platform may not allow routing changes.
            }
        }

        private fun routeAudioViaManager(block: (AudioManager) -> Unit) {
            try {
                block(getSystemService(AudioManager::class.java))
            } catch (t: Throwable) {
                // Ignored: platform may not allow mute changes.
            }
        }
    }

    private fun recordRecentCall(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        val direction = if (call.details?.callDirection == Call.Details.DIRECTION_OUTGOING) {
            CallDirection.OUTGOING
        } else {
            CallDirection.INCOMING
        }
        val duration = activeSinceRealtime[call]
            ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
            ?: 0L
        val finalState = if (duration > 0L) {
            CallState.DISCONNECTED
        } else if (direction == CallDirection.INCOMING) {
            CallState.MISSED
        } else {
            CallState.DISCONNECTED
        }
        val timestamp = startedAtWallTime[call] ?: System.currentTimeMillis()

        scope.launch {
            callHistoryRepository.add(
                RecentCall(
                    number = number,
                    displayName = call.details?.callerDisplayName,
                    type = CallType.SIM,
                    direction = direction,
                    state = finalState,
                    durationMs = duration,
                    timestamp = timestamp,
                )
            )
        }
    }

    private fun mapState(state: Int): CallState = when (state) {
        Call.STATE_NEW -> CallState.CONNECTING
        Call.STATE_DIALING -> CallState.CONNECTING
        Call.STATE_RINGING -> CallState.RINGING
        Call.STATE_ACTIVE -> CallState.ACTIVE
        Call.STATE_HOLDING -> CallState.HOLD
        Call.STATE_DISCONNECTING -> CallState.DISCONNECTING
        Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
        else -> CallState.IDLE
    }
}
