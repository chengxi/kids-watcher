package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBackpressureTest {

    @Test
    fun `sends when the queue is empty`() {
        assertTrue(MediaBackpressure.shouldSend(MediaKind.JPEG, 0))
        assertTrue(MediaBackpressure.shouldSend(MediaKind.PCM16, 0))
    }

    // A stale picture is worthless; a gap in sound is what a parent notices.
    @Test
    fun `drops video before audio as the queue grows`() {
        val between = MediaBackpressure.MAX_QUEUED_VIDEO_BYTES + 1
        assertFalse(MediaBackpressure.shouldSend(MediaKind.JPEG, between))
        assertTrue(MediaBackpressure.shouldSend(MediaKind.PCM16, between))
    }

    @Test
    fun `drops audio too once the queue is well behind`() {
        assertFalse(MediaBackpressure.shouldSend(MediaKind.PCM16, MediaBackpressure.MAX_QUEUED_AUDIO_BYTES + 1))
    }

    // OkHttp closes a WebSocket whose outgoing queue passes 16 MiB. On the
    // monitor that close would surface as MONITOR_LOST, so both caps must sit
    // far below it.
    @Test
    fun `both caps stay far below OkHttp's 16 MiB close threshold`() {
        val okHttpLimit = 16L * 1024 * 1024
        assertTrue(MediaBackpressure.MAX_QUEUED_VIDEO_BYTES * 4 <= okHttpLimit)
        assertTrue(MediaBackpressure.MAX_QUEUED_AUDIO_BYTES * 4 <= okHttpLimit)
    }

    // okhttp3.WebSocket is an interface, so the send path itself - not just the
    // policy - can be tested against a socket that reports a full queue.
    private class FakeWebSocket(private val queued: Long) : okhttp3.WebSocket {
        val sent = mutableListOf<okio.ByteString>()
        override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("http://localhost/").build()
        override fun queueSize(): Long = queued
        override fun send(text: String): Boolean = error("media must go out as binary")
        override fun send(bytes: okio.ByteString): Boolean { sent += bytes; return true }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    @Test
    fun `the send path drops a frame when the socket is behind, without sending it`() {
        val behind = FakeWebSocket(queued = MediaBackpressure.MAX_QUEUED_VIDEO_BYTES + 1)
        assertFalse(sendMediaOn(behind, MediaKind.JPEG, byteArrayOf(1)))
        assertTrue(behind.sent.isEmpty())
    }

    @Test
    fun `the send path sends as binary when the socket keeps up`() {
        val keepingUp = FakeWebSocket(queued = 0)
        assertTrue(sendMediaOn(keepingUp, MediaKind.JPEG, byteArrayOf(1, 2)))
        assertEquals(2, keepingUp.sent.single().size)
    }
}
