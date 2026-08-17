package com.quickvoice.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedNumberDao {
    @Query("SELECT * FROM saved_numbers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SavedNumberEntity>>

    @Query("SELECT * FROM saved_numbers WHERE number = :number")
    suspend fun byNumber(number: String): SavedNumberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedNumberEntity)

    @Query("DELETE FROM saved_numbers WHERE number = :number")
    suspend fun delete(number: String)
}
