package com.quickvoice.core.quickvoice.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records short Quick Voice clips (AAC in an MP4/3gpp container) with a hard
 * maximum duration. Nothing is stored permanently — clips live in the cache dir and
 * are removed right after they are delivered.
 */
@Singleton
class QuickVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class RecorderState { IDLE, RECORDING, STOPPED, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /**
     * Emits the finished recording file every time a recording completes — either because
     * the user lifted the finger or the max duration was reached. The consuming side owns
     * the file (reads + deletes it). null is emitted after [cancel].
     */
    private val _finished = MutableStateFlow<File?>(null)
    val finished: StateFlow<File?> = _finished.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtEpoch = 0L
    private var maxDurationMs = 3_000L

    fun isRecording(): Boolean = _state.value == RecorderState.RECORDING

    fun prepare(maxMessageDurationMs: Long) {
        maxDurationMs = maxMessageDurationMs
        outputFile = File(context.cacheDir, "quickvoice_${System.nanoTime()}.3gpp")
        _finished.value = null
        _state.value = RecorderState.IDLE
        _elapsedMs.value = 0L
    }

    /** Starts capturing. Returns false when the microphone cannot be opened. */
    fun start(): Boolean {
        val file = outputFile ?: return false
        return try {
            val recorder = createRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.setMaxDuration(maxDurationMs.toInt())
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    scope.launch { stop() }
                }
            }
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            startedAtEpoch = System.currentTimeMillis()
            _state.value = RecorderState.RECORDING
            scope.launch {
                while (_state.value == RecorderState.RECORDING) {
                    _elapsedMs.value = System.currentTimeMillis() - startedAtEpoch
                    delay(50)
                }
            }
            true
        } catch (t: Throwable) {
            _state.value = RecorderState.ERROR
            false
        }
    }

    /**
     * Stops recording and emits the recorded file through [finished]. Safe to call from
     * any thread. Idempotent: once the recording is stopped the file is handed over and
     * the internal reference cleared, so repeated calls return false.
     */
    fun stop(): Boolean {
        if (_state.value != RecorderState.RECORDING) return false
        val recorder = mediaRecorder ?: return false
        val file = outputFile ?: return false
        return try {
            recorder.stop()
            recorder.release()
            mediaRecorder = null
            outputFile = null
            _state.value = RecorderState.STOPPED
            _finished.value = file.takeIf { it.exists() && it.length() > 0L }
            true
        } catch (t: Throwable) {
            _state.value = RecorderState.ERROR
            file.delete()
            false
        }
    }

    fun cancel() {
        try {
            if (_state.value == RecorderState.RECORDING) {
                mediaRecorder?.stop()
            }
        } catch (_: Throwable) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
        _finished.value = null
        _state.value = RecorderState.IDLE
        _elapsedMs.value = 0L
    }

    private fun createRecorder(): MediaRecorder {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
