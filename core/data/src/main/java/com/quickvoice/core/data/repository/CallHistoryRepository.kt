package com.quickvoice.core.data.repository

import com.quickvoice.core.data.db.CallDao
import com.quickvoice.core.data.db.toEntity
import com.quickvoice.core.data.db.toModel
import com.quickvoice.core.model.RecentCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallHistoryRepository @Inject constructor(
    private val callDao: CallDao,
) {
    val recents: Flow<List<RecentCall>> = callDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun add(call: RecentCall) {
        callDao.insert(call.toEntity())
    }

    suspend fun clearOlderThan(days: Int = 60) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        callDao.deleteOlderThan(cutoff)
    }
}
