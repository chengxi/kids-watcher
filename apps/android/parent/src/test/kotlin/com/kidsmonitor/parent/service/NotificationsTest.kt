package com.kidsmonitor.parent.service

import com.kidsmonitor.shared.net.ConnectionState

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.shared.protocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private fun event(type: EventType, peakDb: Double? = -9.5) = EventEntity(
        id = 1, monitorId = "m1", type = type, tsMs = 1757000000000,
        peakDb = peakDb, durationMs = 2200,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        Notifications.createChannels(context)
    }

    @Test
    fun `creates event and service channels`() {
        val ids = manager.notificationChannels.map { it.id }
        assertTrue(ids.contains(Notifications.CHANNEL_ALERTS))
        assertTrue(ids.contains(Notifications.CHANNEL_MONITOR_LOST))
        assertTrue(ids.contains(Notifications.CHANNEL_SERVICE))
        assertTrue(ids.contains(Notifications.CHANNEL_MONITOR_ONLINE))
    }

    @Test
    fun `the monitor-lost channel asks to bypass do not disturb`() {
        // NOTE: this asserts the request, not the effect. Robolectric does not
        // model the Do Not Disturb policy-access gate -- canBypassDnd() returns
        // true here even when access is denied, whereas a real device reports
        // mBypassDnd=false. It still earns its place: it fails if someone
        // removes the setBypassDnd call. Whether Android honours it is verified
        // on-device only (see docs/planning/progress-m3.md).
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_MONITOR_LOST)
        assertTrue("the setBypassDnd request must be made", channel.canBypassDnd())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `the ordinary alert channel does not bypass do not disturb`() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_ALERTS)
        assertFalse(channel.canBypassDnd())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `the service channel is low importance so the ongoing notice does not buzz`() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_SERVICE)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `noise and lost alerts land on different channels`() {
        // Same channel would mean silencing 3am false alarms also silences the
        // one alert that means nobody is watching the child.
        assertNotEquals(
            Notifications.channelFor(EventType.LOUD_NOISE),
            Notifications.channelFor(EventType.MONITOR_LOST),
        )
        assertEquals(Notifications.CHANNEL_MONITOR_LOST, Notifications.channelFor(EventType.MONITOR_LOST))
    }

    @Test
    fun `posted notifications name the source device for every event type`() {
        for ((index, type) in EventType.entries.withIndex()) {
            val id = 100L + index
            Notifications.notifyEvent(context, event(type).copy(id = id), "Pixel 6")
            val notification = manager.activeNotifications.first { it.id == id.toInt() }.notification
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Pixel 6"))
        }
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 200, monitorId = "m2"), "Pixel 3")
        val other = manager.activeNotifications.first { it.id == 200 }.notification
        assertEquals("Noise in Pixel 3", other.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `the monitor-lost title says the monitor stopped, not that a noise happened`() {
        val title = Notifications.titleFor(EventType.MONITOR_LOST, "Nursery").lowercase()
        assertTrue(title.contains("offline") || title.contains("stopped") || title.contains("lost"))
    }

    @Test
    fun `the body reports the peak level when there is one`() {
        assertTrue(Notifications.bodyFor(event(EventType.LOUD_NOISE)).contains("-9.5"))
    }

    @Test
    fun `the body omits alert duration`() {
        assertFalse(Notifications.bodyFor(event(EventType.LOUD_NOISE)).contains("2.2s"))
    }

    @Test
    fun `the body omits the level when there is none`() {
        assertFalse(Notifications.bodyFor(event(EventType.MONITOR_LOST, peakDb = null)).contains("dB"))
    }

    @Test
    fun `the ongoing notification says whether the socket is connected`() {
        val connected = Notifications.buildOngoing(context, connected = true)
        val disconnected = Notifications.buildOngoing(context, connected = false)
        val a = connected.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val b = disconnected.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertNotEquals(a, b)
        assertTrue(b.lowercase().contains("restoring"))
    }

    @Test
    fun `ongoing notification distinguishes starting from retry backoff`() {
        val starting = Notifications.buildOngoing(context, ConnectionState.Connecting)
        val waiting = Notifications.buildOngoing(context, ConnectionState.WaitingToRetry(8000, "closed"))
        assertEquals("Connecting to the hub", starting.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Waiting to reconnect", waiting.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(waiting.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("8 seconds"))
    }

    @Test
    fun `the ongoing notification is not dismissable`() {
        val n = Notifications.buildOngoing(context, connected = true)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }
    @Test
    fun `recovery is quiet and dismisses only that monitors offline notification`() {
        Notifications.notifyEvent(context, event(EventType.MONITOR_LOST).copy(id = 10), "Nursery")
        Notifications.notifyEvent(context, event(EventType.MONITOR_LOST).copy(id = 11, monitorId = "m2"), "Playroom")
        Notifications.notifyEvent(context, event(EventType.MONITOR_ONLINE).copy(id = 12), "Nursery")
        assertEquals(setOf(11, 12), manager.activeNotifications.map { it.id }.toSet())
        val recovery = manager.activeNotifications.first { it.id == 12 }.notification
        assertEquals(Notifications.CHANNEL_MONITOR_ONLINE, recovery.channelId)
        assertEquals("Nursery is back online", recovery.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(recovery.channelId).importance)
        assertEquals(null, recovery.sound)
        assertEquals(null, recovery.vibrate)
    }

}
