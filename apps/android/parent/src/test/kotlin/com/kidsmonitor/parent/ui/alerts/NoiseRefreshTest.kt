package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.NoiseSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoiseRefreshTest {
    @Test fun `visible home gets new measurements without navigation and stops polling when hidden`() = runTest {
        var calls = 0
        val store = NoiseHistoryStore(
            cached = { _, _ -> NoiseHistory() },
            load = { _, now ->
                calls++
                val level = if (calls == 1) -50.0 else -30.0
                NoiseHistory(listOf(NoiseSample(now - 15_000, now, level, level, -38.0))) to false
            },
            now = { 60_000 + testScheduler.currentTime },
        )
        store.select("room")
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val polling = launch { store.refreshWhileVisible(connection) }
        runCurrent()
        assertEquals(-50.0, store.state.value.history.samples.single().levelDb, 0.001)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(-30.0, store.state.value.history.samples.single().levelDb, 0.001)
        connection.value = ConnectionState.Reconnecting
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, calls)
        connection.value = ConnectionState.Connected
        runCurrent()
        assertEquals(3, calls)
        polling.cancelAndJoin()
        advanceTimeBy(30_000)
        assertEquals(3, calls)
    }
}
