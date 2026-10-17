package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.parent.data.NOISE_DAY_MS
import com.kidsmonitor.shared.protocol.NoiseSample
import org.junit.Assert.*
import org.junit.Test

class HomeMeasurementsTest {
    private val now = NOISE_DAY_MS * 2
    private val sample = NoiseSample(now - 15_000, now, -50.0, -40.0, -38.0)

    @Test fun `shorter chart windows clip measurements and exclude older alerts`() {
        val crossing = sample.copy(startMs = now - 3_600_000 - 5000, endMs = now - 3_600_000 + 10_000)
        val old = sample.copy(startMs = now - 4 * 3_600_000, endMs = now - 4 * 3_600_000 + 15_000)
        val model = noiseChartModel(NoiseHistory(listOf(old, crossing, sample)), now, 3_600_000)
        assertEquals(now - 3_600_000, model.fromMs)
        assertEquals(listOf(crossing, sample), model.samples)
        assertEquals(25_000L, model.coveredMs)
        assertEquals(model.fromMs, model.coverage.first().startMs)
    }
}
