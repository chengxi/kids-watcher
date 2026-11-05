package com.kidsmonitor.parent.service

import com.kidsmonitor.shared.net.ConnectionState

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.NotificationCompat
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

    private fun eventIdentity(id: Long) = "kids-monitor:event:$id" to 0

    private fun activeEvent(id: Long) = manager.activeNotifications.single { (it.tag to it.id) == eventIdentity(id) }

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
            val notification = activeEvent(id).notification
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Pixel 6"))
        }
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 200, monitorId = "m2"), "Pixel 3")
        val other = activeEvent(200).notification
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
    fun `event id one coexists with the ongoing notice across a status refresh`() {
        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = true))

        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 1), "Nursery")

        fun identities() = manager.activeNotifications.map { it.tag to it.id }.toSet()
        val expected = setOf(null to Notifications.ONGOING_ID, eventIdentity(1))
        val posted = manager.activeNotifications
        assertEquals(expected, identities())
        assertEquals(2, posted.size)
        assertTrue(posted.any { it.notification.channelId == Notifications.CHANNEL_SERVICE })
        assertTrue(posted.any { it.notification.channelId == Notifications.CHANNEL_ALERTS })

        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = false))

        val refreshed = manager.activeNotifications
        assertEquals(2, refreshed.size)
        assertEquals(expected, identities())
        assertTrue(refreshed.any { it.notification.channelId == Notifications.CHANNEL_SERVICE })
        assertTrue(refreshed.any { it.notification.channelId == Notifications.CHANNEL_ALERTS })
    }

    @Test
    fun `recovery is quiet and dismisses only that monitors offline notification`() {
        Notifications.notifyEvent(context, event(EventType.MONITOR_LOST).copy(id = 10), "Nursery")
        Notifications.notifyEvent(context, event(EventType.MONITOR_LOST).copy(id = 11, monitorId = "m2"), "Playroom")
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 13), "Nursery")
        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = true))
        val recoveryEvent = event(EventType.MONITOR_ONLINE).copy(id = 12)
        Notifications.notifyEvent(context, recoveryEvent, "Nursery")
        val expected = setOf(eventIdentity(11), eventIdentity(12), eventIdentity(13), null to Notifications.ONGOING_ID)
        assertEquals(expected, manager.activeNotifications.map { it.tag to it.id }.toSet())
        val recovery = activeEvent(12).notification
        assertEquals(Notifications.CHANNEL_MONITOR_ONLINE, recovery.channelId)
        assertEquals("Nursery is back online", recovery.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(recovery.channelId).importance)
        assertEquals(null, recovery.sound)
        assertEquals(null, recovery.vibrate)

        Notifications.notifyEvent(context, recoveryEvent, "Nursery")
        assertEquals(expected, manager.activeNotifications.map { it.tag to it.id }.toSet())
    }

    @Test
    fun `redelivering an event updates one tagged notification and preserves the ongoing notice`() {
        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = true))
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 1), "Nursery")
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE, peakDb = -3.2).copy(id = 1), "Nursery")

        assertEquals(setOf(null to Notifications.ONGOING_ID, eventIdentity(1)), manager.activeNotifications.map { it.tag to it.id }.toSet())
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(activeEvent(1).notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("-3.2"))
    }

    @Test
    fun `full Long event ids remain distinct when low 32 bits match`() {
        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = true))
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 1L), "Nursery")
        Notifications.notifyEvent(context, event(EventType.MOTION).copy(id = 4294967297L), "Nursery")

        assertEquals(setOf(null to Notifications.ONGOING_ID, eventIdentity(1L), eventIdentity(4294967297L)), manager.activeNotifications.map { it.tag to it.id }.toSet())
        assertEquals(3, manager.activeNotifications.size)
    }

    @Test
    fun `recovery clears a legacy untagged matching offline notice`() {
        val legacyOffline = NotificationCompat.Builder(context, Notifications.CHANNEL_MONITOR_LOST)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .addExtras(android.os.Bundle().apply { putString("monitorId", "m1") })
            .build()
        manager.notify(10, legacyOffline)
        Notifications.notifyEvent(context, event(EventType.MONITOR_LOST).copy(id = 11, monitorId = "m2"), "Playroom")
        Notifications.notifyEvent(context, event(EventType.LOUD_NOISE).copy(id = 13, monitorId = "m1"), "Nursery")
        manager.notify(Notifications.ONGOING_ID, Notifications.buildOngoing(context, connected = true))

        Notifications.notifyEvent(context, event(EventType.MONITOR_ONLINE).copy(id = 12, monitorId = "m1"), "Nursery")

        assertEquals(setOf(null to 1, eventIdentity(11), eventIdentity(12), eventIdentity(13)), manager.activeNotifications.map { it.tag to it.id }.toSet())
    }

}
