package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.shared.net.MonitorStatusDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorStatusTextTest {
    private val monitor = MonitorStatusDto("m1", "Nursery", true, 1L, 88)

    @Test
    fun `reports battery including empty and full`() {
        for (percent in listOf(0, 88, 100)) {
            assertEquals("Monitor battery: $percent%", monitorBatteryText(monitor.copy(batteryPct = percent), true))
        }
    }

    @Test
    fun `missing stale and invalid readings are unavailable`() {
        for (status in listOf(null, monitor.copy(online = false), monitor.copy(batteryPct = null), monitor.copy(batteryPct = -1))) {
            assertEquals("Monitor battery unavailable", monitorBatteryText(status, true))
        }
        assertEquals("Monitor battery unavailable", monitorBatteryText(monitor, false))
    }
}
