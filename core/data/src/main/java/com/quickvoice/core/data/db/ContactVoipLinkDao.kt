package com.quickvoice.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactVoipLinkDao {
    @Query("SELECT * FROM contact_voip_links")
    fun observeAll(): Flow<List<ContactVoipLinkEntity>>

    @Query("SELECT voip_id FROM contact_voip_links WHERE lookup_key = :lookupKey")
    suspend fun voipIdFor(lookupKey: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContactVoipLinkEntity)

    @Query("DELETE FROM contact_voip_links WHERE lookup_key = :lookupKey")
    suspend fun delete(lookupKey: String)
}
