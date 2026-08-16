package com.quickvoice.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.quickvoice.core.model.Contact

/**
 * A number the user saved with a name, shown as the "Saved" section in Contacts.
 * The number itself is the key because it is the VoIP peer id used to reach it.
 */
@Entity(tableName = "saved_numbers")
data class SavedNumberEntity(
    @PrimaryKey
    @ColumnInfo(name = "number")
    val number: String,
    @ColumnInfo(name = "name")
    val name: String,
)

fun SavedNumberEntity.toModel(): Contact = Contact(
    lookupKey = "saved:$number",
    name = name,
    phoneNumber = number,
    voipId = number,
)
