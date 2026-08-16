package com.quickvoice.core.quickvoice.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.quickvoice.core.model.VoiceMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a received voice message through the communication audio stream.
 * Used for the live "play aloud on speaker" fallback on SIM calls and for
 * incoming voice messages.
 */
@Singleton
class QuickVoicePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(message: VoiceMessage) {
        stop()
        try {
            val file = File(context.cacheDir, "incoming_${message.id}.3gpp")
            file.writeBytes(message.mediaBytes)
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
        } catch (t: Throwable) {
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
    }
}
