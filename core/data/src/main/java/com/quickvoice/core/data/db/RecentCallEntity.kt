package com.quickvoice.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.RecentCall

@Entity(tableName = "recent_calls")
data class RecentCallEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "number")
    val number: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
)

fun RecentCallEntity.toModel(): RecentCall = RecentCall(
    id = id,
    number = number,
    displayName = displayName,
    type = CallType.valueOf(type),
    direction = CallDirection.valueOf(direction),
    state = CallState.valueOf(state),
    durationMs = durationMs,
    timestamp = timestamp,
)

fun RecentCall.toEntity(): RecentCallEntity = RecentCallEntity(
    id = id,
    number = number,
    displayName = displayName,
    type = type.name,
    direction = direction.name,
    state = state.name,
    durationMs = durationMs,
    timestamp = timestamp,
)
