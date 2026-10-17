package com.kidsmonitor.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProgressProtocolTest {
    @Test
    fun `decodes correlated startup progress and ready from the hub`() {
        assertEquals(
            HubToParent.StreamWaiting(7, "m", "preparing_video", "attempt-2"),
            ProtocolJson.decodeHubToParent("""{"t":"stream_waiting","streamId":7,"monitorId":"m","phase":"preparing_video","requestId":"attempt-2"}"""),
        )
        assertEquals("attempt-2", (ProtocolJson.decodeHubToParent("""{"t":"stream_ready","streamId":7,"monitorId":"m","video":null,"audio":null,"requestId":"attempt-2"}""") as HubToParent.StreamReady).requestId)
        assertEquals("attempt-2", (ProtocolJson.decodeHubToParent("""{"t":"error","code":"monitor_offline","message":"offline","monitorId":"m","requestId":"attempt-2"}""") as HubToParent.ErrorMessage).requestId)
    }

    @Test
    fun `legacy ready responses still decode without a request id`() {
        assertEquals(null, (ProtocolJson.decodeHubToParent("""{"t":"stream_ready","streamId":7,"monitorId":"m","video":null,"audio":null}""") as HubToParent.StreamReady).requestId)
    }
}
