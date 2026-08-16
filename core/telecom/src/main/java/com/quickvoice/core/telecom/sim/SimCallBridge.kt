package com.quickvoice.core.telecom.sim

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.Call
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes the live Telecom [Call] so Quick Voice can drive speaker routing and
 * call termination. Audio routing goes through the Android [AudioManager]
 * (reliable on modern Android); call termination uses the official [Call] API.
 */
@Singleton
class SimCallBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _activeTelecomCall = MutableStateFlow<Call?>(null)
    val activeTelecomCall: StateFlow<Call?> = _activeTelecomCall.asStateFlow()

    fun setCall(call: Call?) {
        _activeTelecomCall.value = call
    }

    fun setSpeakerOn() = setSpeaker(on = true)

    fun setEarpiece() = setSpeaker(on = false)

    private fun setSpeaker(on: Boolean) {
        try {
            val audioManager = context.getSystemService(AudioManager::class.java)
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
            Log.w(TAG, "setSpeaker($on) failed", t)
        }
    }

    fun disconnect() {
        _activeTelecomCall.value?.disconnect()
    }

    private companion object {
        const val TAG = "SimCallBridge"
    }
}
