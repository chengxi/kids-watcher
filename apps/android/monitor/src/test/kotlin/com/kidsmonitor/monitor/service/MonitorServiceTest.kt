package com.kidsmonitor.monitor.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.store.Credentials
import com.kidsmonitor.monitor.audio.PcmSource
import com.kidsmonitor.monitor.stream.StreamController
import com.kidsmonitor.monitor.stream.StreamOutput
import com.kidsmonitor.monitor.stream.VideoSource
import com.kidsmonitor.monitor.stream.TalkReceiver
import com.kidsmonitor.monitor.stream.TalkSpeaker
import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MonitorToHub
import com.kidsmonitor.shared.protocol.VideoFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MonitorServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var originalCaptureSourceFactory: (Int) -> PcmSource

    @Before
    fun setUp() {
        originalCaptureSourceFactory = MonitorService.captureSourceFactory
        // Robolectric has no Android Keystore, so the encrypted store cannot be
        // constructed here. Same seam as :parent's AlertService.
        MonitorService.credentialsFactory = { ctx ->
            Credentials.withPreferences {
                ctx.getSharedPreferences(Credentials.PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
        MonitorService.credentialsFactory(context).clear()
        MonitorState.reset()
    }

    @After
    fun tearDown() {
        MonitorService.credentialsFactory = { Credentials(it) }
        MonitorService.captureSourceFactory = originalCaptureSourceFactory
    }

    @Test
    fun `posts its permanent capture notice immediately`() {
        // Anyone who picks up the phone must be able to see it is listening,
        // and a service that misses the startForeground window is killed.
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(
            manager.activeNotifications.firstOrNull { it.id == MonitorNotifications.ONGOING_ID },
        )
        controller.destroy()
    }

    @Test
    fun `creates its capture channel on create`() {
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.notificationChannels.map { it.id }.contains(MonitorNotifications.CHANNEL_CAPTURE))
        controller.destroy()
    }

    @Test
    fun `stops itself when unpaired rather than looping`() {
        // There is no hub address without credentials. Looping against one we
        // do not have would burn battery on a phone meant to run all night.
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `reports not listening before capture starts`() {
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        assertFalse(MonitorState.listening.value)
        assertFalse(MonitorState.connected.value)
        controller.destroy()
    }

    @Test
    fun `the ongoing notice says whether it is actually listening`() {
        // "Service running" is not the same as "microphone open". If capture
        // failed, the notice must not claim the room is being monitored.
        val listening = MonitorNotifications.buildOngoing(context, connected = true, listening = true)
        val notListening = MonitorNotifications.buildOngoing(context, connected = true, listening = false)
        val a = listening.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val b = notListening.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("texts should differ: '$a' vs '$b'", a != b)
    }

    @Test
    fun `the ongoing notice says when a parent is watching`() {
        // Anyone near the monitor should be able to tell the camera is live, not
        // only that the microphone is (spec 3.1's reason for the notice).
        val idle = MonitorNotifications.buildOngoing(context, connected = true, listening = true, watching = false)
        val watched = MonitorNotifications.buildOngoing(context, connected = true, listening = true, watching = true)
        val a = idle.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val b = watched.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("should mention watching: '$b'", b.contains("watching"))
        assertFalse(a.contains("watching"))
    }

    @Test
    fun `the ongoing notice is not dismissable`() {
        // The permanent capture indicator is a feature: anyone who picks up the
        // phone can see it is recording (spec 3.1).
        val n = MonitorNotifications.buildOngoing(context, connected = true, listening = true)
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `terminal session failure clears listening and stops service once before safe destruction`() = runBlocking {
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        val service = controller.get()
        val source = FailingSource()
        var stopRequests = 0
        service.stopSelfAction = { stopRequests++; service.stopSelf() }
        service.runCaptureSessionForTest(source)
        service.captureFailed() // Duplicate/stale delivery is ignored.
        assertFalse(MonitorState.listening.value)
        assertFalse(MonitorState.calibrating.value)
        assertFalse(service.audioCaptureAvailable)
        assertEquals(1, stopRequests)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, source.starts)
        assertEquals(1, source.reads)
        assertEquals(1, source.stops)
        assertEquals(1, source.releases)
        controller.destroy()
        assertEquals(1, source.releases)
        service.captureFailed() // A late callback after destruction is ignored.
        assertEquals(1, stopRequests)
    }

    @Test
    fun `capture failure service teardown stops active stream and talk`() = runBlocking {
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        val service = controller.get()
        val video = FakeVideoSource()
        val output = object : StreamOutput {
            override fun sendControl(message: MonitorToHub) = Unit
            override fun sendMedia(frame: MediaFrame): Boolean = true
        }
        val stream = StreamController(video, output, 16_000, audioAvailable = { true })
        service.stream = stream
        val speaker = FakeSpeaker()
        service.talkReceiver = TalkReceiver({ stream.activeStreamId }, { speaker }, {})
        stream.onStartStream(HubToMonitor.StartStream(10, video = true, audio = true))
        service.talkReceiver?.start(HubToMonitor.StartTalk(10, 20))
        assertEquals(10, stream.activeStreamId)

        service.runCaptureSessionForTest(FailingSource())
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
        assertEquals(1, speaker.releases)
        assertEquals(1, video.stops)
    }

    private class FailingSource : PcmSource {
        var starts = 0
        var reads = 0
        var stops = 0
        var releases = 0
        override fun start() { starts++ }
        override fun read(buffer: ShortArray): Int { reads++; return -6 }
        override fun stop() { stops++ }
        override fun release() { releases++ }
    }

    private class FakeVideoSource : VideoSource {
        var stops = 0
        override suspend fun start(onJpeg: (ByteArray, Int) -> Unit, onError: (String) -> Unit): VideoFormat? {
            val format = VideoFormat(640, 480, 10.0, rotationDegrees = 0)
            onJpeg(byteArrayOf(1), 0)
            return format
        }
        override suspend fun stop() { stops++ }
    }

    private class FakeSpeaker : TalkSpeaker {
        var releases = 0
        override fun offer(pcm: ByteArray) = Unit
        override fun release() { releases++ }
    }
}
