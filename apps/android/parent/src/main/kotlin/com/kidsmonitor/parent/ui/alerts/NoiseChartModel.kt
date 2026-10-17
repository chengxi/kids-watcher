package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.NOISE_DAY_MS
import com.kidsmonitor.shared.protocol.NoiseSample
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor

data class TimeSpan(val startMs: Long, val endMs: Long)
data class NoiseChartModel(
    val fromMs: Long,
    val toMs: Long,
    val samples: List<NoiseSample>,
    val coverage: List<TimeSpan>,
    val gaps: List<TimeSpan>,
    val events: List<EventEntity>,
) {
    val coveredMs: Long get() = coverage.sumOf { it.endMs - it.startMs }
}

/** Coverage comes only from measurements, never from online flags or the absence of alerts. */
fun noiseChartModel(history: com.kidsmonitor.parent.data.NoiseHistory, nowMs: Long, windowMs: Long = NOISE_DAY_MS): NoiseChartModel {
    require(windowMs in 1..NOISE_DAY_MS)
    val from = nowMs - windowMs
    val samples = history.samples.filter {
        it.startMs < nowMs && it.endMs > from && it.endMs > it.startMs &&
            it.endMs - it.startMs <= 15_000 && it.levelDb.isFinite() && it.peakDb.isFinite() &&
            it.levelDb in -120.0..0.0 && it.peakDb in -120.0..0.0
    }.distinctBy { it.startMs }.sortedBy { it.startMs }
    val coverage = mutableListOf<TimeSpan>()
    for (sample in samples) {
        val interval = TimeSpan(maxOf(from, sample.startMs), minOf(nowMs, sample.endMs))
        val previous = coverage.lastOrNull()
        // Coverage is the exact union of measured intervals. Even small
        // timestamp gaps stay missing; smoothing tolerance cannot fill them.
        if (previous != null && interval.startMs <= previous.endMs) {
            coverage[coverage.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, interval.endMs))
        } else coverage += interval
    }
    val gaps = mutableListOf<TimeSpan>()
    var cursor = from
    for (span in coverage) {
        if (span.startMs > cursor) gaps += TimeSpan(cursor, span.startMs)
        cursor = span.endMs
    }
    if (cursor < nowMs) gaps += TimeSpan(cursor, nowMs)
    return NoiseChartModel(from, nowMs, samples, coverage, gaps,
        history.events.filter { it.tsMs in from..nowMs }.distinctBy { it.id }.sortedBy { it.tsMs })
}

data class NoiseTrendPoint(val timeMs: Long, val levelDb: Double)

/** Shape-preserving tangents avoid flattening the curve at every plotted knot. */
internal fun noiseTrendTangents(points: List<NoiseTrendPoint>): List<Double> {
    if (points.size < 2) return List(points.size) { 0.0 }
    val durations = points.zipWithNext().map { (a, b) -> (b.timeMs - a.timeMs).toDouble() }
    require(durations.all { it > 0 })
    val slopes = points.zipWithNext().mapIndexed { i, (a, b) -> (b.levelDb - a.levelDb) / durations[i] }
    return points.indices.map { i ->
        when (i) {
            0 -> slopes.first()
            points.lastIndex -> slopes.last()
            else -> {
                val left = slopes[i - 1]
                val right = slopes[i]
                if (left * right <= 0) 0.0 else {
                    val w1 = 2 * durations[i] + durations[i - 1]
                    val w2 = durations[i] + 2 * durations[i - 1]
                    (w1 + w2) / (w1 / left + w2 / right)
                }
            }
        }
    }
}

internal fun noiseTrendWindowMs(rangeHours: Int): Long = when (rangeHours) {
    1 -> 30 * 60_000L
    3 -> 60 * 60_000L
    12 -> 180 * 60_000L
    else -> 360 * 60_000L
}

/**
 * A centered, cosine-weighted trend of the same peaks drawn by the raw line.
 * Integrate weights over each measured interval, tapering smoothly to zero at
 * the window edges so a sample entering/leaving cannot make a sharp step.
 * This is a visual average in dBFS, not an RMS/energy measurement. Sparse knots
 * keep the curve readable at phone width.
 * Gaps over 250 ms start separate averages; tiny capture scheduling gaps have
 * no weight but keep the averaging window stable. Rendering clips the curve
 * to exact measured coverage, so no positive gap is drawn over.
 */
internal fun smoothedNoiseTrend(
    samples: List<NoiseSample>,
    windowMs: Long = 30 * 60_000L,
    pointIntervalMs: Long = maxOf(15_000L, windowMs / 4),
): List<List<NoiseTrendPoint>> {
    require(windowMs > 0)
    require(pointIntervalMs > 0)
    val segments = mutableListOf<MutableList<NoiseSample>>()
    for (sample in samples.sortedBy { it.startMs }) {
        if (!sample.peakDb.isFinite() || sample.peakDb !in -120.0..0.0 || sample.endMs <= sample.startMs) continue
        val end = segments.lastOrNull()?.lastOrNull()?.endMs
        if (end == null || sample.startMs > end + 250) {
            segments += mutableListOf<NoiseSample>()
        }
        // Overlapping timestamps are measured coverage, not an outage. Clip
        // their overlap so elapsed time is not given weight twice.
        val clippedStart = maxOf(sample.startMs, segments.last().lastOrNull()?.endMs ?: sample.startMs)
        if (sample.endMs > clippedStart) segments.last() += sample.copy(startMs = clippedStart)
    }
    return segments.map { segment ->
        val start = segment.first().startMs
        val end = segment.last().endMs
        val times = mutableListOf(start)
        var time = start + pointIntervalMs
        while (time < end) {
            times += time
            time += pointIntervalMs
        }
        times += end
        times.mapNotNull { at ->
            val from = at - windowMs / 2
            val to = at + (windowMs - windowMs / 2)
            var weightedDb = 0.0
            var measuredWeight = 0.0
            // Integral of (1 + cos(2π * offset/window)) / 2.
            fun integral(time: Long): Double {
                val offset = (time - at).toDouble()
                return offset / 2 + windowMs / (4 * PI) * sin(2 * PI * offset / windowMs)
            }
            for (part in segment) {
                val left = maxOf(from, part.startMs)
                val right = minOf(to, part.endMs)
                if (right <= left) continue
                val weight = (integral(right) - integral(left)).coerceAtLeast(0.0)
                weightedDb += part.peakDb * weight
                measuredWeight += weight
            }
            if (measuredWeight == 0.0) null else NoiseTrendPoint(at, weightedDb / measuredWeight)
        }
    }
}

data class NoiseChartAxis(val bottomDb: Double, val topDb: Double) {
    val ticks: List<Double> get() = (0..4).map { topDb - (topDb - bottomDb) * it / 4 }
}

/** Fit all measured peaks, trend points, alerts and the latest reference; keep useful headroom. */
internal fun noiseChartAxis(model: NoiseChartModel, trend: List<List<NoiseTrendPoint>>, alertLevel: Double?): NoiseChartAxis {
    val values = model.samples.map { it.peakDb } + trend.flatten().map { it.levelDb } +
        model.events.mapNotNull { it.peakDb?.takeIf { n -> n.isFinite() && n in -120.0..0.0 } } + listOfNotNull(alertLevel)
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return NoiseChartAxis(-60.0, 0.0)
    var low = finite.min() - 3.0
    var high = finite.max() + 3.0
    if (high - low < 20) {
        val middle = (high + low) / 2
        low = middle - 10
        high = middle + 10
    }
    low = floor(low / 5) * 5
    high = ceil(high / 5) * 5
    if (low < -120) { high += -120 - low; low = -120.0 }
    // dBFS cannot exceed 0; positive alert settings still need their reference visible.
    val ceiling = maxOf(0.0, alertLevel ?: 0.0)
    if (high > ceiling) { low -= high - ceiling; high = ceiling }
    return NoiseChartAxis(maxOf(-120.0, low), high)
}
