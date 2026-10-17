package com.kidsmonitor.parent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoiseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(samples: List<NoiseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlerts(events: List<NoiseAlertEntity>)

    @Query("SELECT * FROM noise_alerts WHERE monitorId = :id AND tsMs >= :fromMs ORDER BY tsMs")
    suspend fun alertsSince(id: String, fromMs: Long): List<NoiseAlertEntity>

    @Query("DELETE FROM noise_alerts WHERE tsMs < :beforeMs")
    suspend fun pruneAlerts(beforeMs: Long)

    @Query("SELECT * FROM noise_samples WHERE monitorId = :id AND endMs > :fromMs ORDER BY startMs")
    suspend fun since(id: String, fromMs: Long): List<NoiseEntity>

    @Query("SELECT MAX(endMs) FROM noise_samples WHERE monitorId = :id")
    suspend fun latestEnd(id: String): Long?

    @Query("DELETE FROM noise_samples WHERE endMs < :beforeMs")
    suspend fun prune(beforeMs: Long)
}
