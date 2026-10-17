package com.kidsmonitor.parent.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.protocol.EventMetadata
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.HubToParent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventRepositoryTest {

    private lateinit var db: ParentDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repo: EventRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ParentDatabase::class.java,
        ).allowMainThreadQueries().build()
        client = OkHttpClient()
        server = MockWebServer()
        server.start()
        repo = EventRepository(
            db.events(),
            HubApi(client, server.url("/").toString().trimEnd('/'), "tok"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }

    private fun hubEvent(id: Long, type: String = "LOUD_NOISE") =
        """{"id":$id,"monitorId":"m1","type":"$type","tsMs":${id * 1000},"metadata":{"peakDb":-9.5,"durationMs":2200}}"""

    private fun liveEvent(id: Long) = HubToParent.Event(
        eventId = id, monitorId = "m1", type = EventType.LOUD_NOISE, tsMs = id * 1000,
        metadata = EventMetadata(peakDb = -9.5, durationMs = 2200),
    )

    @Test
    fun `backfill on an empty cache asks the hub for everything`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[${hubEvent(1)},${hubEvent(2)}]}""").build())
        assertEquals(2, repo.backfill())
        assertTrue(server.takeRequest().target!!.contains("since=0"))
        assertEquals(2, repo.observeRecent().first().size)
    }

    @Test
    fun `backfill asks only for what it is missing`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[${hubEvent(1)},${hubEvent(2)}]}""").build())
        repo.backfill()
        server.takeRequest()

        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[${hubEvent(3)}]}""").build())
        repo.backfill()
        assertTrue(server.takeRequest().target!!.contains("since=2"))
        assertEquals(3, repo.observeRecent().first().size)
    }

    @Test
    fun `the overnight gap - five hours of missed events are recovered on reconnect`() = runTest {
        // The app saw event 10 before its socket was killed at 2am.
        repo.record(liveEvent(10))
        // While it was dead the hub recorded 11..14, including a MONITOR_LOST.
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"events":[${hubEvent(11)},${hubEvent(12, "MONITOR_LOST")},${hubEvent(13)},${hubEvent(14)}]}""",
            ).build(),
        )
        assertEquals(4, repo.backfill())
        assertTrue(server.takeRequest().target!!.contains("since=10"))

        val rows = repo.observeRecent().first()
        assertEquals(listOf(14L, 13L, 12L, 11L, 10L), rows.map { it.id })
        assertTrue(
            "the MONITOR_LOST raised overnight must survive into the history",
            rows.any { it.type == EventType.MONITOR_LOST },
        )
    }

    @Test
    fun `backfill overlapping the cache does not duplicate events`() = runTest {
        repo.record(liveEvent(1))
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[${hubEvent(1)},${hubEvent(2)}]}""").build())
        repo.backfill()

        assertEquals(2, repo.observeRecent().first().size)
    }

    @Test
    fun `backfill of nothing new is a no-op`() = runTest {
        repo.record(liveEvent(5))
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[]}""").build())
        assertEquals(0, repo.backfill())
        assertEquals(1, repo.observeRecent().first().size)
    }

    @Test
    fun `a live event is stored with its metadata`() = runTest {
        repo.record(liveEvent(4))
        val row = repo.observeRecent().first().single()
        assertEquals(4L, row.id)
        assertEquals(-9.5, row.peakDb!!, 0.001)
        assertEquals(2200, row.durationMs)
    }

    @Test
    fun `captured recovery cursor drains all pages despite newer live arrivals`() = runTest {
        repo.record(liveEvent(10))
        val cursor = repo.latestId()
        repo.record(liveEvent(100))
        server.enqueue(MockResponse.Builder().body("""{"events":[${hubEvent(11)},${hubEvent(12)}]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"events":[${hubEvent(13)}]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"events":[]}""").build())
        assertEquals(13L, repo.backfillFrom(cursor))
        assertTrue(server.takeRequest().target!!.contains("since=10"))
        assertTrue(server.takeRequest().target!!.contains("since=12"))
        assertTrue(server.takeRequest().target!!.contains("since=13"))
        assertEquals(listOf(100L, 13L, 12L, 11L, 10L), repo.observeRecent().first().map { it.id })
    }

    @Test
    fun `failed recovery can retry from the retained cursor after live inserts`() = runTest {
        repo.record(liveEvent(10))
        val cursor = repo.latestId()
        server.enqueue(MockResponse.Builder().body("""{"events":[${hubEvent(11)}]}""").build())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(runCatching { repo.backfillFrom(cursor) }.isFailure)
        server.takeRequest(); server.takeRequest()
        repo.record(liveEvent(100))
        server.enqueue(MockResponse.Builder().body("""{"events":[${hubEvent(11)},${hubEvent(12)}]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"events":[]}""").build())
        assertEquals(12L, repo.backfillFrom(cursor))
        assertTrue(server.takeRequest().target!!.contains("since=10"))
        assertTrue(repo.observeRecent().first().any { it.id == 12L })
    }

    @Test
    fun `manual refresh drains full pages instead of leaving older recovered events missing`() = runTest {
        for (start in listOf(1L, 201L)) {
            val body = (start until start + 200).joinToString(",") { hubEvent(it) }
            server.enqueue(MockResponse.Builder().body("{\"events\":[$body]}").build())
        }
        server.enqueue(MockResponse.Builder().body("{\"events\":[${hubEvent(401, "MONITOR_ONLINE")}]}").build())
        assertEquals(401, repo.backfill())
        for (cursor in listOf(0, 200, 400)) assertTrue(server.takeRequest().target!!.contains("since=$cursor"))
        assertEquals(401, repo.observeRecent(1000).first().size)
        assertEquals(EventType.MONITOR_ONLINE, repo.observeRecent(1).first().single().type)
    }

}
