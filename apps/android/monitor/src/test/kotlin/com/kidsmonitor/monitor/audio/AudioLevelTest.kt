package com.kidsmonitor.monitor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioLevelTest {

    private fun sine(amplitude: Double, samples: Int = 320, freq: Double = 440.0, rate: Double = 16000.0) =
        ShortArray(samples) { i ->
            (sin(2 * PI * freq * i / rate) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

    @Test
    fun `digital silence reports the floor sentinel rather than negative infinity`() {
        assertEquals(SILENCE_DBFS, AudioLevel.rmsDbfs(ShortArray(320)), 0.001)
    }

    @Test
    fun `full-scale sine is about -3 dBFS`() {
        // RMS of a full-scale sine is 1/sqrt(2) of peak, which is -3.01 dBFS.
        assertEquals(-3.01, AudioLevel.rmsDbfs(sine(1.0)), 0.2)
    }

    @Test
    fun `halving amplitude drops the level by about 6 dB`() {
        assertEquals(6.0, AudioLevel.rmsDbfs(sine(1.0)) - AudioLevel.rmsDbfs(sine(0.5)), 0.3)
    }

    @Test
    fun `a quiet room reads far below a loud one`() {
        assertTrue(AudioLevel.rmsDbfs(sine(0.001)) < AudioLevel.rmsDbfs(sine(0.5)) - 30)
    }

    @Test
    fun `honours the count parameter so a partly filled buffer is not misread`() {
        // AudioRecord reports how many samples it actually read; measuring the
        // whole array would average in stale zeroes and under-report the level.
        val buffer = ShortArray(1000)
        val loud = sine(1.0, samples = 320)
        loud.copyInto(buffer)
        assertEquals(AudioLevel.rmsDbfs(loud), AudioLevel.rmsDbfs(buffer, count = 320), 0.001)
        assertTrue(AudioLevel.rmsDbfs(buffer) < AudioLevel.rmsDbfs(buffer, count = 320) - 3)
    }

    @Test
    fun `an empty read is silence, not a crash`() {
        assertEquals(SILENCE_DBFS, AudioLevel.rmsDbfs(ShortArray(0)), 0.001)
        assertEquals(SILENCE_DBFS, AudioLevel.rmsDbfs(ShortArray(320), count = 0), 0.001)
    }
}
