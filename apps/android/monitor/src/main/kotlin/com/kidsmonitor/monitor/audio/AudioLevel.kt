package com.kidsmonitor.monitor.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Reported for digital silence, where the true value is negative infinity. */
const val SILENCE_DBFS = -120.0

object AudioLevel {

    private const val FULL_SCALE = 32768.0

    /**
     * RMS level of PCM16 samples, in dBFS (0 = full scale, negative below).
     *
     * [count] matters: `AudioRecord.read` reports how many samples it actually
     * produced, and measuring the whole buffer would average in stale zeroes
     * and under-report the level.
     */
    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Double {
        val n = count.coerceAtMost(samples.size)
        if (n <= 0) return SILENCE_DBFS

        var sumSquares = 0.0
        for (i in 0 until n) {
            val normalized = samples[i] / FULL_SCALE
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / n)
        if (rms <= 0.0) return SILENCE_DBFS
        return (20.0 * log10(rms)).coerceAtLeast(SILENCE_DBFS)
    }
}
