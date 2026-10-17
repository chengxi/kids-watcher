package com.kidsmonitor.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kidsmonitor.monitor.MainActivity

object MonitorNotifications {

    const val CHANNEL_CAPTURE = "capture"
    const val ONGOING_ID = 1

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                "Monitoring status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown the whole time this phone is listening to the room."
                setShowBadge(false)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The permanent capture notice.
     *
     * Android requires it for a camera/microphone foreground service, and for a
     * phone pointed at a child that is a feature rather than a cost: anyone who
     * picks it up can see it is listening (spec §3.1), and, while [watching],
     * that the camera is live too.
     *
     * [listening] is deliberately separate from [connected]: a running service
     * whose microphone failed to open must not claim the room is monitored.
     */
    fun buildOngoing(context: Context, connected: Boolean, listening: Boolean, watching: Boolean = false): Notification {
        val title = if (listening) "Listening to this room" else "Not listening"
        val status = when {
            listening && connected -> "Connected to the hub. Alerts are being delivered."
            listening && !connected -> "Microphone open, but not connected — alerts are not reaching anyone."
            !listening && connected -> "Connected, but the microphone is not open."
            else -> "Not listening and not connected. Open the app to start."
        }
        val text = if (watching) "A parent is watching live. $status" else status
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
