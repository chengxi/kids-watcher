package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionTest {

    private class FakeGateway : StreamGateway {
        val requested = mutableListOf<String>()
        val stopped = mutableListOf<Int>()
        val ids = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var reconnects = 0
        override fun requestStream(monitorId: String) { requested += monitorId }
        override fun requestStream(monitorId: String, requestId: String) { requested += monitorId; ids += requestId }
        override fun cancelRequest(monitorId: String, requestId: String) { cancelled += requestId }
        override fun reconnectHub() { reconnects++ }
        override fun stopStream(streamId: Int) { stopped += streamId }
    }

    private val video = VideoFormat(640, 480, 10.0, rotationDegrees = 90)
    private val audio = AudioFormat(16_000, 1)
    private fun ready(streamId: Int = 7, monitorId: String = "m1") =
        HubToParent.StreamReady(streamId = streamId, monitorId = monitorId, video = video, audio = audio)
    private fun frame(streamId: Int) = MediaFrame(MediaKind.JPEG, streamId, 0, ByteArray(1))

    private fun TestScope.session(gateway: FakeGateway) = LiveSession(gateway, backgroundScope)

    @Test
    fun `starting asks the hub for the stream`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        assertEquals(listOf("m1"), gateway.requested)
        assertEquals(LiveState.Requesting("m1"), live.state.value)
    }

    @Test
    fun `stream_ready for the requested monitor starts playing`() = runTest {
        val live = session(FakeGateway())
        live.start("m1")
        live.onStreamReady(ready())
        assertEquals(LiveState.Playing("m1", 7, video, audio), live.state.value)
    }

    @Test
    fun `a format update rotates the active stream without restarting it`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1"); live.onStreamReady(ready())
        val rotated = video.copy(rotationDegrees = 270)
        live.onStreamReady(ready().copy(video = rotated))
        assertEquals(LiveState.Playing("m1", 7, rotated, audio), live.state.value)
        live.onStreamReady(ready(streamId = 8))
        live.onStreamReady(ready(monitorId = "m2"))
        assertEquals(LiveState.Playing("m1", 7, rotated, audio), live.state.value)
        assertEquals(listOf("m1"), gateway.requested)
        assertTrue(gateway.stopped.isEmpty())
        live.stop()
        live.onStreamReady(ready().copy(video = rotated))
        assertEquals(LiveState.Idle, live.state.value)
    }

    @Test
    fun `stream_ready for a different monitor is ignored`() = runTest {
        val live = session(FakeGateway())
        live.start("m1")
        live.onStreamReady(ready(monitorId = "m2"))
        assertEquals(LiveState.Requesting("m1"), live.state.value)
    }

    @Test
    fun `stopping while playing tells the hub, so the camera turns off`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1"); live.onStreamReady(ready())
        live.stop()
        assertEquals(listOf(7), gateway.stopped)
        assertEquals(LiveState.Idle, live.state.value)
    }

    // stop_stream needs a streamId, which only stream_ready carries. A viewer
    // who leaves before the answer must still release the monitor's camera
    // when the answer arrives - otherwise it runs with nobody watching.
    @Test
    fun `leaving before the stream is ready still stops it when it arrives`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        live.stop()
        live.onStreamReady(ready(streamId = 9))
        assertEquals(listOf(9), gateway.stopped)
        assertEquals(LiveState.Idle, live.state.value)
    }

    @Test
    fun `retries a silent hub three times then stops a legacy late stream`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        repeat(2) { index ->
            advanceTimeBy(START_TIMEOUT_MS + 1); runCurrent()
            assertTrue(live.state.value is LiveState.Retrying)
            advanceTimeBy(2_000L * (index + 1)); runCurrent()
        }
        advanceTimeBy(START_TIMEOUT_MS + 1); runCurrent()
        assertEquals(3, gateway.requested.size)
        assertEquals(3, gateway.cancelled.size)
        assertEquals(3, gateway.reconnects)
        assertTrue(live.state.value is LiveState.Ended)
        live.onStreamReady(ready(streamId = 11))
        assertEquals(listOf(11), gateway.stopped)
    }

    @Test
    fun `no timeout once playing`() = runTest {
        val live = session(FakeGateway())
        live.start("m1"); live.onStreamReady(ready())
        live.onPictureDecoded(7)
        advanceTimeBy(START_TIMEOUT_MS * 3); runCurrent()
        assertTrue(live.state.value is LiveState.Playing)
    }

    @Test
    fun `stream_ended for the playing stream ends it with the hub's reason`() = runTest {
        val live = session(FakeGateway())
        live.start("m1"); live.onStreamReady(ready())
        live.onStreamEnded(HubToParent.StreamEnded(streamId = 7, reason = "monitor disconnected"))
        assertTrue(live.state.value is LiveState.Retrying)
    }

    @Test
    fun `stream_ended for another stream is ignored`() = runTest {
        val live = session(FakeGateway())
        live.start("m1"); live.onStreamReady(ready())
        live.onStreamEnded(HubToParent.StreamEnded(streamId = 99, reason = "x"))
        assertTrue(live.state.value is LiveState.Playing)
    }

    @Test
    fun `an offline monitor retries with the hub reported reason`() = runTest {
        val live = session(FakeGateway())
        live.start("m1")
        live.onError(HubToParent.ErrorMessage(code = "monitor_offline", message = "monitor is not connected"))
        assertEquals(LiveFailure.MONITOR_OFFLINE, (live.state.value as LiveState.Retrying).failure)
    }

    @Test
    fun `losing the hub waits and requests again after recovery`() = runTest {
        val live = session(FakeGateway())
        live.start("m1"); live.onStreamReady(ready())
        live.onDisconnected()
        assertTrue(live.state.value is LiveState.WaitingForHub)
        live.onConnectionChanged(ConnectionState.Connected)
        assertTrue(live.state.value is LiveState.Requesting)
    }

    @Test
    fun `accepts only frames for the stream being played`() = runTest {
        val live = session(FakeGateway())
        assertFalse(live.accepts(frame(7)))
        live.start("m1"); live.onStreamReady(ready())
        assertTrue(live.accepts(frame(7)))
        assertFalse(live.accepts(frame(8)))
        live.stop()
        assertFalse(live.accepts(frame(7)))
    }

    @Test
    fun `starting the monitor already playing does not request twice`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1"); live.onStreamReady(ready())
        live.start("m1")
        assertEquals(listOf("m1"), gateway.requested)
    }

    @Test
    fun `try again after it ended requests afresh`() = runTest {
        val gateway = FakeGateway()
        val live = LiveSession(gateway, backgroundScope, maxAttempts = 1)
        live.start("m1")
        live.onError(HubToParent.ErrorMessage("monitor_offline", "x"))
        live.start("m1")
        assertEquals(listOf("m1", "m1"), gateway.requested)
    }

    // Final review M5: a screen for one monitor being cleared (after the viewer
    // already moved on to another monitor's screen) must not stop the stream
    // the other screen is now playing.
    @Test
    fun `stopping for another monitor leaves the playing stream alone`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        live.onStreamReady(ready())

        live.stop("m2")

        assertEquals(emptyList<Int>(), gateway.stopped)
        assertEquals(LiveState.Playing("m1", 7, video, audio), live.state.value)
    }

    @Test
    fun `stopping for another monitor leaves a pending request alone`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        live.stop("m2")
        live.onStreamReady(ready())
        assertEquals(emptyList<Int>(), gateway.stopped)
        assertEquals(LiveState.Playing("m1", 7, video, audio), live.state.value)
    }

    @Test
    fun `stopping for the monitor being played stops it`() = runTest {
        val gateway = FakeGateway()
        val live = session(gateway)
        live.start("m1")
        live.onStreamReady(ready())
        live.stop("m1")
        assertEquals(listOf(7), gateway.stopped)
        assertEquals(LiveState.Idle, live.state.value)
    }
    @Test
    fun `cold start waits for a connected hub without burning video attempts`() = runTest {
        val gateway = FakeGateway()
        val live = LiveSession(gateway, backgroundScope, initialConnection = ConnectionState.Connecting)
        live.start("m1")
        advanceTimeBy(60_000); runCurrent()
        assertTrue(gateway.requested.isEmpty())
        assertTrue(live.state.value is LiveState.WaitingForHub)
        live.onConnectionChanged(ConnectionState.Connected)
        assertEquals(1, (live.state.value as LiveState.Requesting).attempt)
        assertEquals(listOf("m1"), gateway.requested)
    }

    @Test
    fun `progress distinguishes hub receipt from monitor acknowledgement and preserves a slow start`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1")
        advanceTimeBy(9_000); runCurrent()
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "contacting_monitor", gateway.ids.last()))
        assertEquals(RequestPhase.MONITOR, (live.state.value as LiveState.Requesting).phase)
        advanceTimeBy(9_000); runCurrent()
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "preparing_video", gateway.ids.last()))
        assertEquals(RequestPhase.VIDEO, (live.state.value as LiveState.Requesting).phase)
        advanceTimeBy(9_000); runCurrent()
        live.onStreamReady(ready().copy(requestId = gateway.ids.last()))
        assertTrue(live.state.value is LiveState.Playing)
        assertEquals(1, gateway.requested.size)
    }

    @Test
    fun `a hub acknowledgement without monitor response reports an unresponsive monitor`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1")
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "contacting_monitor", gateway.ids.last()))
        advanceTimeBy(START_TIMEOUT_MS + 1); runCurrent()
        assertEquals(LiveFailure.MONITOR_UNRESPONSIVE, (live.state.value as LiveState.Retrying).failure)
        assertEquals(0, gateway.reconnects)
    }

    @Test
    fun `repeated camera acknowledgements cannot prevent timeout and stale attempts cannot replace a retry`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1"); val first = gateway.ids.last()
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "preparing_video", first))
        advanceTimeBy(10_000); runCurrent()
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "preparing_video", first))
        advanceTimeBy(5_001); runCurrent()
        assertEquals(LiveFailure.VIDEO_DELAY, (live.state.value as LiveState.Retrying).failure)
        advanceTimeBy(2_000); runCurrent()
        live.onStreamReady(ready().copy(requestId = first))
        live.onStreamWaiting(HubToParent.StreamWaiting(7, "m1", "preparing_video", first))
        live.onError(HubToParent.ErrorMessage("monitor_offline", "old", "m1", first))
        assertEquals(RequestPhase.HUB, (live.state.value as LiveState.Requesting).phase)
        live.onStreamReady(ready(8).copy(requestId = gateway.ids.last()))
        assertEquals(8, (live.state.value as LiveState.Playing).streamId)
    }

    @Test
    fun `leaving during retry or network recovery cancels future requests`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1")
        live.onError(HubToParent.ErrorMessage("monitor_offline", "x"))
        live.stop("m1")
        advanceTimeBy(60_000); runCurrent()
        live.onConnectionChanged(ConnectionState.Connected)
        assertEquals(1, gateway.requested.size)
        assertEquals(LiveState.Idle, live.state.value)
        live.start("m1")
        live.onDisconnected(); live.stop()
        live.onConnectionChanged(ConnectionState.Connected)
        assertEquals(2, gateway.requested.size)
    }

    @Test
    fun `missing first picture retries even when live audio arrives`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1"); live.onStreamReady(ready())
        assertTrue(live.accepts(MediaFrame(MediaKind.PCM16, 7, 0, ByteArray(2))))
        advanceTimeBy(FIRST_PICTURE_TIMEOUT_MS + 1); runCurrent()
        assertEquals(LiveFailure.VIDEO_DELAY, (live.state.value as LiveState.Retrying).failure)
        assertEquals(listOf(7), gateway.stopped)
    }

    @Test
    fun `camera failures retry automatically and leave audio available after three attempts`() = runTest {
        val gateway = FakeGateway(); val live = session(gateway)
        live.start("m1")
        repeat(2) { index ->
            live.onStreamReady(ready(index + 1).copy(video = null, requestId = gateway.ids.last()))
            assertEquals(LiveFailure.CAMERA_UNAVAILABLE, (live.state.value as LiveState.Retrying).failure)
            advanceTimeBy(2_000L * (index + 1)); runCurrent()
        }
        live.onStreamReady(ready(3).copy(video = null, requestId = gateway.ids.last()))
        assertEquals(null, (live.state.value as LiveState.Playing).video)
        assertEquals(audio, (live.state.value as LiveState.Playing).audio)
        assertEquals(listOf(1, 2), gateway.stopped)
        assertEquals(3, gateway.requested.size)
    }

}
