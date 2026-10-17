package com.kidsmonitor.shared.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {

    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"
    private val notify = "android.permission.POST_NOTIFICATIONS"
    private val lan = RuntimePermissions.ACCESS_LOCAL_NETWORK

    @Test
    fun `the monitor on Android 17 needs all four, in one request`() {
        assertEquals(
            listOf(camera, mic, notify, lan),
            RuntimePermissions.missing(RuntimePermissions.MONITOR, sdkInt = 37) { false },
        )
    }

    @Test
    fun `the parent on Android 17 needs camera, notifications and the local network`() {
        assertEquals(
            listOf(camera, notify, lan),
            RuntimePermissions.missing(RuntimePermissions.PARENT, sdkInt = 37) { false },
        )
    }

    // The permission does not exist below SDK 37; asking for it there is
    // refused outright rather than granted.
    @Test
    fun `the local network permission is not requested below Android 17`() {
        assertEquals(
            listOf(camera, mic, notify),
            RuntimePermissions.missing(RuntimePermissions.MONITOR, sdkInt = 36) { false },
        )
    }

    @Test
    fun `notifications are not a runtime permission below Android 13`() {
        assertEquals(
            listOf(camera, mic),
            RuntimePermissions.missing(RuntimePermissions.MONITOR, sdkInt = 32) { false },
        )
    }

    @Test
    fun `already granted permissions are not asked for again`() {
        assertEquals(
            listOf(lan),
            RuntimePermissions.missing(RuntimePermissions.PARENT, sdkInt = 37) { it != lan },
        )
    }

    @Test
    fun `nothing is requested when everything is granted`() {
        assertEquals(
            emptyList<String>(),
            RuntimePermissions.missing(RuntimePermissions.MONITOR, sdkInt = 37) { true },
        )
    }

    @Test
    fun `the typed pairing path asks for the local network but never the camera`() {
        // Found on hardware: a screen that asks for nothing cannot reach a hub
        // on Android 17, because ACCESS_LOCAL_NETWORK is denied by default.
        val parent = RuntimePermissions.missing(RuntimePermissions.PARENT_TYPED, sdkInt = 37) { false }
        assertTrue(parent.contains(lan))
        assertFalse(parent.any { it.endsWith("CAMERA") })

        val monitor = RuntimePermissions.missing(RuntimePermissions.MONITOR_TYPED, sdkInt = 37) { false }
        assertTrue(monitor.contains(lan))
        assertFalse(monitor.any { it.endsWith("CAMERA") })
        // The monitor still needs its microphone: that is the product.
        assertTrue(monitor.any { it.endsWith("RECORD_AUDIO") })
    }
}
