package com.kidsmonitor.parent.data

import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.StoredEvent
import com.kidsmonitor.shared.protocol.HubToParent
import kotlinx.coroutines.flow.Flow
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData

fun StoredEvent.toEntity(): EventEntity = EventEntity(
    id = id, monitorId = monitorId, type = type, tsMs = tsMs,
    peakDb = metadata.peakDb, durationMs = metadata.durationMs,
)

fun HubToParent.Event.toEntity(): EventEntity = EventEntity(
    id = eventId, monitorId = monitorId, type = type, tsMs = tsMs,
    peakDb = metadata.peakDb, durationMs = metadata.durationMs,
)

/**
 * The hub is the source of truth; this cache is what makes the app useful when
 * the hub is unreachable.
 */
class EventRepository(
    private val dao: EventDao,
    private val api: HubApi,
) {
    /**
     * Pull everything the hub has that this app does not, using the highest
     * locally-known event id as the cursor.
     *
     * Must run on every reconnect, before resuming normal operation (spec
     * §6.5). Without it, a socket killed at 02:00 by an OEM battery manager and
     * restored at 07:00 leaves no trace of the intervening hours -- and an
     * empty list reads as a quiet night rather than as missing data.
     *
     * Returns the number of events fetched.
     */
    suspend fun backfill(): Int {
        var cursor = dao.maxEventId()
        var count = 0
        while (true) {
            val fetched = api.events(since = cursor, limit = 200)
            if (fetched.isEmpty()) return count
            dao.upsertAll(fetched.map { it.toEntity() })
            count += fetched.size
            val next = fetched.maxOf { it.id }
            if (next <= cursor || fetched.size < 200) return count
            cursor = next
        }
    }

    suspend fun latestId(): Long = dao.maxEventId()

    /** Drain all pages from a captured cursor, independent of newer live arrivals. */
    suspend fun backfillFrom(since: Long): Long {
        var cursor = since
        while (true) {
            val fetched = api.events(since = cursor)
            if (fetched.isEmpty()) return cursor
            dao.upsertAll(fetched.map { it.toEntity() })
            val next = fetched.maxOf { it.id }
            if (next <= cursor) return cursor
            cursor = next
        }
    }

    suspend fun record(event: HubToParent.Event) {
        dao.upsertAll(listOf(event.toEntity()))
    }

    fun pages(filters: EventFilters): Flow<PagingData<EventEntity>> = Pager(
        PagingConfig(pageSize = 50, initialLoadSize = 100, prefetchDistance = 10,
            maxSize = 200, enablePlaceholders = false),
    ) { dao.paged(eventPageQuery(filters)) }.flow

    fun observeMonitorIds(): Flow<List<String>> = dao.observeMonitorIds()

    fun observeRecent(limit: Int = 200): Flow<List<EventEntity>> = dao.observeRecent(limit)

}
