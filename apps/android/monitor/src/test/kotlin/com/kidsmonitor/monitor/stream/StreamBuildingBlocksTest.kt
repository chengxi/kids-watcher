package com.kidsmonitor.monitor.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StreamBuildingBlocksTest {

    // The wire says PCM16 little-endian; AudioTrack on the parent assumes it.
    @Test
    fun `PCM samples are written little-endian`() {
        val bytes = PcmFrames.littleEndian(shortArrayOf(1, -2, 0x1234), count = 3)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), bytes)
    }

    // AudioRecord.read can return fewer samples than the buffer holds.
    @Test
    fun `only the samples actually read are framed`() {
        assertEquals(4, PcmFrames.littleEndian(shortArrayOf(1, 2, 3, 4), count = 2).size)
    }

    // A 4x2 image whose planes carry the padding real cameras add: the Y rows
    // are 6 bytes wide for 4 pixels, and U/V are semi-planar views with a pixel
    // stride of 2. Ignoring either stride shears or tints the picture.
    @Test
    fun `YUV_420_888 with row and pixel strides becomes NV21`() {
        val y = ByteBuffer.wrap(byteArrayOf(
            10, 11, 12, 13, 0, 0,
            20, 21, 22, 23,
        ))
        // uvRowStride 4, pixel stride 2: chroma at offsets 0 and 2 of the row.
        val u = ByteBuffer.wrap(byteArrayOf(50, 0, 51))
        val v = ByteBuffer.wrap(byteArrayOf(60, 0, 61))

        val nv21 = Yuv.toNv21(
            width = 4, height = 2,
            y = y, yRowStride = 6,
            u = u, v = v, uvRowStride = 4, uvPixelStride = 2,
        )

        assertArrayEquals(
            byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23, 60, 50, 61, 51), // Y, then V/U interleaved
            nv21,
        )
    }

    @Test
    fun `NV21 conversion leaves the source buffers' positions alone`() {
        val y = ByteBuffer.wrap(ByteArray(8))
        val u = ByteBuffer.wrap(ByteArray(2))
        val v = ByteBuffer.wrap(ByteArray(2))
        Yuv.toNv21(4, 2, y, 4, u, v, 2, 1)
        assertEquals(0, y.position()); assertEquals(0, u.position()); assertEquals(0, v.position())
    }

    @Test
    fun `the throttle admits at most one frame per interval`() {
        val throttle = FrameThrottle(intervalUs = 100_000) // 10 fps
        assertTrue(throttle.admit(0))
        assertFalse(throttle.admit(33_000))
        assertFalse(throttle.admit(66_000))
        assertTrue(throttle.admit(100_000))
        assertFalse(throttle.admit(133_000))
        assertTrue(throttle.admit(250_000))
    }

    // Chosen by the user for M4 (plan, "Decisions taken before planning").
    @Test
    fun `the default video is 640x480 at 10 fps`() {
        assertEquals(VideoSettings(width = 640, height = 480, fps = 10, jpegQuality = 60), VideoSettings.DEFAULT)
    }
}
