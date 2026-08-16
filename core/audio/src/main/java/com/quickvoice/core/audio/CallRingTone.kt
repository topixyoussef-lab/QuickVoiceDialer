package com.quickvoice.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the tones around a VoIP call:
 *  - [startRingback] loops the ringback tone for the CALLER while the far end rings.
 *  - [startIncomingRingtone] loops the device ringtone for the CALLEE.
 *  - [startIntercomAlert] plays a short beep when an intercom is auto-answered.
 *
 * Everything is stopped and released by [stop].
 */
@Singleton
class CallRingTone @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringbackJob: Job? = null
    private var ringbackTone: ToneGenerator? = null
    private var incomingPlayer: MediaPlayer? = null
    private var beepTone: ToneGenerator? = null

    /** Ringback tone (caller side) while the peer's phone rings. */
    fun startRingback() {
        stop()
        val tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME)
        } catch (t: Throwable) {
            Log.w(TAG, "No ringback tone available", t)
            return
        }
        ringbackTone = tone
        ringbackJob = scope.launch {
            while (isActive) {
                tone.startTone(ToneGenerator.TONE_CDMA_NETWORK_USA_RINGBACK, RINGBACK_TONE_MS)
                delay((RINGBACK_TONE_MS + RINGBACK_GAP_MS).toLong())
            }
        }
    }

    /**
     * Device ringtone (callee side) while a normal call is ringing.
     *
     * @param uriString the user-selected ringtone. An empty string uses the bundled
     *   QuickVoice ringtone, "silent" plays nothing, anything else is treated as a
     *   ringtone URI (system picker result).
     */
    fun startIncomingRingtone(uriString: String = "") {
        stop()
        val uri = resolveRingtoneUri(uriString) ?: return
        try {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            incomingPlayer = player
        } catch (t: Throwable) {
            Log.w(TAG, "Incoming ringtone failed to play", t)
        }
    }

    /**
     * Plays the selected ringtone once (non-looping) so the user can hear it from the
     * settings screen. [onPreviewCompletion] is invoked when playback finishes or stops.
     */
    var onPreviewCompletion: (() -> Unit)? = null

    fun previewRingtone(uriString: String = "") {
        stop()
        val uri = resolveRingtoneUri(uriString) ?: return
        try {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.isLooping = false
            player.setOnCompletionListener { stopPreview() }
            player.setOnErrorListener { _, _, _ -> stopPreview(); true }
            player.prepare()
            player.start()
            incomingPlayer = player
        } catch (t: Throwable) {
            Log.w(TAG, "Ringtone preview failed to play", t)
            stopPreview()
        }
    }

    private fun stopPreview() {
        if (incomingPlayer != null) {
            stop()
            onPreviewCompletion?.invoke()
        }
    }

    /**
     * Resolves the stored ringtone setting into an actual [Uri]: empty string means the
     * bundled QuickVoice ringtone, "silent" means no ringtone (null), anything else is
     * treated as the ringtone URI picked from the system picker.
     */
    private fun resolveRingtoneUri(uriString: String): Uri? {
        if (uriString == SILENT_RINGTONE) return null
        if (uriString.isBlank()) return bundledRingtoneUri() ?: systemDefaultRingtoneUri()
        return try {
            Uri.parse(uriString)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid ringtone URI", t)
            null
        }
    }

    /** The bundled QuickVoice ringtone packaged with the app. */
    private fun bundledRingtoneUri(): Uri? = runCatching {
        Uri.parse("android.resource://${context.packageName}/${R.raw.quickvoice_ringtone}")
    }.getOrElse {
        Log.w(TAG, "Bundled ringtone unavailable", it)
        null
    }

    private fun systemDefaultRingtoneUri(): Uri? = runCatching {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }.getOrElse {
        Log.w(TAG, "No ringtone available", it)
        null
    }

    /** Short alert so the callee knows an intercom was auto-answered. */
    fun startIntercomAlert() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, RINGBACK_VOLUME)
        } catch (t: Throwable) {
            return
        }
        beepTone?.release()
        beepTone = tone
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, INTERCOM_ALERT_MS)
    }

    fun stop() {
        ringbackJob?.cancel()
        ringbackJob = null
        try {
            ringbackTone?.release()
        } catch (_: Throwable) {
        }
        ringbackTone = null
        try {
            incomingPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            incomingPlayer?.release()
        } catch (_: Throwable) {
        }
        incomingPlayer = null
        try {
            beepTone?.release()
        } catch (_: Throwable) {
        }
        beepTone = null
    }

    private companion object {
        const val TAG = "CallRingTone"
        const val RINGBACK_VOLUME = 60
        const val RINGBACK_TONE_MS = 1200
        const val RINGBACK_GAP_MS = 1000
        const val INTERCOM_ALERT_MS = 500
        const val SILENT_RINGTONE = "silent"
    }
}
