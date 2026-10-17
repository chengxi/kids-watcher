package com.kidsmonitor.monitor.audio

import com.kidsmonitor.shared.protocol.DEFAULT_MONITOR_CONFIG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseDetectorTest {

    private val quiet = -50.0
    private val loud = -30.0 // 20 dB above the floor; default threshold is 12

    private fun detector(
        thresholdDb: Double = 12.0,
        sustainMs: Int = 1500,
        cooldownS: Int = 60,
        floorDb: Double = -50.0,
    ) = NoiseDetector(
        DEFAULT_MONITOR_CONFIG.copy(
            thresholdDb = thresholdDb, sustainMs = sustainMs,
            cooldownS = cooldownS, noiseFloorDb = floorDb,
        ),
    )

    /** Feeds 20 ms windows and returns the first detection, if any. */
    private fun feed(d: NoiseDetector, dbfs: Double, ms: Int, startMs: Long = 0): Detection? {
        var now = startMs
        var first: Detection? = null
        repeat(ms / 20) {
            val hit = d.onWindow(dbfs, now)
            if (hit != null && first == null) first = hit
            now += 20
        }
        return first
    }

    @Test
    fun `quiet audio never fires`() {
        assertNull(feed(detector(), quiet, 10_000))
    }

    @Test
    fun `a loud burst shorter than sustainMs does not fire`() {
        // A door closing is loud and brief. Firing on it would make the monitor
        // cry wolf all evening.
        assertNull(feed(detector(sustainMs = 1500), loud, 1000))
    }

    @Test
    fun `sustained loud audio fires after sustainMs`() {
        val detection = feed(detector(sustainMs = 1500), loud, 2000)
        assertNotNull(detection)
        assertEquals(-30.0, detection!!.peakDb, 0.001)
        assertTrue("duration should be at least sustainMs", detection.durationMs >= 1500)
    }

    @Test
    fun `audio just below the threshold does not fire`() {
        // floor -50 + threshold 12 = -38. -38.5 must stay silent.
        assertNull(feed(detector(), -38.5, 5000))
    }

    @Test
    fun `audio just above the threshold fires`() {
        assertNotNull(feed(detector(), -37.5, 5000))
    }

    @Test
    fun `a brief dip below the threshold resets the sustain timer`() {
        val d = detector(sustainMs = 1500)
        feed(d, loud, 1000)
        d.onWindow(quiet, 1000)
        assertNull(feed(d, loud, 1000, startMs = 1020))
    }

    @Test
    fun `cooldown suppresses a second event`() {
        // One crying episode should be one notification, not forty.
        val d = detector(sustainMs = 1000, cooldownS = 60)
        assertNotNull(feed(d, loud, 1500))
        assertNull(feed(d, loud, 1500, startMs = 5_000))
    }

    @Test
    fun `a new event fires once the cooldown expires`() {
        val d = detector(sustainMs = 1000, cooldownS = 60)
        feed(d, loud, 1500)
        assertNotNull(feed(d, loud, 1500, startMs = 61_000))
    }

    @Test
    fun `the peak reported is the loudest window, not the first`() {
        val d = detector(sustainMs = 1000)
        var now = 0L
        repeat(40) { d.onWindow(-30.0, now); now += 20 }
        repeat(10) { d.onWindow(-18.0, now); now += 20 }
        val detection = d.onWindow(-30.0, now)
        assertEquals(-18.0, detection!!.peakDb, 0.001)
    }

    @Test
    fun `the floor tracks slow drift while below threshold`() {
        // Traffic picking up, or a fan on low: quieter than the trigger point,
        // so the floor should absorb it rather than sitting at the old value.
        // -60 floor + 12 threshold = -48, so -55 is ambient.
        val d = detector(floorDb = -60.0)
        repeat(6000) { d.onWindow(-55.0, it * 20L) }
        assertTrue("floor should have risen toward -55, was ${d.noiseFloorDb}", d.noiseFloorDb > -58.0)
    }

    @Test
    fun `a new noise source ABOVE threshold does not silently become the new floor`() {
        // Deliberate, and a sharp edge worth knowing about. The floor only
        // adapts below the trigger point (spec 9), so a fridge that starts up
        // 15 dB above the old floor keeps producing events -- one per cooldown
        // -- rather than being quietly absorbed and ignored.
        //
        // That is the intended trade: a genuinely louder room SHOULD alert
        // rather than teach the monitor to ignore it. The remedy is
        // recalibration (spec 9.1), which is why that is a first-class,
        // user-triggered operation.
        val d = detector(sustainMs = 500, cooldownS = 60, floorDb = -60.0)
        var now = 0L
        var events = 0
        repeat(30_000) { // 10 minutes of steady -45 dB hum
            if (d.onWindow(-45.0, now) != null) events++
            now += 20
        }
        assertTrue("expected repeated events, got $events", events >= 5)
        assertEquals("the floor must not have absorbed it", -60.0, d.noiseFloorDb, 0.001)
    }

    @Test
    fun `the floor does not chase the noise while triggered`() {
        // Otherwise a long cry raises the floor until detection stops -- the
        // monitor goes deaf exactly when it matters most.
        val d = detector(sustainMs = 500, floorDb = -50.0)
        feed(d, loud, 10_000)
        assertTrue("floor drifted to ${d.noiseFloorDb}", d.noiseFloorDb < -45.0)
    }

    @Test
    fun `a config push takes effect immediately`() {
        val d = detector(thresholdDb = 30.0)
        assertNull(feed(d, loud, 3000))
        d.updateConfig(
            DEFAULT_MONITOR_CONFIG.copy(thresholdDb = 5.0, sustainMs = 500, noiseFloorDb = -50.0),
        )
        assertNotNull(feed(d, loud, 3000, startMs = 100_000))
    }

    @Test
    fun `setFloor replaces the floor after calibration`() {
        val d = detector(floorDb = -50.0)
        d.setFloor(-70.0)
        assertEquals(-70.0, d.noiseFloorDb, 0.001)
    }
}
