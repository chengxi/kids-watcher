package com.kidsmonitor.shared.net

/** Transport availability, independent of whether the monitor itself is online. */
sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Reconnecting : ConnectionState
    data class WaitingToRetry(val delayMs: Long, val reason: String) : ConnectionState
}
