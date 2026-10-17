package com.kidsmonitor.parent.service

import com.kidsmonitor.shared.net.ConnectionState

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.kidsmonitor.parent.MainActivity
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.shared.protocol.EventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifications {

    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_MONITOR_LOST = "monitor_lost"
    const val CHANNEL_MONITOR_ONLINE = "monitor_online"
    const val CHANNEL_SERVICE = "service"
    const val ONGOING_ID = 1

    /**
     * Whether Android will actually honour the monitor-lost channel's
     * `setBypassDnd(true)`.
     *
     * `setBypassDnd` is silently ignored unless the app holds Do Not Disturb
     * policy access, which the user grants in Settings -- it is not a runtime
     * permission and cannot be requested with a dialog. Without it the channel
     * reports `mBypassDnd=false` on the device even though the code asked for
     * true, and the one alert that means nobody is watching the child is
     * muted by Do Not Disturb.
     */
    fun isDndBypassGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Settings screen where the user grants that access. There is no dialog. */
    fun dndAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        // A channel's bypassDnd cannot be changed after creation. If the app
        // created the channel before the user granted policy access -- the
        // normal order of events -- the channel is stuck at bypassDnd=false
        // until it is deleted and rebuilt. Do that once access appears.
        val existingLost = manager.getNotificationChannel(CHANNEL_MONITOR_LOST)
        if (existingLost != null &&
            !existingLost.canBypassDnd() &&
            isDndBypassGranted(context)
        ) {
            manager.deleteNotificationChannel(CHANNEL_MONITOR_LOST)
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Noise and motion alerts", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Something happened in the monitored room."
                enableVibration(true)
            },
        )

        // A monitor that has stopped reporting means nobody is watching the
        // child at all. That is strictly more urgent than any noise alert, so
        // it gets alarm audio and bypasses Do Not Disturb. Separate channel so
        // silencing 3am false alarms does not silence this too.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR_LOST, "Monitor offline", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The monitor stopped reporting. Nobody is watching the room."
                enableVibration(true)
                setBypassDnd(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )

        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_MONITOR_ONLINE, "Monitor back online", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "The monitor connection recovered."; setShowBadge(false) })

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Connection status", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The permanent notice shown while the app is listening for alerts."
                setShowBadge(false)
            },
        )
    }

    fun channelFor(type: EventType): String =
        when (type) {
            EventType.MONITOR_LOST -> CHANNEL_MONITOR_LOST
            EventType.MONITOR_ONLINE -> CHANNEL_MONITOR_ONLINE
            else -> CHANNEL_ALERTS
        }

    fun titleFor(type: EventType, monitorName: String): String = when (type) {
        EventType.LOUD_NOISE -> "Noise in $monitorName"
        EventType.MOTION -> "Movement in $monitorName"
        EventType.MONITOR_STARTED -> "$monitorName is now monitoring"
        EventType.MONITOR_STOPPED -> "$monitorName stopped monitoring"
        EventType.MONITOR_LOST -> "$monitorName is offline"
        EventType.MONITOR_ONLINE -> "$monitorName is back online"
    }

    fun bodyFor(event: EventEntity): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.tsMs))
        return when (event.type) {
            EventType.MONITOR_LOST ->
                "$time · no report for 45 seconds. Check the monitor phone."
            else -> {
                val peak = event.peakDb?.let { " · peak %.1f dB".format(it) }.orEmpty()
                "$time$peak"
            }
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun buildOngoing(context: Context, connected: Boolean): Notification = buildOngoing(
        context, if (connected) ConnectionState.Connected else ConnectionState.Reconnecting,
    )

    fun buildOngoing(context: Context, connection: ConnectionState): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(when (connection) {
                ConnectionState.Connected -> "Listening for alerts"
                ConnectionState.Connecting -> "Connecting to the hub"
                ConnectionState.Reconnecting -> "Reconnecting to the hub"
                is ConnectionState.WaitingToRetry -> "Waiting to reconnect"
                ConnectionState.Stopped -> "Alert connection stopped"
            })
            .setContentText(
                when (connection) {
                    ConnectionState.Connected -> "Connected to the hub."
                    ConnectionState.Connecting -> "Establishing the alert connection."
                    ConnectionState.Reconnecting -> "Restoring the connection — live alerts may be delayed."
                    is ConnectionState.WaitingToRetry -> "Retrying automatically in up to ${connection.delayMs / 1000} seconds — live alerts may be delayed."
                    ConnectionState.Stopped -> "Open the app to resume alerts."
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    fun notifyEvent(context: Context, event: EventEntity, monitorName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (event.type == EventType.MONITOR_ONLINE) {
            manager.activeNotifications.filter {
                it.notification.channelId == CHANNEL_MONITOR_LOST &&
                    it.notification.extras.getString("monitorId") == event.monitorId
            }.forEach { manager.cancel(it.id) }
        }
        val notification = NotificationCompat.Builder(context, channelFor(event.type))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titleFor(event.type, monitorName))
            .setContentText(bodyFor(event))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setCategory(
                if (event.type == EventType.MONITOR_LOST) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_EVENT,
            )
            .addExtras(android.os.Bundle().apply { putString("monitorId", event.monitorId) })
            .setSilent(event.type == EventType.MONITOR_ONLINE)
            .setPriority(if (event.type == EventType.MONITOR_ONLINE) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
        // Event id as the notification id: re-delivering the same event updates
        // one notification rather than stacking duplicates.
        manager.notify(event.id.toInt(), notification)
    }
}
