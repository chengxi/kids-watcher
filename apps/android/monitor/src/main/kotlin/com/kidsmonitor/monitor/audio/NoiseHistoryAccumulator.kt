package com.kidsmonitor.monitor.audio

import com.kidsmonitor.shared.protocol.NOISE_INTERVAL_MS
import com.kidsmonitor.shared.protocol.NoiseSample
import kotlin.math.log10
import kotlin.math.pow

/** Summarizes observed windows. Never fills a paused capture with invented silence. */
class NoiseHistoryAccumulator {
    private var startMs: Long? = null
    private var endMs = 0L
    private var energy = 0.0
    private var durationMs = 0L
    private var peakDb = SILENCE_DBFS
    private var threshold: Double? = null

    fun onWindow(levelDb: Double, atMs: Long, windowMs: Int, thresholdDb: Double?): NoiseSample? {
        if (!levelDb.isFinite() || windowMs <= 0) return null
        val start = atMs - windowMs
        val previous = startMs
        val flush = previous != null && (start - endMs > 250 || start < endMs - 250 ||
            atMs - previous > NOISE_INTERVAL_MS || (threshold == null) != (thresholdDb == null))
        val completed = if (flush) snapshot() else null
        if (flush) reset()
        if (startMs == null) startMs = start.coerceAtLeast(0)
        val measured = levelDb.coerceIn(SILENCE_DBFS, 0.0)
        energy += 10.0.pow(measured / 10.0) * windowMs
        durationMs += windowMs
        peakDb = maxOf(peakDb, measured)
        endMs = atMs
        threshold = thresholdDb // actual adaptive detection threshold at the end of the interval
        return completed
    }

    private fun snapshot(): NoiseSample? {
        val start = startMs ?: return null
        if (endMs <= start || endMs - start > NOISE_INTERVAL_MS) return null
        return NoiseSample(start, endMs, (10 * log10(energy / durationMs)).coerceIn(SILENCE_DBFS, 0.0), peakDb, threshold)
    }

    private fun reset() {
        startMs = null
        energy = 0.0
        durationMs = 0
        peakDb = SILENCE_DBFS
    }
}
