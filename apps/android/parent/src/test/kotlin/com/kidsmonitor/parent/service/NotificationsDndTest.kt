package com.kidsmonitor.parent.service

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationsDndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    @Test
    fun `reports that DND bypass is not granted by default`() {
        assertFalse(Notifications.isDndBypassGranted(context))
    }

    @Test
    fun `reports that DND bypass is granted once policy access exists`() {
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        assertTrue(Notifications.isDndBypassGranted(context))
    }

    @Test
    fun `points at the settings screen where the user grants access`() {
        assertEquals(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            Notifications.dndAccessSettingsIntent().action,
        )
    }

    // A test for "createChannels rebuilds the channel once access is granted"
    // cannot be written here: Robolectric ignores the policy-access gate, so
    // the precondition (a channel stuck at bypassDnd=false) is unreachable.
    // That path is verified on a device instead.
}
