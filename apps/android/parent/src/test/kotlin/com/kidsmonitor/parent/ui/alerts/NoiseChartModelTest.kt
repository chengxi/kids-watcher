package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.parent.data.NOISE_DAY_MS
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.NoiseSample
import org.junit.Assert.*
import org.junit.Test

class NoiseChartModelTest {
    private val now = 2 * NOISE_DAY_MS
    private fun sample(start: Long, end: Long = start + 15_000, peak: Double = -50.0) = NoiseSample(start, end, peak, peak, -38.0)

    @Test
    fun `absence of measurements greys the entire day even with noise alerts`() {
        val alert = EventEntity(1, "m1", EventType.LOUD_NOISE, now - 1000, -15.0, 1500)
        val model = noiseChartModel(NoiseHistory(events = listOf(alert)), now)
        assertEquals(listOf(TimeSpan(now - NOISE_DAY_MS, now)), model.gaps)
        assertEquals(0L, model.coveredMs)
        assertEquals(listOf(alert), model.events)
    }

    @Test
    fun `measured digital silence is data and stopped capture leaves a gap`() {
        val first = sample(now - 60_000, peak = -120.0)
        val last = sample(now - 15_000)
        val model = noiseChartModel(NoiseHistory(listOf(first, last)), now)
        assertEquals(listOf(TimeSpan(first.startMs, first.endMs), TimeSpan(last.startMs, now)), model.coverage)
        assertTrue(model.gaps.contains(TimeSpan(first.endMs, last.startMs)))
        assertEquals(30_000L, model.coveredMs)
    }

    @Test
    fun `every positive gap remains missing rather than filled by smoothing tolerance`() {
        for (gapMs in listOf(1L, 100L, 250L, 251L, 30_000L)) {
            val last = sample(now - 15_000)
            val first = sample(last.startMs - gapMs - 15_000)
            val model = noiseChartModel(NoiseHistory(listOf(first, last)), now)
            assertEquals(listOf(TimeSpan(first.startMs, first.endMs), TimeSpan(last.startMs, last.endMs)), model.coverage)
            assertTrue("a ${gapMs}ms gap must stay visible in the model", model.gaps.contains(TimeSpan(first.endMs, last.startMs)))
            assertEquals(30_000L, model.coveredMs)
            assertEquals(gapMs, last.startMs - first.endMs)
        }
    }

    @Test
    fun `overlapping and nested samples never introduce a gap or expand measured duration`() {
        val first = sample(now - 30_000)
        val nested = sample(now - 25_000, now - 20_000)
        val overlap = sample(now - 20_000, now - 5_000)
        val model = noiseChartModel(NoiseHistory(listOf(overlap, nested, first)), now, 60_000)
        assertEquals(listOf(TimeSpan(now - 30_000, now - 5_000)), model.coverage)
        assertEquals(listOf(TimeSpan(now - 60_000, now - 30_000), TimeSpan(now - 5_000, now)), model.gaps)
        assertEquals(25_000L, model.coveredMs)
    }

    @Test
    fun `continuous and retransmitted intervals merge without duplicate coverage`() {
        val first = sample(now - 30_000)
        val second = sample(now - 15_000)
        val model = noiseChartModel(NoiseHistory(listOf(second, first, first)), now)
        assertEquals(2, model.samples.size)
        assertEquals(listOf(TimeSpan(now - 30_000, now)), model.coverage)
    }

    @Test
    fun `clips window edges and ignores malformed or future measurements`() {
        val crossing = sample(now - NOISE_DAY_MS - 5_000)
        val model = noiseChartModel(NoiseHistory(listOf(crossing, sample(now + 1), sample(now - 40_000, peak = Double.NaN), sample(now - 30_000, now - 10_000))), now)
        assertEquals(listOf(crossing), model.samples)
        assertEquals(10_000L, model.coveredMs)
        assertEquals(now - NOISE_DAY_MS, model.coverage.single().startMs)
    }

    @Test
    fun `threshold history is retained including calibration`() {
        val first = sample(now - 30_000).copy(thresholdDb = -40.0)
        val second = sample(now - 15_000).copy(thresholdDb = null)
        val model = noiseChartModel(NoiseHistory(listOf(first, second)), now)
        assertEquals(listOf(-40.0, null), model.samples.map { it.thresholdDb })
    }
}
