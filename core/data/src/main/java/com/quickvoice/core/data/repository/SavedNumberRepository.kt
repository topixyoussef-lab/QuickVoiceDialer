package com.quickvoice.core.data.repository

import com.quickvoice.core.data.db.SavedNumberDao
import com.quickvoice.core.data.db.SavedNumberEntity
import com.quickvoice.core.data.db.toModel
import com.quickvoice.core.model.Contact
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SavedNumberRepository @Inject constructor(
    private val dao: SavedNumberDao,
) {
    /** Numbers the user saved, mapped to contacts whose VoIP id is the number itself. */
    val savedContacts: Flow<List<Contact>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    suspend fun isSaved(number: String): Boolean = dao.byNumber(number) != null

    suspend fun save(number: String, name: String) {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        val n = name.trim().ifEmpty { trimmed }
        dao.upsert(SavedNumberEntity(number = trimmed, name = n))
    }

    suspend fun delete(number: String) {
        dao.delete(number)
    }
}
