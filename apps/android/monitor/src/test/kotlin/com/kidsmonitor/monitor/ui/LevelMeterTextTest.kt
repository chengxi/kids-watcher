package com.kidsmonitor.monitor.ui

import com.kidsmonitor.monitor.audio.SILENCE_DBFS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LevelMeterTextTest {

    // Before the service has run there is no floor. The meter used to add the
    // threshold to the silence placeholder and print "alerts above -108.0 dB"
    // - a precise-looking number that was never true. Seen on the Pixel 6
    // during M5 Task 9, right after fixing its sibling "threshold +0 dB".
    @Test
    fun `with no floor measured the trigger point is not invented`() {
        val text = triggerText(floorDb = SILENCE_DBFS, thresholdDb = 12.0)
        assertEquals("alerts above: not measured yet", text)
        assertFalse(text.contains("108"))
    }

    // "silent" describes a room. An unmeasured floor is not a quiet room.
    @Test
    fun `with no floor measured the floor does not claim the room is silent`() {
        assertEquals("Room floor not measured yet · threshold +12 dB", floorText(SILENCE_DBFS, 12.0))
    }

    @Test
    fun `with a measured floor both read as numbers`() {
        assertEquals("alerts above -38.1 dBFS", triggerText(floorDb = -50.1, thresholdDb = 12.0))
        assertEquals("Room floor -50.1 dBFS · threshold +12 dB", floorText(-50.1, 12.0))
    }
}
