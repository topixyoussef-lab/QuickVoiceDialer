package com.quickvoice.core.model

/** The transport a call uses. */
enum class CallType {
    /** Regular cellular call placed through the SIM via the Android Telecom framework. */
    SIM,

    /** Voice-over-IP call over Wi-Fi / mobile data using WebRTC. */
    VOIP,
}
