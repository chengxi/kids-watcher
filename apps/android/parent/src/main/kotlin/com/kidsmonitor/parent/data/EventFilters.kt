package com.kidsmonitor.parent.data

import com.kidsmonitor.shared.protocol.EventType
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

enum class EventCategory(val label: String, val types: List<EventType>) {
    ALL("All events", emptyList()),
    NOISE("Noise alerts", listOf(EventType.LOUD_NOISE)),
    CONNECTION("Connection", listOf(EventType.MONITOR_LOST, EventType.MONITOR_ONLINE)),
    MOTION("Movement", listOf(EventType.MOTION)),
    MONITORING("Monitoring", listOf(EventType.MONITOR_STARTED, EventType.MONITOR_STOPPED)),
}

data class EventFilters(val monitorId: String? = null, val category: EventCategory = EventCategory.ALL) {
    val active: Boolean get() = monitorId != null || category != EventCategory.ALL
}

/** Filter in SQLite before paging, so an old matching event is never hidden by a recent-row cap. */
internal fun eventPageQuery(filters: EventFilters): SupportSQLiteQuery {
    val clauses = mutableListOf<String>()
    val arguments = mutableListOf<Any>()
    filters.monitorId?.let { clauses += "monitorId = ?"; arguments += it }
    if (filters.category.types.isNotEmpty()) {
        clauses += "type IN (${filters.category.types.joinToString { "?" }})"
        arguments.addAll(filters.category.types.map { it.name })
    }
    val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
    return SimpleSQLiteQuery("SELECT * FROM events$where ORDER BY id DESC", arguments.toTypedArray())
}
