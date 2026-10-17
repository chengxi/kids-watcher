package com.kidsmonitor.monitor.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.store.Credentials
import org.junit.After
import org.junit.Assert.assertFalse
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

    @Before
    fun setUp() {
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
}
