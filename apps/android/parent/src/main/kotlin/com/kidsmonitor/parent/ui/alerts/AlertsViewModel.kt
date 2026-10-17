package com.kidsmonitor.parent.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.EventRepository
import com.kidsmonitor.shared.net.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.paging.insertSeparators
import com.kidsmonitor.parent.data.EventFilters
import java.time.Instant
import java.time.ZoneId

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlertsUiState(
    val events: List<EventEntity> = emptyList(),
    val connection: ConnectionState = ConnectionState.Stopped,
    val filters: EventFilters = EventFilters(),
    val eventMonitorIds: List<String> = emptyList(),
) {
    val connected: Boolean get() = connection == ConnectionState.Connected
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertsViewModel(
    private val repository: EventRepository,
    connection: StateFlow<ConnectionState>,
) : ViewModel() {

    private val filters = MutableStateFlow(EventFilters())
    val pagedEvents = filters.flatMapLatest(repository::pages).map { page ->
        page.map<EventEntity, EventListItem> { EventListItem.Event(it) }
            .insertSeparators { before, after ->
                val event = (after as? EventListItem.Event)?.value
                val previous = (before as? EventListItem.Event)?.value
                if (event != null && (previous == null || eventDay(previous.tsMs) != eventDay(event.tsMs)))
                    EventListItem.Day(event.tsMs, event.id)
                else null
            }
    }.cachedIn(viewModelScope)

    fun setFilters(value: EventFilters) { filters.value = value }

    val state: StateFlow<AlertsUiState> = combine(
        repository.observeRecent(), connection, filters, repository.observeMonitorIds(),
    ) { events, state, selectedFilters, monitorIds ->
        AlertsUiState(events = events, connection = state, filters = selectedFilters, eventMonitorIds = monitorIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState(connection = connection.value))

    /** Manual refresh. Failure is not fatal: the cache still renders. */
    fun refresh() {
        viewModelScope.launch { runCatching { repository.backfill() } }
    }
}

internal fun eventDay(timeMs: Long) = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalDate()

sealed interface EventListItem {
    val key: String
    data class Event(val value: EventEntity) : EventListItem { override val key: String get() = "event-${value.id}" }
    data class Day(val timeMs: Long, val firstEventId: Long) : EventListItem { override val key: String get() = "day-$firstEventId" }
}
