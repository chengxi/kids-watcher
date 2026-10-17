package com.kidsmonitor.parent.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.store.Credentials
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Plain preferences: no Android Keystore under Robolectric.
        AlertService.credentialsFactory = { ctx ->
            Credentials.withPreferences {
                ctx.getSharedPreferences(Credentials.PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
        AlertService.credentialsFactory(context).clear()
    }

    @After
    fun tearDown() {
        AlertService.credentialsFactory = { Credentials(it) }
    }

    @Test
    fun `posts its ongoing notification as soon as it starts`() {
        val controller = Robolectric.buildService(AlertService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(
            "the foreground notification must appear immediately, before any network work",
            manager.activeNotifications.firstOrNull { it.id == Notifications.ONGOING_ID },
        )
        controller.destroy()
    }

    @Test
    fun `creates its notification channels on create`() {
        val controller = Robolectric.buildService(AlertService::class.java).create()
        val manager = context.getSystemService(NotificationManager::class.java)
        val ids = manager.notificationChannels.map { it.id }
        assertTrue(ids.contains(Notifications.CHANNEL_SERVICE))
        assertTrue(ids.contains(Notifications.CHANNEL_MONITOR_LOST))
        controller.destroy()
    }

    @Test
    fun `stops itself when the device is not paired rather than looping`() {
        // Nothing to connect to: there is no hub address without credentials.
        // Sitting in a reconnect loop against an address we do not have would
        // burn battery and show a permanently red banner.
        val controller = Robolectric.buildService(AlertService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        assertTrue("unpaired service should stop itself", shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `reports disconnected before any socket exists`() {
        val controller = Robolectric.buildService(AlertService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        assertTrue(!ServiceState.connected.value)
        controller.destroy()
    }
}
