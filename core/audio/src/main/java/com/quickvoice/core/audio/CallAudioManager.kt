package com.quickvoice.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort routing for in-app (VoIP) audio. Cellular SIM calls are routed through
 * the Telecom [android.telecom.Call] API instead (see core:telecom), because
 * [AudioManager.setSpeakerphoneOn] is unreliable since Android 10 and requires the
 * signature-level MODIFY_AUDIO_ROUTING permission to be effective on Android 12+.
 *
 * The primary path for VoIP speaker control is the WebRTC AudioDeviceModule; this class
 * is a fallback and also manages the audio mode.
 */
@Singleton
class CallAudioManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun enterCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (t: Throwable) {
            Log.w(TAG, "enterCommunicationMode failed", t)
        }
    }

    fun exitCommunicationMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            Log.w(TAG, "exitCommunicationMode failed", t)
        }
    }

    /** Fallback speaker toggle for app audio. Best effort on Android 12+. */
    fun setSpeakerForAppAudio(on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = audioManager.availableCommunicationDevices
                    .firstOrNull { it.isSink && it.type == targetType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                    return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.setSpeakerphoneOn(on)
        } catch (t: Throwable) {
            Log.w(TAG, "setSpeakerForAppAudio failed; falling back to setSpeakerphoneOn", t)
            try {
                @Suppress("DEPRECATION")
                audioManager.setSpeakerphoneOn(on)
            } catch (_: Throwable) {
                // Ignored: platform may not allow routing changes.
            }
        }
    }

    private companion object {
        const val TAG = "CallAudioManager"
    }
}
