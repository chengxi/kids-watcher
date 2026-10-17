package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.shared.protocol.NoiseSample
import org.junit.Assert.*
import org.junit.Test

class NoiseTrendTest {
    private fun sample(start: Long, peak: Double) = NoiseSample(start, start + 15_000, peak - 20, peak, -38.0)

    @Test fun `constant peaks have no offset from raw data even when RMS is much lower`() {
        val points = smoothedNoiseTrend((0..40).map { sample(it * 15_000L, -40.0) }).single()
        points.forEach { assertEquals(-40.0, it.levelDb, 0.001) }
    }

    @Test fun `splitting measured intervals does not change their smoothing weight`() {
        val whole = listOf(sample(0, -20.0), sample(15_000, -60.0))
        val split = (0 until 6).map { index ->
            val start = index * 5_000L
            NoiseSample(start, start + 5_000, -80.0, if (index < 3) -20.0 else -60.0, null)
        }
        val expected = smoothedNoiseTrend(whole, windowMs = 60_000, pointIntervalMs = 5_000).single()
        val actual = smoothedNoiseTrend(split, windowMs = 60_000, pointIntervalMs = 5_000).single()
        expected.zip(actual).forEach { (a, b) -> assertEquals(a.levelDb, b.levelDb, 0.00001) }
    }

    @Test fun `centered trend follows a ramp without trailing window lag`() {
        val samples = (0 until 80).map { index -> sample(index * 15_000L, -80.0 + index) }
        val middle = smoothedNoiseTrend(samples, windowMs = 300_000).single().first { it.timeMs == 600_000L }
        assertEquals(-40.5, middle.levelDb, 0.001)
    }

    @Test fun `three hour trend removes alternating capture jitter and uses sparse knots`() {
        val samples = (0 until 720).map { index -> sample(index * 15_000L, if (index % 2 == 0) -40.0 else -60.0) }
        val points = smoothedNoiseTrend(samples, noiseTrendWindowMs(3)).single()
        assertTrue("the curve must not have a knot for every capture sample", points.size < samples.size / 5)
        points.forEach { assertEquals(-50.0, it.levelDb, 0.2) }
        assertEquals(0L, points.first().timeMs)
        assertEquals(10_800_000L, points.last().timeMs)
    }

    @Test fun `longer chart ranges use broader smoothing windows`() {
        assertEquals(listOf(30L, 60L, 180L, 360L), listOf(1, 3, 12, 24).map { noiseTrendWindowMs(it) / 60_000 })
    }

    @Test fun `default view suppresses short bursts but retains sustained changes`() {
        val samples = (0 until 720).map { index ->
            sample(index * 15_000L, when (index) { in 300..307 -> -10.0; in 480..719 -> -35.0; else -> -60.0 })
        }
        val points = smoothedNoiseTrend(samples, noiseTrendWindowMs(3)).single()
        assertTrue("a three hour phone chart should have at most 25 trend points", points.size <= 25)
        val burst = points.filter { it.timeMs in 4_000_000..5_000_000 }
        assertTrue("a two minute spike must not dominate the trend", burst.all { it.levelDb < -52.0 })
        assertEquals(-35.0, points.last().levelDb, 0.001)
    }

    @Test fun `a steady ramp renders without artificial flat spots at knots`() {
        val points = listOf(NoiseTrendPoint(0, -60.0), NoiseTrendPoint(60_000, -50.0), NoiseTrendPoint(180_000, -30.0))
        noiseTrendTangents(points).forEach { assertEquals(10.0 / 60_000, it, 0.00000001) }
    }

    @Test fun `interpolation cannot invent peaks between unequal spaced knots`() {
        val points = listOf(NoiseTrendPoint(0, -60.0), NoiseTrendPoint(100_000, -20.0), NoiseTrendPoint(110_000, -25.0), NoiseTrendPoint(200_000, -80.0))
        val slopes = noiseTrendTangents(points)
        assertEquals(0.0, slopes[1], 0.00000001)
        points.zipWithNext().forEachIndexed { i, (a, b) ->
            val low = minOf(a.levelDb, b.levelDb)
            val high = maxOf(a.levelDb, b.levelDb)
            val thirdTime = (b.timeMs - a.timeMs) / 3.0
            assertTrue(a.levelDb + slopes[i] * thirdTime in low..high)
            assertTrue(b.levelDb - slopes[i + 1] * thirdTime in low..high)
        }
    }

    @Test fun `capture gaps break the curve and do not blend separate captures`() {
        val segments = smoothedNoiseTrend(listOf(sample(0, -20.0), sample(60_000, -80.0)))
        assertEquals(2, segments.size)
        assertEquals(15_000L, segments.first().last().timeMs)
        assertEquals(60_000L, segments.last().first().timeMs)
        segments.first().forEach { assertEquals(-20.0, it.levelDb, 0.001) }
        segments.last().forEach { assertEquals(-80.0, it.levelDb, 0.001) }
    }

    @Test fun `small sample timestamp overlaps do not fragment a continuous curve`() {
        val samples = (0 until 720).map { index -> sample(index * 14_999L, if (index % 2 == 0) -40.0 else -60.0) }
        val points = smoothedNoiseTrend(samples, noiseTrendWindowMs(3)).single()
        assertTrue(points.size <= 25)
        points.forEach { assertEquals(-50.0, it.levelDb, 0.2) }
    }

    @Test fun `tiny timing gaps preserve averaging stability while coverage stays discontinuous`() {
        val samples = (0 until 720).map { index -> sample(index * 15_001L, if (index % 2 == 0) -40.0 else -60.0) }
        val points = smoothedNoiseTrend(samples, noiseTrendWindowMs(3)).single()
        assertTrue(points.size <= 25)
        points.forEach { assertEquals(-50.0, it.levelDb, 0.2) }
        val model = noiseChartModel(com.kidsmonitor.parent.data.NoiseHistory(samples), samples.last().endMs, 3 * 3_600_000L)
        assertTrue("averaging must not turn any timestamp gap into coverage", model.gaps.any { it.endMs - it.startMs == 1L })
        assertTrue(model.coverage.size > 700)
    }

    @Test fun `empty and invalid captures produce no curve`() {
        assertTrue(smoothedNoiseTrend(emptyList()).isEmpty())
        assertTrue(smoothedNoiseTrend(listOf(sample(0, Double.NaN))).isEmpty())
    }
}
