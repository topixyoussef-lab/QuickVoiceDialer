package com.quickvoice.core.data.repository

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.quickvoice.core.data.db.ContactVoipLinkDao
import com.quickvoice.core.data.db.ContactVoipLinkEntity
import com.quickvoice.core.model.Contact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactVoipLinkDao: ContactVoipLinkDao,
) {
    private val contentResolver: ContentResolver = context.contentResolver

    /** True if READ_CONTACTS has been granted by the user. */
    fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    val voipLinks: Flow<List<Pair<String, String>>> =
        contactVoipLinkDao.observeAll().map { links -> links.map { it.lookupKey to it.voipId } }

    suspend fun setVoipId(lookupKey: String, voipId: String) {
        if (voipId.isBlank()) {
            contactVoipLinkDao.delete(lookupKey)
        } else {
            contactVoipLinkDao.upsert(ContactVoipLinkEntity(lookupKey, voipId.trim()))
        }
    }

    suspend fun voipIdFor(lookupKey: String): String? = contactVoipLinkDao.voipIdFor(lookupKey)

    /**
     * Returns the VoIP ID linked to the contact that owns [number], if any.
     * Used by Quick Voice to route a voice message to a recipient that uses our app.
     */
    suspend fun voipIdForNumber(number: String): String? {
        if (number.isBlank() || !hasReadContactsPermission()) return null
        return withContext(Dispatchers.IO) {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(Uri.encode(number)).build()
            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY),
                null,
                null,
                null,
            ) ?: return@withContext null
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val lookupKey = c.getString(0)
                    if (lookupKey != null) return@withContext contactVoipLinkDao.voipIdFor(lookupKey)
                }
            }
            null
        }
    }

    /**
     * Queries the device address book. Numbers are merged against our stored VoIP links.
     * Runs off the main thread. Returns empty list without permission.
     */
    suspend fun searchContacts(query: String = "", voipLinks: Map<String, String> = emptyMap()): List<Contact> =
        withContext(Dispatchers.IO) {
            if (!hasReadContactsPermission()) return@withContext emptyList()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            )
            val selection = if (query.isBlank()) null else
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = if (query.isBlank()) null else arrayOf("%$query%")

            val contacts = LinkedHashMap<String, Contact>() // contact id -> first number
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            ) ?: return@withContext emptyList()

            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val keyIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                while (c.moveToNext()) {
                    val contactId = c.getString(idIdx)
                    if (contacts.containsKey(contactId)) continue
                    contacts[contactId] = Contact(
                        lookupKey = c.getString(keyIdx),
                        name = c.getString(nameIdx) ?: "Unknown",
                        phoneNumber = c.getString(numIdx)?.replace(" ", "") ?: "",
                        voipId = voipLinks[c.getString(keyIdx)],
                        photoUri = c.getString(photoIdx),
                    )
                }
            }
            contacts.values.toList()
        }

    /** Fast single-number lookup used by the in-call screen. */
    fun lookupName(number: String): String? {
        if (!hasReadContactsPermission()) return null
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(Uri.encode(number)).build()
        val cursor = contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        cursor.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
