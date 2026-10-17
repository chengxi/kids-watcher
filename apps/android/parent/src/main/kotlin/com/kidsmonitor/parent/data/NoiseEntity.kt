package com.kidsmonitor.parent.data

import androidx.room.Entity
import com.kidsmonitor.shared.protocol.NoiseSample

@Entity(tableName = "noise_samples", primaryKeys = ["monitorId", "startMs"])
data class NoiseEntity(
    val monitorId: String,
    val startMs: Long,
    val endMs: Long,
    val levelDb: Double,
    val peakDb: Double,
    val thresholdDb: Double?,
) {
    fun sample() = NoiseSample(startMs, endMs, levelDb, peakDb, thresholdDb)
}
