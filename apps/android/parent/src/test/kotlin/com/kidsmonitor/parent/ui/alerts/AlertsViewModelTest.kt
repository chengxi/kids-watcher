package com.kidsmonitor.parent.ui.alerts

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.parent.data.EventRepository
import com.kidsmonitor.parent.data.EventFilters
import com.kidsmonitor.parent.data.EventCategory
import com.kidsmonitor.parent.data.ParentDatabase
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.EventMetadata
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.HubToParent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertsViewModelTest {

    private lateinit var db: ParentDatabase
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repository: EventRepository
    private val connected = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ParentDatabase::class.java,
        ).allowMainThreadQueries().build()
        client = OkHttpClient()
        server = MockWebServer()
        server.start()
        repository = EventRepository(
            db.events(), HubApi(client, server.url("/").toString().trimEnd('/'), "tok"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
        Dispatchers.resetMain()
    }

    private fun event(id: Long, type: EventType = EventType.LOUD_NOISE) = HubToParent.Event(
        eventId = id, monitorId = "m1", type = type, tsMs = id * 1000,
        metadata = EventMetadata(peakDb = -9.5, durationMs = 2200),
    )

    private suspend fun awaitState(predicate: (AlertsUiState) -> Boolean): AlertsUiState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModelUnderTest.state.first(predicate) }
        }

    private lateinit var viewModelUnderTest: AlertsViewModel

    private fun newViewModel(): AlertsViewModel =
        AlertsViewModel(repository, connected).also { viewModelUnderTest = it }

    @Test
    fun `surfaces stored events newest first`() = runTest {
        repository.record(event(1))
        repository.record(event(2))
        newViewModel()
        val state = awaitState { it.events.size == 2 }
        assertEquals(listOf(2L, 1L), state.events.map { it.id })
    }

    @Test
    fun `reflects connection state`() = runTest {
        newViewModel()
        assertFalse(awaitState { true }.connected)
        connected.value = ConnectionState.Connected
        assertTrue(awaitState { it.connected }.connected)
        connected.value = ConnectionState.WaitingToRetry(8_000, "wifi unavailable")
        assertFalse(awaitState { it.connection is ConnectionState.WaitingToRetry }.connected)
        connected.value = ConnectionState.Reconnecting
        assertEquals(ConnectionState.Reconnecting, awaitState { it.connection == ConnectionState.Reconnecting }.connection)
    }

    @Test
    fun `shows history even with no hub reachable`() = runTest {
        repository.record(event(1))
        server.close() // hub gone
        newViewModel()
        // The whole point of the Room cache: an unreachable hub must not mean
        // an empty screen, which would read as a quiet night.
        assertEquals(1, awaitState { it.events.isNotEmpty() }.events.size)
    }

    @Test
    fun `a MONITOR_LOST event is present in the list like any other`() = runTest {
        repository.record(event(1, EventType.MONITOR_LOST))
        newViewModel()
        assertEquals(
            EventType.MONITOR_LOST,
            awaitState { it.events.isNotEmpty() }.events.single().type,
        )
    }
    @Test
    fun `changing filters starts a new history with unique day and event keys`() = runTest {
        repository.record(event(1, EventType.MONITOR_LOST))
        repository.record(event(2, EventType.MONITOR_ONLINE))
        repository.record(event(3))
        val vm = newViewModel()
        val all = vm.pagedEvents.asSnapshot()
        assertEquals(listOf(3L, 2L, 1L), all.filterIsInstance<EventListItem.Event>().map { it.value.id })
        assertEquals(all.size, all.map { it.key }.distinct().size)
        assertEquals(1, all.filterIsInstance<EventListItem.Day>().size)
        vm.setFilters(EventFilters("m1", EventCategory.CONNECTION))
        val filtered = vm.pagedEvents.asSnapshot()
        assertEquals(listOf(2L, 1L), filtered.filterIsInstance<EventListItem.Event>().map { it.value.id })
        vm.setFilters(EventFilters("missing"))
        assertTrue(vm.pagedEvents.asSnapshot().isEmpty())
        vm.setFilters(EventFilters())
        assertEquals(3, vm.pagedEvents.asSnapshot().filterIsInstance<EventListItem.Event>().size)
    }

}
