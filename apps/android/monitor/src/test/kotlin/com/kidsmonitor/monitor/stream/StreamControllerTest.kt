package com.kidsmonitor.monitor.stream

import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.MonitorToHub
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamControllerTest {

    /**
     * A camera that works. By default it delivers one frame while starting, as
     * a real camera does within moments of opening; that frame is what tells
     * the controller the camera really runs, and it is not sent (the stream has
     * not been announced yet).
     */
    private class FakeCamera(
        var format: VideoFormat? = VideoFormat(640, 480, 10.0, rotationDegrees = 90),
        var deliversFirstFrame: Boolean = true,
        val errorWhileOpening: String? = null,
        val firstFrameRotation: Int? = null,
    ) : VideoSource {
        var selectedFacing = CameraFacing.BACK
        var switchable = false
        var failedFacing: CameraFacing? = null
        override suspend fun selectFacing(facing: CameraFacing): Boolean {
            if (!switchable) return false
            selectedFacing = facing
            return true
        }
        var running = false
        var starts = 0
        var stops = 0
        val calls = mutableListOf<String>()
        var onJpeg: ((ByteArray, Int) -> Unit)? = null
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat? {
            starts++
            calls += "start"
            if (format == null || failedFacing == selectedFacing) return null
            running = true
            this.onJpeg = onJpeg
            errorWhileOpening?.let(onError)
            if (deliversFirstFrame) onJpeg(byteArrayOf(0), firstFrameRotation ?: format!!.rotationDegrees)
            return if (switchable) format?.copy(facing = selectedFacing, availableCameras = CameraFacing.entries) else format
        }
        override suspend fun stop() { running = false; stops++; calls += "stop"; onJpeg = null }
        fun emit(bytes: ByteArray, rotation: Int = format!!.rotationDegrees) = onJpeg?.invoke(bytes, rotation)
    }

    /** A camera whose start() fails outright, e.g. CameraX rejecting the bind. */
    private class ThrowingStartCamera : VideoSource {
        var stopCalled = false
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat? = error("camera init failed")
        override suspend fun stop() { stopCalled = true }
    }

    /** A camera whose start() never returns until cancelled, to exercise stream-controller cancellation. */
    private class SuspendingCamera : VideoSource {
        var stopped = false
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat? = awaitCancellation()
        override suspend fun stop() { stopped = true }
    }

    /** A camera that starts fine but whose stop() fails, e.g. releasing CameraX throws. */
    private class ThrowingStopCamera(
        private val format: VideoFormat = VideoFormat(640, 480, 10.0, rotationDegrees = 90),
    ) : VideoSource {
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat {
            onJpeg(byteArrayOf(0), format.rotationDegrees)
            return format
        }
        override suspend fun stop() { error("camera stop failed") }
    }

    /** A camera whose stop() suspends before it has released anything, as a real release may. */
    private class SuspendingStopCamera(private val release: suspend () -> Unit) : VideoSource {
        var stopped = false
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat {
            onJpeg(byteArrayOf(0), 0)
            return VideoFormat(640, 480, 10.0)
        }
        override suspend fun stop() { release(); stopped = true }
    }

    private class FakeOutput : StreamOutput {
        val control = mutableListOf<MonitorToHub>()
        val media = mutableListOf<MediaFrame>()
        val wire = mutableListOf<Any>()
        override fun sendControl(message: MonitorToHub) { control += message; wire += message }
        override fun sendMedia(frame: MediaFrame): Boolean { media += frame; wire += frame; return true }
    }

    private var clockUs = 5_000_000L
    private val streaming = mutableListOf<Boolean>()
    private var micCapturing = true
    private fun controller(camera: VideoSource, output: FakeOutput) =
        StreamController(
            camera, output, sampleRate = 16_000, audioAvailable = { micCapturing },
            nowUs = { clockUs }, onStreamingChanged = { streaming += it },
        )

    private fun start(id: Int = 4, video: Boolean = true, audio: Boolean = true) = HubToMonitor.StartStream(id, video, audio)

    @Test
    fun `initial rotation comes from the proving frame rather than bind metadata`() = runTest {
        val camera = FakeCamera(firstFrameRotation = 180); val out = FakeOutput()
        controller(camera, out).onStartStream(start())
        assertEquals(180, (out.control.single() as MonitorToHub.StreamStarted).video!!.rotationDegrees)
        assertTrue(out.media.isEmpty())
    }

    @Test
    fun `a turn reannounces rotation before its picture and preserves audio`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        for (rotation in listOf(0, 180, 270, 90)) {
            camera.emit(byteArrayOf(1), rotation)
            val update = out.wire[out.wire.lastIndex - 1] as MonitorToHub.StreamStarted
            assertEquals(4, update.streamId)
            assertEquals(rotation, update.video!!.rotationDegrees)
            assertEquals(AudioFormat(16_000, 1), update.audio)
            assertTrue(out.wire.last() is MediaFrame)
        }
        camera.emit(byteArrayOf(2), 90)
        assertEquals(5, out.control.size) // unchanged rotation needs no announcement
        assertEquals(1, camera.starts)
        assertEquals(listOf(true), streaming)
    }

    @Test
    fun `starting with video and audio opens the camera and announces both formats`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        controller(camera, out).onStartStream(start())
        assertTrue(camera.running)
        assertEquals(
            listOf(MonitorToHub.StreamStarted(4, VideoFormat(640, 480, 10.0, 90), AudioFormat(16_000, 1))),
            out.control,
        )
        assertEquals(listOf(true), streaming)
    }

    @Test
    fun `audio-only requests never open the camera`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        controller(camera, out).onStartStream(start(video = false))
        assertEquals(0, camera.starts)
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
    }

    // The microphone is still worth something when the camera is not.
    @Test
    fun `a camera that will not start falls back to sound only`() = runTest {
        val out = FakeOutput()
        controller(FakeCamera(format = null), out).onStartStream(start())
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
    }

    @Test
    fun `a camera that will not start, with no audio asked for, declines with a reason`() = runTest {
        val out = FakeOutput()
        val c = controller(FakeCamera(format = null), out)
        c.onStartStream(start(audio = false))
        val reply = out.control.single() as MonitorToHub.StreamStopped
        assertEquals(4, reply.streamId)
        assertTrue(reply.reason.contains("camera"))
        assertNull(c.activeStreamId)
    }

    // A camera that throws while starting must not leave the stream half-started:
    // the hub is owed a reply either way, same as a camera that returns null.
    @Test
    fun `a camera whose start throws falls back to sound only, and the camera is stopped`() = runTest {
        val camera = ThrowingStartCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
        assertEquals(4, c.activeStreamId)
        assertTrue(camera.stopCalled)
    }

    @Test
    fun `a camera whose start throws, with no audio asked for, declines with a reason, and the camera is stopped`() = runTest {
        val camera = ThrowingStartCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start(audio = false))
        val reply = out.control.single() as MonitorToHub.StreamStopped
        assertEquals(4, reply.streamId)
        assertNull(c.activeStreamId)
        assertTrue(camera.stopCalled)
    }

    // A start still suspended in the camera when its coroutine is cancelled (e.g.
    // the service is being destroyed) must not leave the camera running or the
    // stream half-open.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a start in progress stops the camera and clears the stream`() = runTest {
        val camera = SuspendingCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        val job = launch { c.onStartStream(start()) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(camera.stopped)
        assertNull(c.activeStreamId)
        assertTrue(streaming.isEmpty())
    }

    @Test
    fun `camera frames go out as JPEG frames on the stream, timed from its start`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        controller(camera, out).onStartStream(start())
        clockUs += 250_000
        camera.emit(byteArrayOf(9, 9))
        val frame = out.media.single()
        assertEquals(MediaKind.JPEG, frame.kind)
        assertEquals(4, frame.streamId)
        assertEquals(250_000L, frame.timestampUs)
        assertArrayEquals(byteArrayOf(9, 9), frame.payload)
    }

    @Test
    fun `audio windows go out as little-endian PCM while streaming`() = runTest {
        val out = FakeOutput()
        val c = controller(FakeCamera(), out)
        c.onAudioWindow(shortArrayOf(1), 1) // idle: nothing
        c.onStartStream(start())
        c.onAudioWindow(shortArrayOf(1, -1), 2)
        val frame = out.media.single()
        assertEquals(MediaKind.PCM16, frame.kind)
        assertArrayEquals(byteArrayOf(1, 0, -1, -1), frame.payload)
    }

    @Test
    fun `no audio frames when only video was asked for`() = runTest {
        val out = FakeOutput()
        val c = controller(FakeCamera(), out)
        c.onStartStream(start(audio = false))
        c.onAudioWindow(shortArrayOf(1), 1)
        assertTrue(out.media.isEmpty())
    }

    @Test
    fun `stop_stream for this stream turns the camera off and silences audio`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        c.onStopStream(HubToMonitor.StopStream(4))
        assertFalse(camera.running)
        c.onAudioWindow(shortArrayOf(1), 1)
        assertTrue(out.media.isEmpty())
        assertEquals(listOf(true, false), streaming)
    }

    // A camera that fails to release must not swallow the streaming-off signal,
    // and must not leave the caller (e.g. a replacement start_stream) unable to proceed.
    @Test
    fun `stop_stream still turns streaming off when the camera fails to stop`() = runTest {
        val camera = ThrowingStopCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        c.onStopStream(HubToMonitor.StopStream(4)) // must not throw despite the camera failing to stop
        assertEquals(listOf(true, false), streaming)
        assertNull(c.activeStreamId)
    }

    @Test
    fun `stop_stream for another stream is ignored`() = runTest {
        val camera = FakeCamera()
        val c = controller(camera, FakeOutput())
        c.onStartStream(start())
        c.onStopStream(HubToMonitor.StopStream(99))
        assertTrue(camera.running)
    }

    // The hub ends the stream when the monitor's socket closes. The camera must
    // not keep running on its own - that is spec §4.4's whole thermal argument.
    @Test
    fun `losing the hub turns the camera off without trying to send`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        out.control.clear()
        c.onDisconnected()
        assertFalse(camera.running)
        assertTrue(out.control.isEmpty())
    }

    @Test
    fun `stopping the monitor tells the hub and turns the camera off`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        out.control.clear()
        c.shutdown()
        assertFalse(camera.running)
        assertEquals(4, (out.control.single() as MonitorToHub.StreamStopped).streamId)
    }

    @Test
    fun `a new stream id replaces the old stream, stopping the previous camera first`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start(id = 4))
        c.onStartStream(start(id = 5))
        assertEquals(listOf("start", "stop", "start"), camera.calls) // old camera stopped before the new one starts
        assertEquals(listOf(true, false, true), streaming)
        assertEquals(
            MonitorToHub.StreamStarted(5, VideoFormat(640, 480, 10.0, 90), AudioFormat(16_000, 1)),
            out.control.last(),
        )
        camera.emit(byteArrayOf(1))
        assertEquals(5, out.media.single().streamId)
    }

    @Test
    fun `switches both ways on the same stream and rejects late frames from the old lens`() = runTest {
        val camera = FakeCamera().apply { switchable = true }
        val out = FakeOutput(); val c = controller(camera, out)
        c.onStartStream(start())
        val oldFrame = camera.onJpeg!!
        c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT))
        assertEquals(CameraFacing.FRONT, (out.control.filterIsInstance<MonitorToHub.StreamStarted>().last()).video?.facing)
        assertEquals(MonitorToHub.CameraResult(4, "front"), out.control.last())
        oldFrame(byteArrayOf(9), 270)
        assertTrue(out.media.isEmpty())
        camera.emit(byteArrayOf(2), 90)
        assertEquals(4, out.media.single().streamId)
        c.onAudioWindow(shortArrayOf(7), 1)
        assertEquals(MediaKind.PCM16, out.media.last().kind)
        c.onSelectCamera(HubToMonitor.SelectCamera(4, "back", CameraFacing.BACK))
        assertEquals(CameraFacing.BACK, (out.control.filterIsInstance<MonitorToHub.StreamStarted>().last()).video?.facing)
        assertEquals(3, camera.starts)
        assertEquals(listOf(true), streaming)
    }

    @Test
    fun `failed front camera restores back camera and reports failure without ending audio`() = runTest {
        val camera = FakeCamera().apply { switchable = true; failedFacing = CameraFacing.FRONT }
        val out = FakeOutput(); val c = controller(camera, out)
        c.onStartStream(start())
        c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT))
        val format = out.control.filterIsInstance<MonitorToHub.StreamStarted>().last()
        assertEquals(CameraFacing.BACK, format.video?.facing)
        assertEquals(AudioFormat(16_000, 1), format.audio)
        assertTrue((out.control.last() as MonitorToHub.CameraResult).reason!!.contains("restored"))
        assertEquals(4, c.activeStreamId)
    }

    @Test
    fun `unsupported lens and stale stream do not disturb capture`() = runTest {
        val camera = FakeCamera().apply { switchable = true }
        val out = FakeOutput(); val c = controller(camera, out)
        c.onStartStream(start())
        c.onSelectCamera(HubToMonitor.SelectCamera(5, "stale", CameraFacing.FRONT))
        camera.switchable = false
        c.onSelectCamera(HubToMonitor.SelectCamera(4, "missing", CameraFacing.FRONT))
        assertEquals(0, camera.stops)
        assertEquals(1, camera.starts)
        assertTrue(camera.running)
        assertTrue(out.control.filterIsInstance<MonitorToHub.CameraResult>().all { it.reason != null })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `audio continues during switch and leaving releases the replacement camera`() = runTest {
        val camera = FakeCamera(deliversFirstFrame = false).apply { switchable = true }
        val out = FakeOutput(); val c = controller(camera, out)
        val start = launch { c.onStartStream(start()) }
        runCurrent(); camera.emit(byteArrayOf(1)); start.join()
        val change = launch { c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT)) }
        runCurrent()
        c.onAudioWindow(shortArrayOf(8), 1)
        assertEquals(MediaKind.PCM16, out.media.single().kind)
        val stop = launch { c.onStopStream(HubToMonitor.StopStream(4)) }
        camera.emit(byteArrayOf(2)); change.join(); stop.join()
        assertFalse(camera.running)
        assertNull(c.activeStreamId)
        assertEquals(listOf(true, false), streaming)
    }

    @Test
    fun `a lens that never produces a frame times out and restores the previous camera`() = runTest {
        val camera = FakeCamera().apply { switchable = true }
        val out = FakeOutput(); val c = controller(camera, out)
        c.onStartStream(start())
        camera.deliversFirstFrame = false
        val change = launch { c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT)) }
        advanceTimeBy(FIRST_FRAME_TIMEOUT_MS + 1); runCurrent()
        assertEquals(CameraFacing.BACK, camera.selectedFacing)
        camera.emit(byteArrayOf(1)); change.join()
        assertEquals(CameraFacing.BACK, out.control.filterIsInstance<MonitorToHub.StreamStarted>().last().video?.facing)
        assertTrue((out.control.last() as MonitorToHub.CameraResult).reason!!.contains("restored"))
    }

    @Test
    fun `service cancellation during a camera switch releases the camera`() = runTest {
        val camera = FakeCamera().apply { switchable = true }
        val out = FakeOutput(); val c = controller(camera, out)
        c.onStartStream(start()); camera.deliversFirstFrame = false
        val change = launch { c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT)) }
        runCurrent(); change.cancel(); change.join()
        assertFalse(camera.running); assertNull(c.activeStreamId)
    }

    @Test
    fun `both cameras failing preserves sound or ends a stream with no remaining media`() = runTest {
        for (hasAudio in listOf(true, false)) {
            micCapturing = hasAudio
            val camera = FakeCamera().apply { switchable = true }
            val out = FakeOutput(); val c = controller(camera, out)
            c.onStartStream(start()); camera.format = null
            c.onSelectCamera(HubToMonitor.SelectCamera(4, "front", CameraFacing.FRONT))
            assertFalse(camera.running)
            if (hasAudio) {
                val format = out.control.filterIsInstance<MonitorToHub.StreamStarted>().last()
                assertNull(format.video); assertEquals(AudioFormat(16_000, 1), format.audio)
                assertEquals(4, c.activeStreamId)
                assertTrue((out.control.last() as MonitorToHub.CameraResult).reason!!.contains("either"))
            } else {
                assertNull(c.activeStreamId)
                assertTrue(out.control.last() is MonitorToHub.StreamStopped)
            }
        }
    }

    // The camera executor can deliver one more frame after stop() returns. It
    // must not go out stamped with a stream the hub has already ended.
    @Test
    fun `a frame the camera delivers after stopping is not sent`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        val late = camera.onJpeg!!
        c.onStopStream(HubToMonitor.StopStream(4))
        late(byteArrayOf(7), 180)
        assertTrue(out.media.isEmpty())
    }

    @Test
    fun `a repeated start for the same stream is ignored`() = runTest {
        val camera = FakeCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start(id = 4))
        c.onStartStream(start(id = 4))
        assertEquals(1, camera.starts)
        assertEquals(1, out.control.size)
    }

    // MonitorService.onDestroy calls shutdown() while the service is being torn
    // down. A camera stop that suspends must still run to the end: cancellation
    // there would be swallowed by the best-effort runCatching and leave the
    // camera open with nobody left to close it.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown from an already-cancelled scope still releases a camera whose stop suspends`() = runTest {
        val camera = SuspendingStopCamera { yield() }; val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        out.control.clear()
        launch {
            cancel() // this coroutine is cancelled before shutdown begins
            c.shutdown()
        }
        advanceUntilIdle()
        assertTrue(camera.stopped)
        assertEquals(4, (out.control.single() as MonitorToHub.StreamStopped).streamId)
        assertEquals(listOf(true, false), streaming)
    }

    // The production shape: onDestroy bounds shutdown() with a timeout. The
    // timeout may abandon the wait for the hub, never the camera release.
    @Test
    fun `a shutdown timeout does not abort a camera stop in progress`() = runTest {
        val camera = SuspendingStopCamera { delay(5_000) }
        val c = controller(camera, FakeOutput())
        c.onStartStream(start())
        withTimeoutOrNull(2_000) { c.shutdown() }
        assertTrue(camera.stopped)
    }

    // Final review I4: a monitor must not advertise sound it is not capturing.
    // A parent hearing silence reads it as "the room is quiet".
    @Test
    fun `a microphone that is not capturing is not advertised`() = runTest {
        micCapturing = false
        val out = FakeOutput()
        val c = controller(FakeCamera(), out)
        c.onStartStream(start())
        assertEquals(listOf(MonitorToHub.StreamStarted(4, VideoFormat(640, 480, 10.0, 90), null)), out.control)
        c.onAudioWindow(shortArrayOf(1), 1)
        assertTrue(out.media.isEmpty())
    }

    @Test
    fun `no camera and no microphone declines, naming both`() = runTest {
        micCapturing = false
        val out = FakeOutput()
        val c = controller(FakeCamera(format = null), out)
        c.onStartStream(start())
        val reply = out.control.single() as MonitorToHub.StreamStopped
        assertTrue(reply.reason, reply.reason.contains("camera") && reply.reason.contains("microphone"))
        assertTrue(reply.reason.length <= 128)
        assertNull(c.activeStreamId)
    }

    @Test
    fun `an audio-only request with no microphone declines`() = runTest {
        micCapturing = false
        val out = FakeOutput()
        val c = controller(FakeCamera(), out)
        c.onStartStream(start(video = false))
        assertTrue((out.control.single() as MonitorToHub.StreamStopped).reason.contains("microphone"))
    }

    // Final review I3: CameraX's bind returns before the camera has opened.
    // One that then fails (in use, policy, background access refused) never
    // produces a frame, and the parent would wait on "Live" forever.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a camera that never delivers a frame falls back to sound only, and is stopped`() = runTest {
        val camera = FakeCamera(deliversFirstFrame = false); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        assertEquals(FIRST_FRAME_TIMEOUT_MS, currentTime)
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
        assertEquals(1, camera.stops)
        assertFalse(camera.running)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a camera that reports an error while opening falls back without waiting`() = runTest {
        val camera = FakeCamera(deliversFirstFrame = false, errorWhileOpening = "CAMERA_IN_USE"); val out = FakeOutput()
        val c = controller(camera, out)
        c.onStartStream(start())
        assertEquals(0L, currentTime)
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
        assertEquals(1, camera.stops)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a first frame that arrives within the window keeps video`() = runTest {
        val camera = FakeCamera(deliversFirstFrame = false); val out = FakeOutput()
        val c = controller(camera, out)
        val job = launch { c.onStartStream(start()) }
        advanceTimeBy(FIRST_FRAME_TIMEOUT_MS - 1)
        camera.emit(byteArrayOf(1))
        job.join()
        assertEquals(listOf(MonitorToHub.StreamStarted(4, VideoFormat(640, 480, 10.0, 90), AudioFormat(16_000, 1))), out.control)
        assertTrue(camera.running)
        assertTrue("the proving frame is not sent before stream_started", out.media.isEmpty())
    }

    // A start that hangs (the camera provider never arrives) held the
    // controller's lock with no reply to the hub. It is abandoned in time for
    // the parent, which gives up at START_TIMEOUT_MS = 10 s.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a camera start that hangs is abandoned after the start timeout`() = runTest {
        val camera = SuspendingCamera(); val out = FakeOutput()
        val c = controller(camera, out)
        val job = backgroundScope.launch { c.onStartStream(start()) }
        advanceTimeBy(VIDEO_START_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue("start still hanging", job.isCompleted)
        assertEquals(listOf(MonitorToHub.StreamStarted(4, null, AudioFormat(16_000, 1))), out.control)
        assertTrue(camera.stopped)
        assertTrue(VIDEO_START_TIMEOUT_MS + FIRST_FRAME_TIMEOUT_MS < 10_000)
    }

    // The capture loop is not behind the controller's lock. While a start is
    // still waiting for the camera, audio windows must not go out stamped with
    // a stream the hub has not been told about.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `audio captured while the camera is still starting is not sent`() = runTest {
        val camera = FakeCamera(deliversFirstFrame = false); val out = FakeOutput()
        val c = controller(camera, out)
        val job = launch { c.onStartStream(start()) }
        advanceTimeBy(1_000)
        c.onAudioWindow(shortArrayOf(1), 1)
        assertTrue(out.media.isEmpty())

        camera.emit(byteArrayOf(1))
        job.join()
        c.onAudioWindow(shortArrayOf(1), 1)
        assertEquals(MediaKind.PCM16, out.media.single().kind)
    }
}
