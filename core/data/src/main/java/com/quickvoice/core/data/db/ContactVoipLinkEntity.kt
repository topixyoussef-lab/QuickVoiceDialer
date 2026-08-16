package com.quickvoice.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the optional VoIP ID a user linked to a device contact.
 * Keyed by the contacts lookup key so it survives phone number changes.
 */
@Entity(tableName = "contact_voip_links")
data class ContactVoipLinkEntity(
    @PrimaryKey
    @ColumnInfo(name = "lookup_key")
    val lookupKey: String,
    @ColumnInfo(name = "voip_id")
    val voipId: String,
)
