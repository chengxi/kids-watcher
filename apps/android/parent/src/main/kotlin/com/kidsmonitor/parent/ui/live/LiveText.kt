package com.kidsmonitor.parent.ui.live

import com.kidsmonitor.parent.ui.alerts.hubConnectionProblemText
import com.kidsmonitor.parent.live.LiveState
import com.kidsmonitor.parent.live.RequestPhase
import com.kidsmonitor.parent.live.MAX_START_ATTEMPTS
import com.kidsmonitor.shared.net.ConnectionState

fun liveStatusText(state: LiveState, hasPicture: Boolean = true): String = when (state) {
    LiveState.Idle -> "Connecting…"
    is LiveState.WaitingForHub -> when (val connection = state.connection) {
        is ConnectionState.WaitingToRetry ->
            "${hubConnectionProblemText(connection.reason)} Reconnecting automatically in up to ${connection.delayMs / 1000} seconds…"
        else -> "Connecting to the hub over the network… Video will be requested when connected."
    }
    is LiveState.Requesting -> {
        val stage = when (state.phase) {
            RequestPhase.HUB -> "Checking the connection to the hub…"
            RequestPhase.MONITOR -> "Hub reached. Waiting for the monitor to respond…"
            RequestPhase.VIDEO -> "Monitor responded. Preparing its video feed…"
        }
        "$stage (attempt ${state.attempt} of $MAX_START_ATTEMPTS)"
    }
    is LiveState.Retrying ->
        "${state.failure.description} Retrying automatically in ${state.delayMs / 1000} seconds (attempt ${state.nextAttempt} of $MAX_START_ATTEMPTS)…"
    is LiveState.Playing -> when {
        state.video == null -> "Live · sound only, the camera is not available"
        !hasPicture -> "Monitor ready. Waiting for the first picture…"
        state.audio == null -> "Live · picture only, the microphone is not available"
        else -> "Live"
    }
    is LiveState.Ended -> state.reason
}

/** Dimensions after applying the stream's clockwise rotation. */
fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees % 180 == 0) width to height else height to width
