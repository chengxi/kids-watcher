package com.kidsmonitor.parent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kidsmonitor.shared.net.StoredEvent
import com.kidsmonitor.shared.protocol.EventType

/** Chart markers have their own cache so a 24h query cannot skip the alert-backfill cursor. */
@Entity(tableName = "noise_alerts")
data class NoiseAlertEntity(
    @PrimaryKey val id: Long,
    val monitorId: String,
    val type: EventType,
    val tsMs: Long,
    val peakDb: Double?,
    val durationMs: Int?,
) {
    fun event() = EventEntity(id, monitorId, type, tsMs, peakDb, durationMs)
}

fun StoredEvent.toNoiseAlert() = NoiseAlertEntity(id, monitorId, type, tsMs, metadata.peakDb, metadata.durationMs)
