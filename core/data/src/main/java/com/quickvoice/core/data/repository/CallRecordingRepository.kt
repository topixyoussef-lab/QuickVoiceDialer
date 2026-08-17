package com.quickvoice.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A recorded VoIP call stored on device. */
data class CallRecording(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val timestamp: Long,
) {
    val uri: Uri get() = Uri.fromFile(File(path))
}

/**
 * Lists / deletes call recordings saved by the [com.quickvoice.core.audio.CallRecorder]
 * in `getExternalFilesDir(null)/recordings`.
 */
@Singleton
class CallRecordingRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val dir: File get() = File(appContext.getExternalFilesDir(null), "recordings")

    fun list(): List<CallRecording> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                CallRecording(
                    path = it.absolutePath,
                    fileName = it.name,
                    sizeBytes = it.length(),
                    timestamp = it.lastModified(),
                )
            }
            ?: emptyList()

    fun delete(recording: CallRecording): Boolean = runCatching { File(recording.path).delete() }.getOrDefault(false)
}
