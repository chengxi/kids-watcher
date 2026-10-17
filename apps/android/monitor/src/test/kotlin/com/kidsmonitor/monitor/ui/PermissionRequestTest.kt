package com.kidsmonitor.monitor.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.permissions.RuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Found on a Pixel 8 Pro during M5 Task 9. The pairing screen launched one
 * permission request after another in the same LaunchedEffect. Android shows
 * one permission dialog at a time and drops any request made while another is
 * open, logging only "Can request only one set of permissions at a time" - so
 * ACCESS_LOCAL_NETWORK was never asked for, and on Android 17 the hub
 * connection timed out with no error. Robolectric grants nothing and shows no
 * dialogs, so this cannot be caught by running the screen; it is caught by
 * construction instead.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionRequestTest {

    private val screen = File("src/main/kotlin/com/kidsmonitor/monitor/ui/MonitorPairingScreen.kt")

    @Test
    fun `the pairing screen has exactly one permission launcher`() {
        val launchers = Regex("""rememberLauncherForActivityResult\s*\(""").findAll(screen.readText()).count()
        assertEquals(
            "a second launcher is a second request that Android will silently drop",
            1,
            launchers,
        )
    }

    @Test
    fun `the one launcher requests several permissions at once`() {
        assertTrue(screen.readText().contains("RequestMultiplePermissions()"))
    }

    // An undeclared runtime permission is refused with no dialog at all - the
    // M3 CAMERA bug. Everything this app asks for must be in its manifest.
    @Test
    fun `every permission requested at runtime is declared in the manifest`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toSet()
        val requested = RuntimePermissions.missing(RuntimePermissions.MONITOR, sdkInt = 37) { false }
        assertEquals(emptySet<String>(), requested.toSet() - declared)
    }
}
