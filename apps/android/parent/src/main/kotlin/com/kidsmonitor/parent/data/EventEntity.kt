package com.kidsmonitor.parent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.kidsmonitor.shared.protocol.EventType

@Entity(tableName = "events", indices = [Index(value = ["monitorId", "id"]), Index(value = ["type", "id"])])
data class EventEntity(
    // The hub's event id. It is a monotonic sequence per hub install and
    // doubles as the backfill cursor, so it is the natural primary key.
    @PrimaryKey val id: Long,
    val monitorId: String,
    val type: EventType,
    val tsMs: Long,
    val peakDb: Double?,
    val durationMs: Int?,
)
