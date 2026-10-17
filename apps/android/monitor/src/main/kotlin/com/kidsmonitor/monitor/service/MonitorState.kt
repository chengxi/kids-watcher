package com.kidsmonitor.monitor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kidsmonitor.monitor.audio.SILENCE_DBFS
import com.kidsmonitor.shared.protocol.DEFAULT_MONITOR_CONFIG

/**
 * What the UI shows about the running service.
 *
 * The level readout matters more than it looks: a threshold that is wrong for a
 * room is otherwise invisible, and the defaults are guesses until the app has
 * been lived with.
 */
/**
 * The threshold the screen shows before any config has been pushed.
 *
 * The spec default, not 0.0: the status screen renders this straight into
 * "threshold +%.0f dB" while the service is stopped, and the detector would
 * apply DEFAULT_MONITOR_CONFIG until the hub says otherwise. Zero is a number
 * that is never true, and it reads as a monitor that alerts on everything. It
 * showed "+0 dB" on a real Pixel 6 during M5 Task 9.
 *
 * Declared once so the initial value and [MonitorState.reset] cannot drift.
 */
internal val IDLE_THRESHOLD_DB = DEFAULT_MONITOR_CONFIG.thresholdDb

object MonitorState {
    internal val mutableConnected = MutableStateFlow(false)
    internal val mutableListening = MutableStateFlow(false)
    internal val mutableLevelDb = MutableStateFlow(SILENCE_DBFS)
    internal val mutableFloorDb = MutableStateFlow(SILENCE_DBFS)
    internal val mutableThresholdDb = MutableStateFlow(IDLE_THRESHOLD_DB)
    internal val mutableLastEventMs = MutableStateFlow<Long?>(null)
    internal val mutableCalibrating = MutableStateFlow(false)
    internal val mutableWatching = MutableStateFlow(false)
    internal val mutableParentTalking = MutableStateFlow(false)
    val parentTalking: StateFlow<Boolean> = mutableParentTalking.asStateFlow()

    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    val listening: StateFlow<Boolean> = mutableListening.asStateFlow()
    val levelDb: StateFlow<Double> = mutableLevelDb.asStateFlow()
    val floorDb: StateFlow<Double> = mutableFloorDb.asStateFlow()
    val thresholdDb: StateFlow<Double> = mutableThresholdDb.asStateFlow()
    val lastEventMs: StateFlow<Long?> = mutableLastEventMs.asStateFlow()
    val calibrating: StateFlow<Boolean> = mutableCalibrating.asStateFlow()

    /** A parent is watching live: the camera is on (spec §4.4). */
    val watching: StateFlow<Boolean> = mutableWatching.asStateFlow()

    /** Test seam: this is a singleton, so state leaks between tests otherwise. */
    internal fun reset() {
        mutableConnected.value = false
        mutableListening.value = false
        mutableLevelDb.value = SILENCE_DBFS
        mutableFloorDb.value = SILENCE_DBFS
        mutableThresholdDb.value = IDLE_THRESHOLD_DB
        mutableLastEventMs.value = null
        mutableCalibrating.value = false
        mutableWatching.value = false
        mutableParentTalking.value = false
    }
}
