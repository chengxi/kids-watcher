package com.kidsmonitor.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun `decodes an event fanned out by the hub`() {
        val raw = """
            {"t":"event","eventId":42,"monitorId":"m1","type":"LOUD_NOISE",
             "tsMs":1757000000000,"metadata":{"peakDb":-9.5,"durationMs":2200}}
        """.trimIndent()
        val msg = ProtocolJson.decodeHubToParent(raw)
        assertTrue(msg is HubToParent.Event)
        val event = msg as HubToParent.Event
        assertEquals(42L, event.eventId)
        assertEquals(EventType.LOUD_NOISE, event.type)
        assertEquals(-9.5, event.metadata.peakDb!!, 0.001)
    }

    @Test
    fun `decodes an event whose metadata is absent`() {
        val raw = """{"t":"event","eventId":1,"monitorId":"m1","type":"MONITOR_LOST","tsMs":1}"""
        val event = ProtocolJson.decodeHubToParent(raw) as HubToParent.Event
        assertNull(event.metadata.peakDb)
    }

    @Test
    fun `decodes monitor status with a null battery`() {
        val raw = """
            {"t":"monitor_status","monitorId":"m1","name":"Nursery",
             "online":false,"lastSeenMs":null,"batteryPct":null}
        """.trimIndent()
        val status = ProtocolJson.decodeHubToParent(raw) as HubToParent.MonitorStatus
        assertNull(status.batteryPct)
        assertEquals(false, status.online)
    }

    @Test
    fun `ignores unknown fields so a newer hub does not break an older app`() {
        val raw = """
            {"t":"event","eventId":7,"monitorId":"m1","type":"MOTION","tsMs":1,
             "metadata":{},"somethingAddedLater":"whatever"}
        """.trimIndent()
        val event = ProtocolJson.decodeHubToParent(raw) as HubToParent.Event
        assertEquals(7L, event.eventId)
    }

    @Test(expected = ProtocolException::class)
    fun `rejects an unknown message type`() {
        ProtocolJson.decodeHubToParent("""{"t":"alien_invasion"}""")
    }

    @Test(expected = ProtocolException::class)
    fun `rejects malformed json`() {
        ProtocolJson.decodeHubToParent("{not json")
    }

    @Test
    fun `encodes subscribe with an explicit null meaning all monitors`() {
        val encoded = ProtocolJson.encode(ParentToHub.Subscribe(monitorIds = null))
        assertTrue(encoded.contains(""""t":"subscribe""""))
        assertTrue(encoded.contains(""""monitorIds":null"""))
    }

    @Test
    fun `encodes request_stream with the field names the hub expects`() {
        val encoded = ProtocolJson.encode(
            ParentToHub.RequestStream(monitorId = "m1", video = true, audio = false),
        )
        assertTrue(encoded.contains(""""t":"request_stream""""))
        assertTrue(encoded.contains(""""monitorId":"m1""""))
        assertTrue(encoded.contains(""""video":true"""))
    }

    @Test
    fun `encodes ack_event`() {
        val encoded = ProtocolJson.encode(ParentToHub.AckEvent(eventId = 12))
        assertTrue(encoded.contains(""""t":"ack_event""""))
        assertTrue(encoded.contains(""""eventId":12"""))
    }

    @Test
    fun `heartbeat encodes as an object, not a bare string`() {
        // A data object serializes to {} plus the discriminator. If it ever
        // encodes as "Heartbeat", the hub's zod parse fails and the socket
        // starts answering every heartbeat with an error.
        val encoded = ProtocolJson.encode(ParentToHub.Heartbeat)
        assertTrue(encoded.startsWith("{"))
        assertTrue(encoded.contains(""""t":"heartbeat""""))
    }
    @Test
    fun `decodes the hubs connection recovery event`() {
        val event = ProtocolJson.decodeHubToParent(
            """{"t":"event","eventId":2,"monitorId":"m1","type":"MONITOR_ONLINE","tsMs":2000,"metadata":{}}""",
        ) as HubToParent.Event
        assertEquals(EventType.MONITOR_ONLINE, event.type)
        assertNull(event.metadata.peakDb)
    }

}
