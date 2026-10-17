package com.kidsmonitor.shared.protocol

import org.junit.Assert.*
import org.junit.Test

class CameraProtocolTest {
    @Test fun `decodes switch command result and camera metadata`() {
        assertEquals(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT),
            ProtocolJson.decodeHubToMonitor("""{"t":"select_camera","streamId":4,"requestId":"front","facing":"front"}"""))
        assertEquals(HubToParent.CameraResult(4, "front", "Unavailable"),
            ProtocolJson.decodeHubToParent("""{"t":"camera_result","streamId":4,"requestId":"front","reason":"Unavailable"}"""))
        val ready = ProtocolJson.decodeHubToParent("""{"t":"stream_ready","streamId":4,"monitorId":"m","audio":null,"video":{"width":640,"height":480,"fps":10,"facing":"front","availableCameras":["back","front"]}}""") as HubToParent.StreamReady
        assertEquals(CameraFacing.FRONT, ready.video?.facing)
        assertEquals(CameraFacing.entries, ready.video?.availableCameras)
        val legacy = ProtocolJson.decodeHubToParent("""{"t":"stream_ready","streamId":4,"monitorId":"m","audio":null,"video":{"width":640,"height":480,"fps":10}}""") as HubToParent.StreamReady
        assertNull(legacy.video?.facing)
        assertTrue(legacy.video!!.availableCameras.isEmpty())
    }
}
