package com.quickvoice.core.model

/**
 * A short voice message produced by Quick Voice.
 * [mediaBytes] holds an AAC/MP4 (3gpp) container produced by MediaRecorder.
 */
data class VoiceMessage(
    val id: String,
    val fromUserId: String,
    val fromName: String,
    val toUserId: String,
    val mediaBytes: ByteArray,
    val durationMs: Long,
    val mimeType: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean = other is VoiceMessage && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
