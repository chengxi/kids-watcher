package com.kidsmonitor.parent.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.RawQuery
import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Upsert
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @RawQuery(observedEntities = [EventEntity::class])
    fun paged(query: SupportSQLiteQuery): PagingSource<Int, EventEntity>

    @Query("SELECT DISTINCT monitorId FROM events ORDER BY monitorId")
    fun observeMonitorIds(): Flow<List<String>>

    @Query("SELECT * FROM events WHERE monitorId = :id AND tsMs >= :fromMs ORDER BY tsMs")
    suspend fun inWindow(id: String, fromMs: Long): List<EventEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM events")
    suspend fun maxEventId(): Long

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}
