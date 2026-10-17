package com.kidsmonitor.monitor.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typing a code must never cost a camera permission. Robolectric cannot answer
 * that question, so this reads the screen's source, the way PermissionRequestTest
 * does for the scanning path.
 */
class ManualPairingScreenTest {

    private val source = File(
        "src/main/kotlin/com/kidsmonitor/monitor/ui/ManualPairingScreen.kt",
    ).readText()

    @Test
    fun `the manual screen never asks for the camera`() {
        assertTrue(
            "the typed path must not request the camera",
            !source.contains("Manifest.permission.CAMERA") && !source.contains("CAMERA"),
        )
    }

    @Test
    fun `the manual screen asks for the local network in one request`() {
        // Hardware finding: a screen that asks for nothing cannot reach a hub
        // on Android 17, and a second launcher would be silently dropped.
        assertEquals(
            "exactly one permission launcher",
            1,
            Regex("rememberLauncherForActivityResult\\s*\\(").findAll(source).count(),
        )
        assertTrue(source.contains("RuntimePermissions.MONITOR_TYPED"))
    }

    @Test
    fun `the monitor pairs as a monitor`() {
        // A monitor app that sends expectRole="parent" would redeem the wrong
        // code and then fail on the socket instead of at pairing.
        assertTrue(source.contains("role = \"monitor\""))
    }

    @Test
    fun `the manual screen shows the confirmation step`() {
        assertTrue(source.contains("ManualPairingState.Confirm"))
        assertTrue(source.contains("confirmMatch"))
        assertTrue(source.contains("confirmMismatch"))
    }
}
