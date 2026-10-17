package com.kidsmonitor.monitor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {

    private fun run(c: Calibrator, dbfs: (Int) -> Double, ms: Int): CalibrationOutcome? {
        var now = 0L
        var outcome: CalibrationOutcome? = null
        repeat(ms / 20) { i ->
            val result = c.onWindow(dbfs(i), now)
            if (result != null && outcome == null) outcome = result
            now += 20
        }
        return outcome
    }

    @Test
    fun `does nothing until started`() {
        val c = Calibrator(windowMs = 1000)
        assertFalse(c.isRunning)
        assertNull(c.onWindow(-50.0, 0))
    }

    @Test
    fun `reports the median level of a steady room`() {
        val c = Calibrator(windowMs = 1000).apply { start(0) }
        val outcome = run(c, { -52.0 }, 1200)
        assertNotNull(outcome)
        assertEquals(-52.0, outcome!!.noiseFloorDb, 0.5)
    }

    @Test
    fun `reports how long it actually sampled`() {
        val c = Calibrator(windowMs = 1000).apply { start(0) }
        assertTrue(run(c, { -52.0 }, 1200)!!.sampledMs >= 1000)
    }

    @Test
    fun `a brief bang does not drag the floor up`() {
        // Someone shutting a door during calibration must not poison the
        // baseline for the rest of the night. This is why it is a median.
        val c = Calibrator(windowMs = 2000).apply { start(0) }
        val outcome = run(c, { i -> if (i in 10..14) -10.0 else -52.0 }, 2200)
        assertEquals(-52.0, outcome!!.noiseFloorDb, 1.0)
    }

    @Test
    fun `a mean would have been dragged up by that bang`() {
        // Guards the choice, not just the behaviour: if someone swaps the
        // median for a mean, this documents what it costs.
        val samples = (0 until 100).map { i -> if (i in 10..14) -10.0 else -52.0 }
        val mean = samples.average()
        assertTrue("a mean would sit at $mean", mean > -50.0)
    }

    @Test
    fun `spread reflects how variable the room is`() {
        val steady = Calibrator(windowMs = 2000).apply { start(0) }
        val steadySpread = run(steady, { -52.0 }, 2200)!!.spreadDb

        val variable = Calibrator(windowMs = 2000).apply { start(0) }
        val variableSpread = run(variable, { i -> if (i % 2 == 0) -60.0 else -44.0 }, 2200)!!.spreadDb

        assertTrue("steady=$steadySpread variable=$variableSpread", variableSpread > steadySpread + 5)
    }

    @Test
    fun `stops running once it has reported`() {
        val c = Calibrator(windowMs = 1000).apply { start(0) }
        run(c, { -52.0 }, 1200)
        assertFalse(c.isRunning)
    }

    @Test
    fun `reports only once per run`() {
        val c = Calibrator(windowMs = 1000).apply { start(0) }
        var reports = 0
        var now = 0L
        repeat(200) {
            if (c.onWindow(-52.0, now) != null) reports++
            now += 20
        }
        assertEquals(1, reports)
    }

    @Test
    fun `a second start discards the first run's samples`() {
        // The operator retries because the room was not actually quiet the
        // first time. Carrying those samples over would defeat the retry.
        val c = Calibrator(windowMs = 1000)
        c.start(0)
        repeat(25) { c.onWindow(-20.0, it * 20L) } // noisy, incomplete run
        c.start(0)
        val outcome = run(c, { -55.0 }, 1200)
        assertEquals(-55.0, outcome!!.noiseFloorDb, 0.5)
    }
}
