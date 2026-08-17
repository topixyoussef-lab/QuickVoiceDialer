package com.quickvoice.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM recent_calls ORDER BY timestamp DESC LIMIT 200")
    fun observeAll(): Flow<List<RecentCallEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentCallEntity)

    @Query("DELETE FROM recent_calls WHERE timestamp < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long)
}
