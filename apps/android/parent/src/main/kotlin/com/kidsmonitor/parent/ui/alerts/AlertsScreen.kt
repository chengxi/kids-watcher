package com.kidsmonitor.parent.ui.alerts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.compose.itemContentType
import com.kidsmonitor.parent.data.EventFilters
import com.kidsmonitor.parent.data.EventCategory
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.service.Notifications
import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    dndBanner: @Composable () -> Unit = {},
    monitorList: MonitorListState = MonitorListState(),
    onRefresh: () -> Unit = {},
    onWatch: (MonitorStatusDto) -> Unit = {},
    noiseHistory: NoiseHistoryState = NoiseHistoryState(),
    onSelectChartMonitor: (String) -> Unit = {},
    hubAddress: String = "Home hub",
    selectedMonitorId: String? = noiseHistory.monitorId,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val events = viewModel.pagedEvents.collectAsLazyPagingItems()
    ParentHome(state, monitorList, noiseHistory, selectedMonitorId, hubAddress, dndBanner,
        onRefresh = { viewModel.refresh(); onRefresh() }, onWatch = onWatch,
        onSelect = onSelectChartMonitor, eventItems = events, onFiltersChanged = viewModel::setFilters)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ParentHome(
    state: AlertsUiState,
    monitorList: MonitorListState,
    noiseHistory: NoiseHistoryState,
    selectedMonitorId: String?,
    hubAddress: String,
    dndBanner: @Composable () -> Unit = {},
    onRefresh: () -> Unit,
    onWatch: (MonitorStatusDto) -> Unit,
    onSelect: (String) -> Unit,
    eventItems: LazyPagingItems<EventListItem>? = null,
    onFiltersChanged: (EventFilters) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val applyFilters: (EventFilters) -> Unit = { value ->
        val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "event-filters" }?.index ?: 0
        onFiltersChanged(value)
        scope.launch { listState.scrollToItem(header) }
    }
    val locale = LocalConfiguration.current.locales[0]
    var showStatus by remember { mutableStateOf(false) }
    val selected = monitorList.monitors.find { it.id == selectedMonitorId } ?: monitorList.monitors.firstOrNull()
    val current = state.connected && monitorList.current
    val systemColor = when {
        !state.connected -> Problem
        !monitorList.current || monitorList.monitors.isEmpty() -> Unknown
        monitorList.monitors.any { !it.online || (it.batteryPct ?: 100) <= 15 } -> Problem
        else -> Healthy
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kid Monitor", fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showStatus = true }, modifier = Modifier.semantics { contentDescription = "System status: ${if (systemColor == Healthy) "online" else if (systemColor == Problem) "problem" else "unknown"}. Tap for details" }) {
                        MonitorSymbol(MonitorIcon.HUB)
                        Spacer(Modifier.width(8.dp))
                        StatusDot(systemColor)
                        Spacer(Modifier.width(6.dp))
                        Text("Status", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item { dndBanner() }
            if (monitorList.monitors.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        monitorList.monitors.forEach { monitor ->
                            FilterChip(
                                selected = monitor.id == selected?.id,
                                onClick = { onSelect(monitor.id) },
                                label = { Text(monitor.name) },
                                leadingIcon = { StatusDot(if (!current) Unknown else if (monitor.online) Healthy else Problem) },
                                shape = RoundedCornerShape(24.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                            )
                        }
                    }
                }
                item {
                    selected?.let { monitor ->
                        MonitorCard(monitor, state.connection, monitorList,
                            onWatch = { onWatch(monitor) })
                    }
                }
            } else {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (current) "No monitors paired" else "Waiting for monitors", style = MaterialTheme.typography.titleMedium)
                            Text(if (current) "Pair a monitor phone with your home hub to see this room here." else connectionBannerText(state.connection) ?: monitorRefreshText(monitorList))
                            TextButton(onClick = onRefresh) { Text("Refresh") }
                        }
                    }
                }
            }
            // Failures remain visible without requiring the user to open the status sheet.
            if (!current && monitorList.monitors.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (state.connected) Unknown else Problem)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.connected) "Monitor status unavailable" else "Hub disconnected", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    NoiseHistoryChart(noiseHistory, emptyList(), onSelect)
                }
            }
            stickyHeader(key = "event-filters") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Events", style = MaterialTheme.typography.titleMedium)
                    EventFiltersBar(state.filters, monitorList.monitors, state.eventMonitorIds, applyFilters)
                }
            }
            val dateFormat = SimpleDateFormat("EEE d MMM yyyy", locale)
            if (eventItems != null) {
                if (eventItems.itemCount == 0 && eventItems.loadState.refresh !is LoadState.Loading && eventItems.loadState.refresh !is LoadState.Error) {
                    item { EventEmptyState(state, applyFilters) }
                }
                items(eventItems.itemCount, key = eventItems.itemKey { it.key },
                    contentType = eventItems.itemContentType { if (it is EventListItem.Day) "day" else "event" }) { index ->
                    when (val item = eventItems[index]) {
                        is EventListItem.Day -> Text(dateFormat.format(Date(item.timeMs)).uppercase(locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is EventListItem.Event -> EventRow(item.value, monitorList.monitors.find { it.id == item.value.monitorId }?.name ?: "Monitor")
                        null -> Spacer(Modifier.height(72.dp))
                    }
                }
                val loading = eventItems.loadState.refresh is LoadState.Loading || eventItems.loadState.append is LoadState.Loading || eventItems.loadState.prepend is LoadState.Loading
                val failed = eventItems.loadState.refresh is LoadState.Error || eventItems.loadState.append is LoadState.Error || eventItems.loadState.prepend is LoadState.Error
                item(key = "events-footer") {
                    when {
                        loading -> Text("Loading events…", style = MaterialTheme.typography.bodySmall)
                        failed -> Column {
                            Text("Couldn't load saved events.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = eventItems::retry) { Text("Try again") }
                        }
                        eventItems.itemCount > 0 && eventItems.loadState.append.endOfPaginationReached -> Text("End of saved events", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        eventItems.itemCount > 0 -> Text("Older events load as you scroll.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // Static histories keep previews/render fixtures independent of database paging.
                if (state.events.isEmpty()) item { EventEmptyState(state, applyFilters) }
                state.events.groupBy { dateFormat.format(Date(it.tsMs)) }.forEach { (day, events) ->
                    item(key = "day-${events.first().id}") { Text(day.uppercase(locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(events, key = { "event-${it.id}" }) { event ->
                        EventRow(event, monitorList.monitors.find { it.id == event.monitorId }?.name ?: "Monitor")
                    }
                }
            }
        }
    }
    if (showStatus) {
        ModalBottomSheet(onDismissRequest = { showStatus = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Status", style = MaterialTheme.typography.titleLarge)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (state.connected) Healthy else Problem)
                            Text("Home hub", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(hubAddress, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(connectionBannerText(state.connection) ?: "Connected · receiving live alerts", style = MaterialTheme.typography.bodySmall)
                        if (state.connected && !monitorList.current) Text(monitorRefreshText(monitorList), style = MaterialTheme.typography.bodySmall)
                    }
                }
                monitorList.monitors.sortedBy { it.online }.forEach { monitor ->
                    OutlinedCard(border = BorderStroke(1.dp, if (current && !monitor.online) Problem else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(if (!current) Unknown else if (monitor.online) Healthy else Problem)
                                Text(monitor.name, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(monitorAvailabilityText(monitor, state.connection, monitorList), style = MaterialTheme.typography.bodyMedium)
                            Text(monitorBatteryText(monitor, current), style = MaterialTheme.typography.bodySmall)
                            monitor.lastSeenMs?.let { Text("Last report: ${SimpleDateFormat("EEE HH:mm:ss", locale).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                            if (current && !monitor.online) Text("Check the monitor phone, its charger, and its network.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusLegend(Healthy, "online")
                    StatusLegend(Unknown, "unknown")
                    StatusLegend(Problem, "problem")
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
            }
        }
    }
}

@Composable
private fun StatusLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { StatusDot(color); Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun MonitorCard(monitor: MonitorStatusDto, connection: ConnectionState, list: MonitorListState, onWatch: () -> Unit) {
    val available = connection == ConnectionState.Connected && list.current && monitor.online
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(monitor.name, style = MaterialTheme.typography.titleMedium)
                    Text(monitorAvailabilityText(monitor, connection, list), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = monitorBatteryText(monitor, available) }) {
                    MonitorSymbol(MonitorIcon.BATTERY, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (available) monitor.batteryPct?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—" else "—")
                }
            }
            Button(onClick = onWatch, enabled = available, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                MonitorSymbol(MonitorIcon.CAMERA, color = if (available) MaterialTheme.colorScheme.onPrimary else Unknown)
                Spacer(Modifier.width(10.dp))
                Text("Watch live", fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, name: String) {
    val locale = LocalConfiguration.current.locales[0]
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp)) {
                Text(SimpleDateFormat("h:mm a", locale).format(Date(event.tsMs)), style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(Notifications.titleFor(event.type, name), style = MaterialTheme.typography.bodySmall,
                    color = when (event.type) { EventType.MONITOR_ONLINE -> Healthy; EventType.MONITOR_LOST -> Problem; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                if (event.type == EventType.MONITOR_LOST) Text("Check the monitor phone and its connection.", style = MaterialTheme.typography.bodySmall)
            }
            if (event.type == EventType.LOUD_NOISE) event.peakDb?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("%.0f".format(it), fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = MaterialTheme.colorScheme.tertiary)
                    Text("dBFS", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EventEmptyState(state: AlertsUiState, onFiltersChanged: (EventFilters) -> Unit) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(when {
            state.filters.active -> "No events match these filters."
            state.connected -> "No events yet."
            else -> "No saved events yet. Waiting for the hub connection."
        })
        if (state.filters.active) TextButton(onClick = { onFiltersChanged(EventFilters()) }) { Text("Clear filters") }
    }
}

@Composable
private fun EventFiltersBar(filters: EventFilters, monitors: List<MonitorStatusDto>, historicMonitorIds: List<String>, onChange: (EventFilters) -> Unit) {
    var showDevices by remember { mutableStateOf(false) }
    var showTypes by remember { mutableStateOf(false) }
    val devices = (monitors.map { it.id } + historicMonitorIds).distinct()
    fun name(id: String) = monitors.find { it.id == id }?.name ?: "Monitor ${id.take(8)}"
    val selectedName = filters.monitorId?.let(::name) ?: "All devices"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { showDevices = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Event device filter: $selectedName" }) {
                Text("$selectedName ▾", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = showDevices, onDismissRequest = { showDevices = false }) {
                DropdownMenuItem(text = { Text("All devices") }, onClick = { showDevices = false; onChange(filters.copy(monitorId = null)) })
                devices.forEach { id -> DropdownMenuItem(text = { Text(name(id)) }, onClick = { showDevices = false; onChange(filters.copy(monitorId = id)) }) }
            }
        }
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { showTypes = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Event type filter: ${filters.category.label}" }) {
                Text("${filters.category.label} ▾", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = showTypes, onDismissRequest = { showTypes = false }) {
                EventCategory.entries.forEach { category -> DropdownMenuItem(text = { Text(category.label) }, onClick = { showTypes = false; onChange(filters.copy(category = category)) }) }
            }
        }
    }
}
