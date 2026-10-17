package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.VideoFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMediaLimitsTest {

    private val vga = VideoFormat(640, 480, 10.0)

    @Test
    fun `a picture the size the stream advertised is decoded`() {
        assertTrue(LiveMediaLimits.decodable(640, 480, vga))
    }

    // The monitor does not rotate pixels, but a sensor may report either
    // orientation; allow either, with some slack for a camera's closest size.
    @Test
    fun `a picture in the other orientation or somewhat larger is decoded`() {
        assertTrue(LiveMediaLimits.decodable(480, 640, vga))
        assertTrue(LiveMediaLimits.decodable(1280, 960, vga))
    }

    // Final review I1: a few-kilobyte JPEG can claim 30000x30000, which is a
    // 3.6 GB bitmap. The header is read first and a picture like that is refused.
    @Test
    fun `a picture far larger than advertised is refused before decoding`() {
        assertFalse(LiveMediaLimits.decodable(1281, 480, vga))
        assertFalse(LiveMediaLimits.decodable(30_000, 30_000, vga))
    }

    @Test
    fun `without an advertised format, the absolute limit still applies`() {
        assertTrue(LiveMediaLimits.decodable(4096, 4096, null))
        assertFalse(LiveMediaLimits.decodable(4097, 10, null))
    }

    @Test
    fun `a header with no size is refused`() {
        assertFalse(LiveMediaLimits.decodable(0, 480, vga))
        assertFalse(LiveMediaLimits.decodable(-1, -1, null))
    }

    @Test
    fun `plays the formats a monitor produces`() {
        assertTrue(LiveMediaLimits.playable(AudioFormat(16_000, 1)))
        assertTrue(LiveMediaLimits.playable(AudioFormat(8_000, 2)))
        assertTrue(LiveMediaLimits.playable(AudioFormat(48_000, 1)))
    }

    @Test
    fun `refuses a format no speaker plays`() {
        assertFalse(LiveMediaLimits.playable(AudioFormat(7_999, 1)))
        assertFalse(LiveMediaLimits.playable(AudioFormat(48_001, 1)))
        assertFalse(LiveMediaLimits.playable(AudioFormat(16_000, 3)))
    }
}
