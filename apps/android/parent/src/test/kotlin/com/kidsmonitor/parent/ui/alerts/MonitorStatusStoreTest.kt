package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.net.MonitorStatusDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import com.kidsmonitor.shared.net.HubApiException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MonitorStatusStoreTest {
    private val online = MonitorStatusDto("m1", "Nursery", true, 1L, 88)

    @Test
    fun `failed poll preserves identity but cannot report offline or permit watching`() = runTest {
        var fail = false
        val store = MonitorStatusStore { if (fail) throw IOException("no network") else listOf(online) }
        store.refresh()
        fail = true
        store.refresh()
        val state = store.state.value
        assertEquals(listOf(online), state.monitors)
        assertFalse(state.current)
        assertTrue(state.failed)
        assertEquals("Status unavailable — retrying", monitorAvailabilityText(online, ConnectionState.Connected, state))
        assertEquals("Monitor battery unavailable", monitorBatteryText(online, state.current))
    }

    @Test
    fun `hub report distinguishes offline from unavailable and recovery restores online`() = runTest {
        var report = online.copy(online = false)
        val store = MonitorStatusStore { listOf(report) }
        store.refresh()
        assertEquals("Offline — reported by hub", monitorAvailabilityText(report, ConnectionState.Connected, store.state.value))
        report = online
        store.invalidate()
        assertFalse(store.state.value.current)
        store.refresh()
        assertEquals("Online", monitorAvailabilityText(report, ConnectionState.Connected, store.state.value))
    }

    @Test
    fun `disconnect during a request invalidates its eventual result`() = runTest {
        val response = CompletableDeferred<List<MonitorStatusDto>>()
        val store = MonitorStatusStore { response.await() }
        val request = launch { store.refresh() }
        runCurrent()
        assertTrue(store.state.value.refreshing)
        store.invalidate()
        response.complete(listOf(online))
        request.join()
        assertFalse(store.state.value.current)
    }

    @Test
    fun `screen cancellation releases refresh without claiming the monitor failed`() = runTest {
        var calls = 0
        val response = CompletableDeferred<List<MonitorStatusDto>>()
        val store = MonitorStatusStore { calls++; if (calls == 1) response.await() else listOf(online) }
        val request = launch { store.refresh() }
        runCurrent()
        store.refresh() // concurrent triggers coalesce
        assertEquals(1, calls)
        request.cancel()
        request.join()
        assertFalse(store.state.value.refreshing)
        assertFalse(store.state.value.failed)
        store.refresh()
        assertTrue(store.state.value.current)
    }

    @Test
    fun `cached offline report is unknown while parent reconnects`() {
        val offline = online.copy(online = false)
        for (connection in listOf(ConnectionState.Connecting, ConnectionState.Reconnecting, ConnectionState.WaitingToRetry(1000, "closed"))) {
            assertEquals("Status unknown — waiting for hub connection", monitorAvailabilityText(offline, connection, MonitorListState(listOf(offline), current = true)))
            assertTrue(connectionBannerText(connection)!!.contains("alerts", ignoreCase = true))
            assertFalse(connectionBannerText(connection)!!.contains("Not connected"))
        }
        assertNull(connectionBannerText(ConnectionState.Connected))
    }
    @Test
    fun `cold status request recovers automatically on the third attempt`() = runTest {
        var calls = 0
        val store = MonitorStatusStore { calls++; if (calls < 3) throw IOException("timed out") else listOf(online) }
        val job = launch { store.refresh() }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1_000L, store.state.value.retryDelayMs)
        assertTrue(monitorRefreshText(store.state.value).contains("timed out"))
        assertFalse(store.state.value.current)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(2_000); runCurrent()
        job.join()
        assertEquals(3, calls)
        assertTrue(store.state.value.current)
        assertFalse(store.state.value.failed)
    }

    @Test
    fun `authorization errors are reported without repeatedly sending the same request`() = runTest {
        var calls = 0
        val store = MonitorStatusStore { calls++; throw HubApiException(401, "revoked") }
        store.refresh()
        assertEquals(1, calls)
        assertTrue(store.state.value.failureText!!.contains("authorization"))
    }

    @Test
    fun `leaving the screen cancels a pending status retry`() = runTest {
        var calls = 0
        val store = MonitorStatusStore { calls++; throw IOException("timed out") }
        val job = launch { store.refresh() }
        runCurrent()
        job.cancel(); job.join()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        assertFalse(store.state.value.refreshing)
        assertNull(store.state.value.retryDelayMs)
    }

}
