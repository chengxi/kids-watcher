package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.parent.data.NoiseHistoryRepository
import com.kidsmonitor.shared.net.HubApiException
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.NOISE_INTERVAL_MS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NoiseHistoryState(
    val monitorId: String? = null,
    val history: NoiseHistory = NoiseHistory(),
    val nowMs: Long = System.currentTimeMillis(),
    val loading: Boolean = false,
    val error: String? = null,
    val eventsTruncated: Boolean = false,
)

class NoiseHistoryStore internal constructor(
    private val cached: suspend (String, Long) -> NoiseHistory,
    private val load: suspend (String, Long) -> Pair<NoiseHistory, Boolean>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(repository: NoiseHistoryRepository) : this(repository::cached, repository::refresh)
    private val mutableState = MutableStateFlow(NoiseHistoryState())
    val state = mutableState.asStateFlow()

    fun tick() { mutableState.value = mutableState.value.copy(nowMs = now()) }

    suspend fun select(id: String) {
        val time = now()
        mutableState.value = NoiseHistoryState(monitorId = id, nowMs = time, loading = true)
        mutableState.value = mutableState.value.copy(history = cached(id, time), loading = false)
    }

    suspend fun refresh() {
        val id = state.value.monitorId ?: return
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val (history, truncated) = load(id, now())
            mutableState.value = state.value.copy(history = history, error = null, eventsTruncated = truncated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error =
                if (error is HubApiException && error.statusCode == 404) "Noise history unavailable. Update the hub to collect noise levels."
                else "Could not update noise history. Showing saved measurements.")
        } finally {
            mutableState.value = state.value.copy(loading = false)
            tick()
        }
    }
}

/** Owned by the visible home screen; cancellation stops both polling and pending requests. */
internal suspend fun NoiseHistoryStore.refreshWhileVisible(
    connection: StateFlow<ConnectionState>,
    intervalMs: Long = NOISE_INTERVAL_MS,
) = coroutineScope {
    val refreshes = Channel<Unit>(Channel.CONFLATED)
    launch { connection.collect { if (it == ConnectionState.Connected) refreshes.trySend(Unit) } }
    launch { for (request in refreshes) refresh() }
    launch {
        while (true) {
            delay(intervalMs)
            tick()
            if (connection.value == ConnectionState.Connected) refreshes.trySend(Unit)
        }
    }
}
