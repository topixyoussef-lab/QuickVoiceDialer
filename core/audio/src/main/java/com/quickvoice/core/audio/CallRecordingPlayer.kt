package com.quickvoice.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays back a recorded call file, notifying [onCompletion] when playback ends.
 */
@Singleton
class CallRecordingPlayer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private var mediaPlayer: MediaPlayer? = null
    var onCompletion: (() -> Unit)? = null

    fun play(path: String) {
        stop()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener { onCompletion?.invoke() }
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
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }
}
