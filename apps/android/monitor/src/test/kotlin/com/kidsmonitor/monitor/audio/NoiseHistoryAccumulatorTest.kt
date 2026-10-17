package com.kidsmonitor.monitor.audio

import org.junit.Assert.*
import org.junit.Test

class NoiseHistoryAccumulatorTest {
    @Test
    fun `summarizes RMS energy and retains brief peaks`() {
        val accumulator = NoiseHistoryAccumulator()
        for (t in 20L..15_000L step 20) {
            assertNull(accumulator.onWindow(if (t == 15_000L) -10.0 else -60.0, t, 20, -38.0))
        }
        val sample = accumulator.onWindow(-60.0, 15_020, 20, -38.0)!!
        assertEquals(0L, sample.startMs)
        assertEquals(15_000L, sample.endMs)
        assertEquals(-10.0, sample.peakDb, 0.001)
        assertTrue(sample.levelDb > -40) // averaging decibels would wrongly give about -60
        assertEquals(-38.0, sample.thresholdDb!!, 0.001)
    }

    @Test
    fun `capture pause and clock rollback cannot turn missing time into silence`() {
        val accumulator = NoiseHistoryAccumulator()
        accumulator.onWindow(-120.0, 1000, 20, -38.0)
        val beforeGap = accumulator.onWindow(-50.0, 20_000, 20, -38.0)!!
        assertEquals(980L, beforeGap.startMs)
        assertEquals(1000L, beforeGap.endMs)
        val beforeClockChange = accumulator.onWindow(-50.0, 10_000, 20, -38.0)!!
        assertEquals(19_980L, beforeClockChange.startMs)
        assertEquals(20_000L, beforeClockChange.endMs)
    }

    @Test
    fun `calibration keeps measurements but has no active threshold`() {
        val accumulator = NoiseHistoryAccumulator()
        accumulator.onWindow(-50.0, 1000, 20, null)
        val calibration = accumulator.onWindow(-50.0, 1020, 20, -38.0)!!
        assertNull(calibration.thresholdDb)
        assertEquals(-50.0, calibration.levelDb, 0.001)
    }
}
