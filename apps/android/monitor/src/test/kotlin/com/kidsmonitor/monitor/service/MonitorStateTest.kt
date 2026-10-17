package com.kidsmonitor.monitor.service

import com.kidsmonitor.shared.protocol.DEFAULT_MONITOR_CONFIG
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorStateTest {

    // MonitorState is an `object`, so its field initialisers run once per JVM
    // and cannot be re-observed after another test has written to it. The value
    // is therefore pinned at its source instead: IDLE_THRESHOLD_DB is the only
    // place the idle threshold is written, used by both the initialiser and
    // reset(), so it cannot drift from what the screen shows.
    @Test
    fun `the idle threshold is the spec default, not zero`() {
        assertEquals(DEFAULT_MONITOR_CONFIG.thresholdDb, IDLE_THRESHOLD_DB, 0.0001)
        assertEquals(12.0, IDLE_THRESHOLD_DB, 0.0001)
    }

    @Test
    fun `reset restores the same idle threshold the screen starts with`() {
        MonitorState.mutableThresholdDb.value = 99.0
        MonitorState.reset()
        assertEquals(IDLE_THRESHOLD_DB, MonitorState.thresholdDb.value, 0.0001)
    }
}
