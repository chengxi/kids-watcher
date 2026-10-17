package com.kidsmonitor.monitor.audio

import com.kidsmonitor.shared.protocol.MonitorConfig
import kotlin.math.exp
import kotlin.math.max

data class Detection(val peakDb: Double, val durationMs: Int)

/**
 * Turns a stream of 20 ms level readings into noise events.
 *
 * Pure Kotlin on purpose: the thresholds here are the part most likely to be
 * wrong, and testing them against synthetic levels is far more informative than
 * making noises at a phone.
 */
class NoiseDetector(
    private var config: MonitorConfig,
    private val floorEmaWindowMs: Int = 60_000,
) {
    var noiseFloorDb: Double = config.noiseFloorDb
        private set

    private var aboveSinceMs: Long? = null
    private var peakDb: Double = SILENCE_DBFS
    private var lastEventMs: Long? = null

    fun updateConfig(config: MonitorConfig) {
        this.config = config
        noiseFloorDb = config.noiseFloorDb
        reset()
    }

    /** Adopt a calibrated floor without disturbing the rest of the state. */
    fun setFloor(dbfs: Double) {
        noiseFloorDb = dbfs
    }

    /**
     * Feed one window. Returns a [Detection] on the window where the sound has
     * been above threshold for `sustainMs`, or null.
     */
    fun onWindow(dbfs: Double, nowMs: Long): Detection? {
        val threshold = noiseFloorDb + config.thresholdDb

        if (dbfs <= threshold) {
            // Only track the room's floor while nothing is happening. Updating
            // it during a cry would drag the threshold up until detection
            // stopped -- the monitor would go deaf exactly when it mattered.
            updateFloor(dbfs)
            reset()
            return null
        }

        peakDb = max(peakDb, dbfs)
        val since = aboveSinceMs ?: nowMs.also { aboveSinceMs = it }
        val elapsed = (nowMs - since).toInt()
        if (elapsed < config.sustainMs) return null

        val last = lastEventMs
        if (last != null && nowMs - last < config.cooldownS * 1000L) {
            // Still inside the cooldown: one episode is one notification.
            return null
        }

        val detection = Detection(peakDb = peakDb, durationMs = elapsed)
        lastEventMs = nowMs
        reset()
        return detection
    }

    private fun updateFloor(dbfs: Double) {
        // Exponential moving average over floorEmaWindowMs, in 20 ms steps.
        val alpha = 1.0 - exp(-WINDOW_MS.toDouble() / floorEmaWindowMs)
        noiseFloorDb += alpha * (dbfs - noiseFloorDb)
    }

    private fun reset() {
        aboveSinceMs = null
        peakDb = SILENCE_DBFS
    }

    private companion object {
        const val WINDOW_MS = 20
    }
}
