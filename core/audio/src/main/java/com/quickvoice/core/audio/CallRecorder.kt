package com.quickvoice.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a live VoIP call to an .m4a file.
 *
 * Captures the microphone through an [AudioRecord] on [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
 * while the app is in communication mode. On most devices this source captures the full
 * conversation mix (the caller's voice plus the remote audio played back), which is how
 * in-app call recording is done without root or a screen-capture permission. If a device
 * only delivers the local mic, the recording still contains the user's side.
 *
 * The captured PCM is encoded to AAC-LC with [MediaCodec] and written with [MediaMuxer].
 * Files land in `getExternalFilesDir(null)/recordings`.
 */
@Singleton
class CallRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastRecording: String? = null

    fun isRecording(): Boolean = running

    /** Path of the most recently finished (or in-progress) recording. */
    fun lastRecordingPath(): String? = lastRecording

    /** Starts recording; returns the output file path, or null if no capture source could start. */
    fun start(label: String): String? {
        if (running) return null
        val dir = File(context.getExternalFilesDir(null), "recordings")
        dir.mkdirs()
        val file = File(dir, "rec-${timestamp()}-${sanitize(label)}.m4a")
        val record = try {
            createAudioRecord()
        } catch (t: Throwable) {
            Log.w(TAG, "Mic capture unavailable", t)
            null
        }
        if (record == null) return null
        running = true
        lastRecording = file.absolutePath
        thread = Thread({ recordLoop(record, file) }, "CallRecorder").apply { start() }
        return file.absolutePath
    }

    /** Stops recording and finalises the file. Safe to call even if never started. */
    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_SAMPLES * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mic AudioRecord not initialised" }
        record.startRecording()
        return record
    }

    private fun recordLoop(record: AudioRecord, file: File) {
        val encoder = MuxEncoder(file)
        try {
            val frame = ShortArray(FRAME_SAMPLES)
            while (running) {
                val n = record.read(frame, 0, frame.size)
                if (n > 0) encoder.encode(frame, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Recording loop failed", t)
        } finally {
            try {
                runCatching { record.stop() }
                runCatching { record.release() }
            } finally {
                encoder.finish()
            }
        }
    }

    private fun sanitize(label: String): String =
        label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "call" }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val TAG = "CallRecorder"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 480 // 10 ms at 48 kHz
    }

    // ------------------------------------------------------------------- encode

    private class MuxEncoder(private val file: File) {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var trackIndex = -1
        private var muxerStarted = false
        private var ptsUs = 0L
        private val info = MediaCodec.BufferInfo()

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
        }

        fun encode(pcm: ShortArray, size: Int) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index)
                val pcmBytes = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
                pcmBytes.asShortBuffer().put(pcm, 0, size)
                input?.clear()
                input?.put(pcmBytes.array(), 0, size * 2)
                codec.queueInputBuffer(index, 0, size * 2, ptsUs, 0)
                ptsUs += size * 1_000_000L / SAMPLE_RATE
            }
            drainOutputs()
        }

        fun finish() {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.getInputBuffer(index)?.clear()
                codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainOutputs()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        private fun drainOutputs() {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
