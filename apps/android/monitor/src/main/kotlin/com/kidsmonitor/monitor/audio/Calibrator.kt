package com.kidsmonitor.monitor.audio

data class CalibrationOutcome(
    val noiseFloorDb: Double,
    val spreadDb: Double,
    val sampledMs: Int,
)

/**
 * Samples the room for a fixed window and reports its resting level.
 *
 * Uses the median rather than the mean: a door closing during calibration would
 * drag a mean upwards and leave the monitor deaf for the rest of the night.
 * Spread is the interquartile range, which says how variable the room is
 * without being moved by the same outliers.
 *
 * Calibration exists because the adaptive floor converges to whatever the room
 * was doing while it converged (spec §9.1). A monitor first started during a
 * noisy afternoon learns the wrong baseline and stays deaf until something
 * resets it; this is that reset.
 */
class Calibrator(private val windowMs: Int) {

    private val samples = mutableListOf<Double>()
    private var startedAtMs: Long? = null
    private var reported = false

    val isRunning: Boolean get() = startedAtMs != null && !reported

    fun start(nowMs: Long) {
        // Clear rather than accumulate: an operator restarting calibration is
        // saying the previous attempt was not representative.
        samples.clear()
        startedAtMs = nowMs
        reported = false
    }

    fun onWindow(dbfs: Double, nowMs: Long): CalibrationOutcome? {
        val started = startedAtMs ?: return null
        if (reported) return null

        samples.add(dbfs)
        val elapsed = (nowMs - started).toInt()
        if (elapsed < windowMs) return null

        reported = true
        val sorted = samples.sorted()
        return CalibrationOutcome(
            noiseFloorDb = percentile(sorted, 0.50),
            spreadDb = percentile(sorted, 0.75) - percentile(sorted, 0.25),
            sampledMs = elapsed,
        )
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return SILENCE_DBFS
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
