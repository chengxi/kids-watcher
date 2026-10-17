package com.kidsmonitor.shared.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialsTest {

    private lateinit var credentials: Credentials

    private val sample = HubCredentials(
        baseUrl = "https://192.168.1.100:8444",
        wsUrl = "wss://192.168.1.100:8444",
        fingerprint = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
            "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3",
        deviceId = "9d6ee388-8426-435e-8b34-337a426ac2d5",
        token = "QsPQvYnZsbgdKp7xN2mL4vR8sT1uW3yZ5aB6cD7eF8g",
    )

    @Before
    fun setUp() {
        // Plain preferences, not encrypted: Robolectric has no Android Keystore.
        // Same class, same logic -- only the cipher layer differs, and that is
        // verified on a real device in the on-device pass.
        val context = ApplicationProvider.getApplicationContext<Context>()
        credentials = Credentials.withPreferences {
            context.getSharedPreferences(Credentials.PREFS_NAME, Context.MODE_PRIVATE)
        }
        credentials.clear()
    }

    @Test
    fun `reports unpaired before anything is saved`() {
        assertFalse(credentials.isPaired)
        assertNull(credentials.load())
    }

    @Test
    fun `round-trips credentials`() {
        credentials.save(sample)
        assertTrue(credentials.isPaired)
        assertEquals(sample, credentials.load())
    }

    @Test
    fun `clear removes everything`() {
        credentials.save(sample)
        credentials.clear()
        assertFalse(credentials.isPaired)
        assertNull(credentials.load())
    }

    @Test
    fun `overwrites on re-pair rather than accumulating`() {
        credentials.save(sample)
        credentials.save(sample.copy(deviceId = "other", token = "other-token"))
        assertEquals("other", credentials.load()!!.deviceId)
    }
}
