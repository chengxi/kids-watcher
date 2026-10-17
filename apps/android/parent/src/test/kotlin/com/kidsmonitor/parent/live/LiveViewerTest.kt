package com.kidsmonitor.parent.live

import com.kidsmonitor.shared.net.MediaFeed
import com.kidsmonitor.shared.protocol.CameraFacing
import com.kidsmonitor.shared.protocol.AudioFormat
import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewerTest {

    private class FakeGateway : StreamGateway {
        val requested = mutableListOf<String>()
        val stopped = mutableListOf<Int>()
        override fun requestStream(monitorId: String) { requested += monitorId }
        override fun stopStream(streamId: Int) { stopped += streamId }
    }

    private class FakeSink(val format: AudioFormat) : AudioSink {
        val offered = mutableListOf<ByteArray>()
        var releases = 0
        override fun offer(pcm: ByteArray) { offered += pcm }
        override fun release() { releases++ }
    }

    private val video = VideoFormat(640, 480, 10.0)
    private val audio = AudioFormat(16_000, 1)

    private class Rig(
        val sessions: MutableStateFlow<LiveSession?>,
        val media: MediaFeed,
        val sinks: MutableList<FakeSink>,
        val viewer: LiveViewer<String>,
    ) {
        var openAttempts = 0
    }

    private fun TestScope.rig(
        session: LiveSession? = null,
        monitorId: String = "m1",
        sessions: MutableStateFlow<LiveSession?> = MutableStateFlow(session),
        openAudio: ((AudioFormat) -> AudioSink)? = null,
        decode: (ByteArray, VideoFormat?) -> String? = { bytes, _ -> String(bytes) },
    ): Rig {
        val media = MediaFeed()
        val sinks = mutableListOf<FakeSink>()
        lateinit var rig: Rig
        val viewer = LiveViewer(
            monitorId = monitorId,
            sessions = sessions,
            video = media.video,
            audio = media.audio,
            scope = backgroundScope,
            workDispatcher = StandardTestDispatcher(testScheduler),
            openAudio = { format ->
                rig.openAttempts++
                openAudio?.invoke(format) ?: FakeSink(format).also { sinks += it }
            },
            releaseAudio = { it.release() },
            decode = decode,
        )
        rig = Rig(sessions, media, sinks, viewer)
        return rig
    }

    private fun ready(streamId: Int) =
        HubToParent.StreamReady(streamId = streamId, monitorId = "m1", video = video, audio = audio)
    private fun pcm(streamId: Int) = MediaFrame(MediaKind.PCM16, streamId, 0, ByteArray(640))
    private fun jpeg(streamId: Int, text: String) = MediaFrame(MediaKind.JPEG, streamId, 0, text.toByteArray())

    // Review finding 1: the screen can start before AlertService attaches its
    // session (process death restoring the Watch screen, or a service
    // recreate). The viewer must start the session when it appears, not sit
    // on "Connecting…" forever.
    @Test
    fun `a session that appears after start is started`() = runTest {
        val r = rig()
        r.viewer.start()
        runCurrent()
        assertTrue(r.viewer.state.value is LiveState.WaitingForHub)

        val gateway = FakeGateway()
        r.sessions.value = LiveSession(gateway, backgroundScope)
        runCurrent()

        assertEquals(listOf("m1"), gateway.requested)
        assertEquals(LiveState.Requesting("m1"), r.viewer.state.value)
    }

    @Test
    fun `a replacement session is started while watching, but not after leaving`() = runTest {
        val first = FakeGateway()
        val r = rig(LiveSession(first, backgroundScope))
        r.viewer.start()
        runCurrent()

        val second = FakeGateway()
        r.sessions.value = LiveSession(second, backgroundScope)
        runCurrent()
        assertEquals(listOf("m1"), second.requested)

        r.viewer.stop()
        val third = FakeGateway()
        r.sessions.value = LiveSession(third, backgroundScope)
        runCurrent()
        assertEquals(emptyList<String>(), third.requested)
    }

    // Review finding 2: a PCM frame already in flight when stop() runs must not
    // open a new AudioTrack that nothing will ever release.
    @Test
    fun `audio arriving after stop opens no player`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()
        // No runCurrent between: the derived state still reads Playing, exactly
        // as it does for a frame already being handled on another thread.
        r.viewer.stop()
        assertTrue(r.viewer.state.value is LiveState.Playing)

        r.viewer.onMedia(pcm(7))

        assertEquals(0, r.sinks.size)
    }

    @Test
    fun `stop releases the player`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()
        r.viewer.onMedia(pcm(7))
        assertEquals(1, r.sinks.size)

        r.viewer.stop()
        assertEquals(1, r.sinks.single().releases)
    }

    // Review finding 3: a picture that finishes decoding after stop() must not
    // reappear on a screen that was just cleared.
    @Test
    fun `a picture decoded after stop is dropped`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        lateinit var viewer: LiveViewer<String>
        val r = rig(session, decode = { bytes, _ -> viewer.stop(); String(bytes) })
        viewer = r.viewer
        viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()

        r.media.tryEmit(jpeg(7, "late"))
        runCurrent()

        assertNull(viewer.frame.value)
    }

    @Test
    fun `a picture decoded while watching is shown`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()

        r.media.tryEmit(jpeg(7, "hello"))
        runCurrent()

        assertEquals("hello", r.viewer.frame.value)
    }

    // Review finding 4: each stream gets its own player, built from that
    // stream's format; the old one is released when its stream ends.
    @Test
    fun `the player is released when the stream ends and a new stream gets a new one`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()
        r.viewer.onMedia(pcm(7))

        session.onStreamEnded(HubToParent.StreamEnded(streamId = 7, reason = "gone"))
        runCurrent()
        assertEquals(1, r.sinks[0].releases)

        advanceTimeBy(2_000); runCurrent() // automatic retry
        session.onStreamReady(ready(8))
        runCurrent()
        r.viewer.onMedia(pcm(8))

        assertEquals(2, r.sinks.size)
        assertEquals(0, r.sinks[1].releases)
        assertTrue(r.sinks[1].offered.isNotEmpty())
    }

    // Final review I1: AudioTrack.Builder().build() and play() can throw. That
    // must not crash the process (it hosts AlertService), must not stop the
    // picture, and must not be retried fifty times a second for the same stream.
    @Test
    fun `a player that cannot be built leaves the picture playing and is not retried for that stream`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session, openAudio = { error("AudioTrack init failed") })
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()

        repeat(10) { r.viewer.onMedia(pcm(7)) }
        r.media.tryEmit(jpeg(7, "still here"))
        runCurrent()

        assertEquals(1, r.openAttempts)
        assertEquals("still here", r.viewer.frame.value)
    }

    @Test
    fun `a new stream tries the player again`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        var fail = true
        val r = rig(session, openAudio = { format -> if (fail) error("busy") else FakeSink(format) })
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()
        r.viewer.onMedia(pcm(7))

        session.onStreamEnded(HubToParent.StreamEnded(7, "gone"))
        advanceTimeBy(2_000); runCurrent()
        session.onStreamReady(ready(8))
        runCurrent()
        fail = false
        r.viewer.onMedia(pcm(8))

        assertEquals(2, r.openAttempts)
    }

    @Test
    fun `audio in a format no speaker plays opens no player`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(HubToParent.StreamReady(7, "m1", video, AudioFormat(sampleRate = 1, channels = 1)))
        runCurrent()
        r.viewer.onMedia(pcm(7))
        assertEquals(0, r.openAttempts)
    }

    // A decoder that throws on one bad picture must not end the decoding
    // coroutine (or the process): the next good picture is still shown.
    @Test
    fun `a picture that fails to decode does not stop later pictures`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session, decode = { bytes, _ ->
            val text = String(bytes)
            if (text == "bad") throw IllegalArgumentException("corrupt") else text
        })
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()

        r.media.tryEmit(jpeg(7, "bad"))
        runCurrent()
        r.media.tryEmit(jpeg(7, "good"))
        runCurrent()

        assertEquals("good", r.viewer.frame.value)
    }

    // The decoder needs the advertised size to refuse a picture claiming to be
    // far larger (LiveMediaLimits), so it is handed the stream's format.
    @Test
    fun `the decoder is given the stream's advertised format`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val seen = mutableListOf<VideoFormat?>()
        val r = rig(session, decode = { bytes, format -> seen += format; String(bytes) })
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()
        r.media.tryEmit(jpeg(7, "x"))
        runCurrent()
        assertEquals(listOf<VideoFormat?>(video), seen)
    }

    // Final review M5: leaving the screen for m1 after the viewer has opened
    // m2's screen must not stop m2's stream on the shared session.
    @Test
    fun `leaving one monitor's screen does not stop another monitor's stream`() = runTest {
        val gateway = FakeGateway()
        val session = LiveSession(gateway, backgroundScope)
        val sessions = MutableStateFlow<LiveSession?>(session)
        val first = rig(monitorId = "m1", sessions = sessions)
        val second = rig(monitorId = "m2", sessions = sessions)
        first.viewer.start()
        runCurrent()
        second.viewer.start() // replaces m1's request on the one session
        session.onStreamReady(HubToParent.StreamReady(9, "m2", video, audio))
        runCurrent()

        first.viewer.stop()

        assertEquals(emptyList<Int>(), gateway.stopped)
        assertEquals(LiveState.Playing("m2", 9, video, audio), session.state.value)
    }

    // Final review T7: pictures and sound travel on separate flows, so a
    // picture backlog cannot push out audio on the way to this viewer.
    @Test
    fun `audio and video arrive on their own flows`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7))
        runCurrent()

        r.media.tryEmit(pcm(7))
        r.media.tryEmit(jpeg(7, "pic"))
        runCurrent()

        assertEquals(1, r.sinks.single().offered.size)
        assertEquals("pic", r.viewer.frame.value)
    }
    @Test
    fun `undecodable JPEG bytes do not cancel the first picture timeout`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session, decode = { _, _ -> null })
        r.viewer.start(); session.onStreamReady(ready(7)); runCurrent()
        r.media.tryEmit(jpeg(7, "invalid")); runCurrent()
        advanceTimeBy(FIRST_PICTURE_TIMEOUT_MS + 1); runCurrent()
        assertEquals(LiveFailure.VIDEO_DELAY, (session.state.value as LiveState.Retrying).failure)
        assertNull(r.viewer.frame.value)
    }

    @Test
    fun `a decode finishing after disconnection cannot restore a stale picture`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session, decode = { _, _ -> session.onDisconnected(); "old picture" })
        r.viewer.start(); session.onStreamReady(ready(7)); runCurrent()
        r.media.tryEmit(jpeg(7, "old")); runCurrent()
        assertTrue(session.state.value is LiveState.WaitingForHub)
        assertNull(r.viewer.frame.value)
    }

    @Test
    fun `successfully decoded picture completes startup and cancels its deadline`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start(); session.onStreamReady(ready(7)); runCurrent()
        r.media.tryEmit(jpeg(7, "picture")); runCurrent()
        advanceTimeBy(FIRST_PICTURE_TIMEOUT_MS + 1); runCurrent()
        assertTrue(session.state.value is LiveState.Playing)
        assertEquals("picture", r.viewer.frame.value)
    }

    @Test
    fun `camera switch clears the old picture and keeps the same audio player`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session)
        r.viewer.start()
        session.onStreamReady(ready(7).copy(video = video.copy(facing = CameraFacing.BACK)))
        runCurrent()
        r.media.tryEmit(pcm(7)); r.media.tryEmit(jpeg(7, "back")); runCurrent()
        assertEquals("back", r.viewer.frame.value)
        session.onStreamReady(ready(7).copy(video = null)); runCurrent()
        assertNull(r.viewer.frame.value)
        r.media.tryEmit(pcm(7)); r.media.tryEmit(jpeg(7, "late back")); runCurrent()
        assertNull(r.viewer.frame.value)
        session.onStreamReady(ready(7).copy(video = video.copy(facing = CameraFacing.FRONT, rotationDegrees = 270)))
        runCurrent(); r.media.tryEmit(jpeg(7, "front")); runCurrent()
        assertEquals("front", r.viewer.frame.value)
        assertEquals(1, r.sinks.size); assertEquals(0, r.sinks.single().releases)
        assertEquals(2, r.sinks.single().offered.size)
        r.viewer.stop()
    }

    @Test
    fun `a decode completing during a lens change cannot restore the previous picture`() = runTest {
        val session = LiveSession(FakeGateway(), backgroundScope)
        val r = rig(session, decode = { _, _ -> session.onStreamReady(ready(7).copy(video = video.copy(facing = CameraFacing.FRONT))); "stale back" })
        r.viewer.start(); session.onStreamReady(ready(7).copy(video = video.copy(facing = CameraFacing.BACK))); runCurrent()
        r.media.tryEmit(jpeg(7, "back")); runCurrent()
        assertNull(r.viewer.frame.value)
        r.viewer.stop()
    }

}
