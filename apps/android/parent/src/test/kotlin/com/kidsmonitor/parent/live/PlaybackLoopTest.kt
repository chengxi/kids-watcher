package com.kidsmonitor.parent.live

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLoopTest {

    private fun primed(chunks: Int) = AudioJitterBuffer(16_000, 1, prebufferMs = 20, maxBufferedMs = 10_000).apply {
        repeat(chunks) { offer(ByteArray(640)) }
    }

    @Test
    fun `writes each buffered chunk and waits when the buffer is empty`() {
        var writes = 0
        lateinit var loop: PlaybackLoop
        loop = PlaybackLoop(
            buffer = primed(3),
            write = { writes++; it.size },
            idle = { loop.running = false },
            onWriteError = {},
        )
        loop.run()
        assertEquals(3, writes)
    }

    // Final review I5: a negative write means the track is dead (released,
    // lost its output). Writing again spins on a dead native object; the loop
    // stops and says why.
    @Test
    fun `a failed write stops playback`() {
        var writes = 0
        val errors = mutableListOf<Int>()
        lateinit var loop: PlaybackLoop
        loop = PlaybackLoop(
            buffer = primed(5),
            write = { writes++; -6 },
            idle = { loop.running = false },
            onWriteError = { errors += it },
        )
        loop.run()
        assertEquals(1, writes)
        assertEquals(listOf(-6), errors)
    }
}
