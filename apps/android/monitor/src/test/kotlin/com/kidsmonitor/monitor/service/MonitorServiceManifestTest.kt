package com.kidsmonitor.monitor.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guard rail. Every assertion here corresponds to a platform trap that has
 * already cost real time somewhere in this project.
 */
@RunWith(RobolectricTestRunner::class)
class MonitorServiceManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun serviceInfo(): ServiceInfo = context.packageManager.getServiceInfo(
        ComponentName(context, MonitorService::class.java), PackageManager.GET_META_DATA,
    )

    private fun declared(): List<String> = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions!!.toList()

    @Test
    fun `the service is typed both camera and microphone`() {
        val type = serviceInfo().foregroundServiceType
        assertEquals(
            "microphone is what lets capture continue through a locked screen",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        assertEquals(
            "camera is declared now so on-demand video in M4 needs no manifest change",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    @Test
    fun `the service is NOT typed dataSync`() {
        assertEquals(
            "dataSync is capped at 6h per 24h on Android 15+ and stops silently",
            0,
            serviceInfo().foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @Test
    fun `declares the foreground service permissions its types require`() {
        assertTrue(declared().contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(declared().contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(declared().contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
    }

    @Test
    fun `declares RECORD_AUDIO, without which capture cannot start`() {
        assertTrue(declared().contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `declares ACCESS_LOCAL_NETWORK, without which nothing reaches the hub`() {
        // Android 17 blocks LAN traffic without it, and blocked connections
        // time out rather than erroring - indistinguishable from the hub being
        // down. This cost an hour in M3.
        assertTrue(declared().contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }

    @Test
    fun `the service is not exported`() {
        assertEquals(false, serviceInfo().exported)
    }

    @Test
    fun `declares no BOOT_COMPLETED receiver`() {
        // Android 15+ throws ForegroundServiceStartNotAllowedException when a
        // boot receiver starts a camera/microphone FGS (spec 3.2). A receiver
        // here would crash on every reboot. The parent side reports the gap as
        // MONITOR_LOST instead.
        val receivers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers.orEmpty().map { it.name }
        assertTrue(
            "a boot receiver cannot legally start this service: $receivers",
            receivers.none { it.contains("Boot", ignoreCase = true) },
        )
    }
}
