package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.NoiseHistory
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.NoiseSample
import org.junit.Assert.*
import org.junit.Test

class NoiseChartAxisTest {
    private fun model(peaks: List<Double>, alert: Double? = null) = noiseChartModel(
        NoiseHistory(peaks.mapIndexed { i, db -> NoiseSample(i * 15_000L, (i + 1) * 15_000L, db, db, -38.0) },
            alert?.let { listOf(EventEntity(1, "m", EventType.LOUD_NOISE, 10_000, it, 1500)) } ?: emptyList()),
        peaks.size * 15_000L)

    @Test fun `quiet room uses a narrow scale instead of the entire digital range`() {
        val axis = noiseChartAxis(model(listOf(-47.0, -45.0, -41.0)), emptyList(), -38.0)
        assertTrue(axis.topDb - axis.bottomDb in 20.0..30.0)
        assertTrue(-47.0 > axis.bottomDb && -38.0 < axis.topDb)
    }

    @Test fun `loud peaks and alert markers remain visible after automatic scaling`() {
        val axis = noiseChartAxis(model(listOf(-55.0, -50.0, -5.0), -2.0), emptyList(), -38.0)
        assertTrue(axis.bottomDb < -55)
        assertTrue(axis.topDb >= -2)
    }

    @Test fun `digital silence and full scale both have a valid readable axis`() {
        listOf(-120.0, 0.0).forEach { value ->
            val axis = noiseChartAxis(model(listOf(value)), emptyList(), null)
            assertTrue(value in axis.bottomDb..axis.topDb)
            assertTrue(axis.topDb - axis.bottomDb >= 20)
            assertTrue(axis.bottomDb >= -120 && axis.topDb <= 0)
        }
    }

    @Test fun `no measurements has a stable scale and positive alert reference is included`() {
        assertEquals(NoiseChartAxis(-60.0, 0.0), noiseChartAxis(model(emptyList()), emptyList(), null))
        val axis = noiseChartAxis(model(listOf(-40.0)), emptyList(), 10.0)
        assertTrue(10.0 in axis.bottomDb..axis.topDb)
    }
}
