package com.kidsmonitor.shared.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaFramesTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // The golden bytes are what binary.ts produces for the same frame. If this
    // test and the conformance script disagree, the two languages have drifted.
    @Test
    fun `encodes the 12-byte big-endian header exactly as the hub does`() {
        val frame = MediaFrame(MediaKind.JPEG, streamId = 0x0102, timestampUs = 1_234_567, payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
        assertEquals("4b01010200000000" + "0012d687" + "dead", hex(MediaFrames.encode(frame)))
    }

    @Test
    fun `round-trips every field`() {
        val frame = MediaFrame(MediaKind.PCM16, streamId = 65_535, timestampUs = 9_000_000_000L, payload = byteArrayOf(1, 2, 3))
        val back = MediaFrames.decode(MediaFrames.encode(frame))
        assertEquals(MediaKind.PCM16, back.kind)
        assertEquals(65_535, back.streamId) // uint16: must not come back negative
        assertEquals(9_000_000_000L, back.timestampUs)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.payload)
    }

    @Test
    fun `an empty payload is a valid frame`() {
        val back = MediaFrames.decode(MediaFrames.encode(MediaFrame(MediaKind.JPEG, 1, 0, ByteArray(0))))
        assertEquals(0, back.payload.size)
    }

    @Test
    fun `rejects a frame shorter than the header`() {
        assertThrows(ProtocolException::class.java) { MediaFrames.decode(ByteArray(11)) }
    }

    @Test
    fun `rejects a bad magic byte`() {
        val bytes = MediaFrames.encode(MediaFrame(MediaKind.JPEG, 1, 0, ByteArray(0))).also { it[0] = 0x00 }
        assertThrows(ProtocolException::class.java) { MediaFrames.decode(bytes) }
    }

    @Test
    fun `rejects an unknown media kind`() {
        val bytes = MediaFrames.encode(MediaFrame(MediaKind.JPEG, 1, 0, ByteArray(0))).also { it[1] = 0x7F }
        assertThrows(ProtocolException::class.java) { MediaFrames.decode(bytes) }
    }

    @Test
    fun `refuses to encode a stream id outside uint16`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaFrames.encode(MediaFrame(MediaKind.JPEG, 65_536, 0, ByteArray(0)))
        }
    }

    @Test
    fun `rotation defaults to zero when a hub omits it`() {
        val decoded = ProtocolJson.decodeHubToParent(
            """{"t":"stream_ready","streamId":1,"monitorId":"m","video":{"width":640,"height":480,"fps":10.0},"audio":null}""",
        ) as HubToParent.StreamReady
        assertEquals(0, decoded.video!!.rotationDegrees)
    }
}
