package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.shared.net.MonitorStatusDto
import com.kidsmonitor.shared.net.ConnectionState

fun connectionBannerText(connection: ConnectionState): String? = when (connection) {
    ConnectionState.Connected -> null
    ConnectionState.Connecting -> "Connecting to the hub… Live alerts will resume when connected."
    ConnectionState.Reconnecting -> "Reconnecting to the hub… Live alerts may be delayed."
    is ConnectionState.WaitingToRetry ->
        "${hubConnectionProblemText(connection.reason)} Retrying automatically in up to ${connection.delayMs / 1000} seconds. Live alerts may be delayed."
    ConnectionState.Stopped -> "Alert connection stopped. Tap Refresh to reconnect."
}

fun monitorAvailabilityText(monitor: MonitorStatusDto, connection: ConnectionState, list: MonitorListState): String = when {
    connection != ConnectionState.Connected -> "Status unknown — waiting for hub connection"
    !list.current && list.refreshing -> "Checking monitor status…"
    !list.current -> "Status unavailable — retrying"
    monitor.online -> "Online"
    else -> "Offline — reported by hub"
}

fun monitorBatteryText(monitor: MonitorStatusDto?, hubConnected: Boolean): String {
    if (!hubConnected || monitor?.online != true) return "Monitor battery unavailable"
    val percent = monitor.batteryPct?.takeIf { it in 0..100 }
    return percent?.let { "Monitor battery: $it%" } ?: "Monitor battery unavailable"
}

fun monitorRefreshText(list: MonitorListState): String = when {
    list.retryDelayMs != null -> "${list.failureText} Retrying automatically in ${list.retryDelayMs / 1000} seconds (attempt ${list.attempt + 1} of 3)…"
    list.refreshing && list.failed -> "Retrying the hub status request… (attempt ${list.attempt} of 3)"
    list.failed -> "${list.failureText ?: "Could not update monitor status."} Status remains unknown; automatic checks will continue."
    else -> "Checking monitor status…"
}

/** These are transport observations, never diagnoses of monitor sleep or camera state. */
fun hubConnectionProblemText(reason: String): String {
    val detail = reason.lowercase()
    return when {
        "401" in detail || "403" in detail -> "The hub refused this phone's authorization."
        "certificate" in detail || "fingerprint" in detail || "ssl" in detail -> "The hub's secure connection could not be verified."
        "timeout" in detail || "timed out" in detail -> "The hub connection timed out. Check this phone's network and the home hub."
        "connect" in detail || "network" in detail || "resolve" in detail || "route" in detail -> "Cannot reach the hub. Check this phone's network and the home hub."
        else -> "The connection to the hub was interrupted."
    }
}
