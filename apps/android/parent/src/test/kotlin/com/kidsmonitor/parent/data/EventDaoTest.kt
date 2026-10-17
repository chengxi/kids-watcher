package com.kidsmonitor.parent.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.protocol.EventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventDaoTest {

    private lateinit var db: ParentDatabase
    private lateinit var dao: EventDao

    private fun event(id: Long, type: EventType = EventType.LOUD_NOISE, tsMs: Long = id * 1000) =
        EventEntity(
            id = id, monitorId = "m1", type = type, tsMs = tsMs,
            peakDb = -9.5, durationMs = 2200,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ParentDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.events()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `maxEventId is zero on an empty cache so the first backfill asks for everything`() = runTest {
        assertEquals(0L, dao.maxEventId())
    }

    @Test
    fun `maxEventId returns the newest hub id`() = runTest {
        dao.upsertAll(listOf(event(1), event(7), event(3)))
        assertEquals(7L, dao.maxEventId())
    }

    @Test
    fun `upsert is idempotent so replayed backfill does not duplicate`() = runTest {
        dao.upsertAll(listOf(event(1), event(2)))
        dao.upsertAll(listOf(event(1), event(2), event(3)))
        assertEquals(3, dao.observeRecent(100).first().size)
    }

    @Test
    fun `observeRecent returns newest first`() = runTest {
        dao.upsertAll(listOf(event(1), event(2), event(3)))
        assertEquals(listOf(3L, 2L, 1L), dao.observeRecent(100).first().map { it.id })
    }

    @Test
    fun `observeRecent honours the limit`() = runTest {
        dao.upsertAll((1L..10L).map { event(it) })
        assertEquals(3, dao.observeRecent(3).first().size)
    }

    @Test
    fun `round-trips metadata including nulls`() = runTest {
        dao.upsertAll(listOf(event(1).copy(peakDb = null, durationMs = null)))
        val row = dao.observeRecent(1).first().single()
        assertEquals(null, row.peakDb)
        assertEquals(null, row.durationMs)
    }

    @Test
    fun `round-trips the event type enum`() = runTest {
        dao.upsertAll(listOf(event(1, type = EventType.MONITOR_LOST)))
        assertEquals(EventType.MONITOR_LOST, dao.observeRecent(1).first().single().type)
    }

    @Test
    fun `re-upserting still refreshes the mutable fields`() = runTest {
        dao.upsertAll(listOf(event(1)))
        dao.upsertAll(listOf(event(1).copy(peakDb = -3.0, tsMs = 99)))
        val row = dao.observeRecent(1).first().single()
        assertEquals(-3.0, row.peakDb!!, 0.001)
        assertEquals(99L, row.tsMs)
    }
    @Test
    fun `pages reach history beyond the old 200 row cutoff without deleting it`() = runTest {
        dao.upsertAll((1L..600L).map { event(it) })
        val source = dao.paged(eventPageQuery(EventFilters()))
        var page = source.load(PagingSource.LoadParams.Refresh<Int>(null, 100, false)) as PagingSource.LoadResult.Page<Int, EventEntity>
        val ids = page.data.map { it.id }.toMutableList()
        while (page.nextKey != null) {
            page = source.load(PagingSource.LoadParams.Append(page.nextKey!!, 50, false)) as PagingSource.LoadResult.Page<Int, EventEntity>
            ids.addAll(page.data.map { it.id })
        }
        assertEquals((600L downTo 1L).toList(), ids)
        assertEquals(600, dao.observeRecent(1000).first().size)
    }

    @Test
    fun `device and category filter the entire database before paging`() = runTest {
        dao.upsertAll((1L..500L).map { event(it).copy(monitorId = "m2") } + listOf(
            event(1, EventType.MONITOR_LOST), event(2, EventType.MONITOR_ONLINE), event(3),
        ))
        val source = dao.paged(eventPageQuery(EventFilters("m1", EventCategory.CONNECTION)))
        val page = source.load(PagingSource.LoadParams.Refresh<Int>(null, 50, false)) as PagingSource.LoadResult.Page<Int, EventEntity>
        assertEquals(listOf(2L, 1L), page.data.map { it.id })
        assertEquals(null, page.nextKey)
        assertEquals(listOf("m1", "m2"), dao.observeMonitorIds().first())
        val noise = dao.paged(eventPageQuery(EventFilters("m1", EventCategory.NOISE)))
            .load(PagingSource.LoadParams.Refresh<Int>(null, 50, false)) as PagingSource.LoadResult.Page<Int, EventEntity>
        assertEquals(listOf(3L), noise.data.map { it.id })
    }

    @Test
    fun `new live rows invalidate existing pages so the latest event appears`() = runTest {
        dao.upsertAll(listOf(event(1)))
        val source = dao.paged(eventPageQuery(EventFilters()))
        source.load(PagingSource.LoadParams.Refresh<Int>(null, 50, false))
        dao.upsertAll(listOf(event(2, EventType.MONITOR_ONLINE)))
        db.invalidationTracker.refreshVersionsSync()
        assertTrue(source.invalid)
        val refreshed = dao.paged(eventPageQuery(EventFilters()))
            .load(PagingSource.LoadParams.Refresh<Int>(null, 50, false)) as PagingSource.LoadResult.Page<Int, EventEntity>
        assertEquals(listOf(2L, 1L), refreshed.data.map { it.id })
    }

}
