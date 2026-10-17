package com.kidsmonitor.parent.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioJitterBufferTest {

    // 16 kHz mono PCM16 is 32 bytes per millisecond; one 20 ms frame is 640 bytes.
    private fun chunk(tag: Int) = ByteArray(640) { tag.toByte() }
    private fun buffer() = AudioJitterBuffer(sampleRate = 16_000, channels = 1, prebufferMs = 120, maxBufferedMs = 400)

    @Test
    fun `plays nothing until the prebuffer is full`() {
        val b = buffer()
        repeat(5) { b.offer(chunk(it)) } // 100 ms
        assertNull(b.poll())
        b.offer(chunk(5)) // 120 ms
        assertArrayEquals(chunk(0), b.poll())
    }

    @Test
    fun `plays chunks in arrival order`() {
        val b = buffer()
        repeat(6) { b.offer(chunk(it)) }
        repeat(6) { assertArrayEquals(chunk(it), b.poll()) }
    }

    // A parent watching live wants now, not a growing delay: past the cap the
    // oldest audio is discarded so latency cannot creep up over a long session.
    @Test
    fun `drops the oldest audio beyond the latency cap`() {
        val b = buffer()
        repeat(30) { b.offer(chunk(it)) } // 600 ms offered
        assertTrue("buffered ${b.bufferedMs()} ms", b.bufferedMs() <= 400)
        assertEquals(10L * 640, b.droppedBytes)
        assertArrayEquals(chunk(10), b.poll())
    }

    // After running dry, refill the prebuffer before playing again, so a
    // network hiccup gives one short gap rather than a stutter.
    @Test
    fun `re-primes after an underrun`() {
        val b = buffer()
        repeat(6) { b.offer(chunk(it)) }
        repeat(6) { b.poll() }
        assertNull(b.poll())
        b.offer(chunk(99))
        assertNull(b.poll())
    }
}
