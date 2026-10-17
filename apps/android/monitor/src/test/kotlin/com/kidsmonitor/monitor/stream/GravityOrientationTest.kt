package com.kidsmonitor.monitor.stream

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class GravityOrientationTest {
    @Test
    fun `all four mounts map to CameraX surface rotation`() {
        val tracker = OrientationTracker()
        assertEquals(Surface.ROTATION_0, tracker.update(0))
        assertEquals(Surface.ROTATION_270, tracker.update(90))
        assertEquals(Surface.ROTATION_180, tracker.update(180))
        assertEquals(Surface.ROTATION_90, tracker.update(270))
        assertEquals(Surface.ROTATION_0, tracker.update(359))
    }

    @Test
    fun `unknown gravity keeps the last usable orientation`() {
        val tracker = OrientationTracker()
        assertEquals(Surface.ROTATION_0, tracker.update(-1))
        tracker.update(180)
        assertEquals(Surface.ROTATION_180, tracker.update(-1))
    }

    @Test
    fun `a mount near a diagonal does not flip back and forth`() {
        val tracker = OrientationTracker()
        for (degrees in listOf(44, 46, 59)) assertEquals(Surface.ROTATION_0, tracker.update(degrees))
        assertEquals(Surface.ROTATION_270, tracker.update(60))
        for (degrees in listOf(46, 44, 31)) assertEquals(Surface.ROTATION_270, tracker.update(degrees))
        assertEquals(Surface.ROTATION_0, tracker.update(30))
        assertEquals(Surface.ROTATION_0, tracker.update(301))
        assertEquals(Surface.ROTATION_90, tracker.update(300))
    }
}
