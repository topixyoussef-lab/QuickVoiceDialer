package com.quickvoice.core.model

/** A call log entry shown on the Recents tab. */
data class RecentCall(
    val id: Long = 0L,
    val number: String,
    val displayName: String? = null,
    val type: CallType,
    val direction: CallDirection,
    val state: CallState,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
)
