package com.quickvoice.core.model

/** User-configurable Quick Voice behaviour. */
data class QuickVoiceSettings(
    val enabled: Boolean = false,
    /** Maximum length of a recorded message, 2s..5s. */
    val maxMessageDurationMs: Long = 3_000L,
    /** After this long without the far party answering, Quick Voice auto-arms. */
    val autoActivateAfterMs: Long = 15_000L,
    /** Auto-enable the loudspeaker when Quick Voice arms. */
    val autoEnableSpeaker: Boolean = true,
)
