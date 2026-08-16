package com.quickvoice.core.model

/** A contact pulled from the device address book, optionally linked to a VoIP ID. */
data class Contact(
    val lookupKey: String,
    val name: String,
    val phoneNumber: String,
    val voipId: String? = null,
    val photoUri: String? = null,
)
