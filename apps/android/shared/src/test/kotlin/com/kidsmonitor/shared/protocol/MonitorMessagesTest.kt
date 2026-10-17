package com.kidsmonitor.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorMessagesTest {

    @Test
    fun `decodes the config the hub pushes on connect`() {
        val raw = """
            {"t":"config","config":{"thresholdDb":12.0,"sustainMs":1500,"cooldownS":60,
             "noiseFloorDb":-50.0,"motionEnabled":false,"motionThreshold":12.0,
             "calibrationWindowMs":30000}}
        """.trimIndent()
        val msg = ProtocolJson.decodeHubToMonitor(raw)
        assertTrue(msg is HubToMonitor.Config)
        assertEquals(12.0, (msg as HubToMonitor.Config).config.thresholdDb, 0.001)
    }

    @Test
    fun `decodes a recalibrate request`() {
        val msg = ProtocolJson.decodeHubToMonitor("""{"t":"recalibrate","windowMs":30000}""")
        assertEquals(30000, (msg as HubToMonitor.Recalibrate).windowMs)
    }

    @Test
    fun `decodes start_stream even though M5 does not stream`() {
        // The hub sends this whenever a parent taps view. A monitor that cannot
        // decode it would log a parse error on every attempt.
        val raw = """{"t":"start_stream","streamId":3,"video":true,"audio":true}"""
        val msg = ProtocolJson.decodeHubToMonitor(raw) as HubToMonitor.StartStream
        assertEquals(3, msg.streamId)
    }

    @Test(expected = ProtocolException::class)
    fun `rejects a parent message on the monitor schema`() {
        ProtocolJson.decodeHubToMonitor("""{"t":"monitor_status","monitorId":"m1"}""")
    }

    @Test
    fun `encodes an event with metadata the hub accepts`() {
        val encoded = ProtocolJson.encodeMonitor(
            MonitorToHub.Event(
                clientEventId = "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
                type = EventType.LOUD_NOISE,
                tsMs = 1757000000000,
                metadata = EventMetadata(peakDb = -9.5, durationMs = 2200),
            ),
        )
        assertTrue(encoded.contains(""""t":"event""""))
        assertTrue(encoded.contains(""""peakDb":-9.5"""))
    }

    @Test
    fun `encodes a heartbeat carrying battery and thermal state`() {
        val encoded = ProtocolJson.encodeMonitor(
            MonitorToHub.Heartbeat(batteryPct = 88, thermal = "none"),
        )
        assertTrue(encoded.contains(""""t":"heartbeat""""))
        assertTrue(encoded.contains(""""batteryPct":88"""))
    }

    @Test
    fun `encodes a heartbeat with an unknown battery as null, not omitted`() {
        // The hub's zod schema has batteryPct as nullable but not optional:
        // dropping the key entirely would be rejected.
        val encoded = ProtocolJson.encodeMonitor(
            MonitorToHub.Heartbeat(batteryPct = null, thermal = "unknown"),
        )
        assertTrue(encoded.contains(""""batteryPct":null"""))
    }

    @Test
    fun `the Kotlin defaults match the spec, and so the hub's`() {
        // The hub pushes its own copy on connect. If these drift, the monitor
        // behaves one way before that message arrives and another after, which
        // is a maddening thing to debug.
        assertEquals(12.0, DEFAULT_MONITOR_CONFIG.thresholdDb, 0.001)
        assertEquals(1500, DEFAULT_MONITOR_CONFIG.sustainMs)
        assertEquals(60, DEFAULT_MONITOR_CONFIG.cooldownS)
        assertEquals(-50.0, DEFAULT_MONITOR_CONFIG.noiseFloorDb, 0.001)
        assertEquals(false, DEFAULT_MONITOR_CONFIG.motionEnabled)
        assertEquals(12.0, DEFAULT_MONITOR_CONFIG.motionThreshold, 0.001)
        assertEquals(30_000, DEFAULT_MONITOR_CONFIG.calibrationWindowMs)
    }

    @Test
    fun `encodes a calibration result`() {
        val encoded = ProtocolJson.encodeMonitor(
            MonitorToHub.CalibrationResult(noiseFloorDb = -57.5, spreadDb = 3.2, sampledMs = 30000),
        )
        assertTrue(encoded.contains(""""t":"calibration_result""""))
        assertTrue(encoded.contains(""""noiseFloorDb":-57.5"""))
    }
}
