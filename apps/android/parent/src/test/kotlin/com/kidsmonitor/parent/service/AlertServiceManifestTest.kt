package com.kidsmonitor.parent.service

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
 * Guard rail. If anyone ever "simplifies" the service type back to dataSync,
 * this fails loudly with the reason.
 */
@RunWith(RobolectricTestRunner::class)
class AlertServiceManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun serviceInfo(): ServiceInfo = context.packageManager.getServiceInfo(
        ComponentName(context, AlertService::class.java), PackageManager.GET_META_DATA,
    )

    @Test
    fun `the service is typed specialUse`() {
        assertEquals(
            "specialUse is the only untimed type this app qualifies for: dataSync " +
                "is capped at 6h per 24h, and connectedDevice additionally requires " +
                "a Bluetooth/NFC/USB/wifi-control permission this app does not use. " +
                "See spec 3.4.",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            serviceInfo().foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    @Test
    fun `the service is NOT typed dataSync`() {
        assertEquals(
            "dataSync is time-limited on Android 15+; this service must run indefinitely",
            0,
            serviceInfo().foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @Test
    fun `declares the permissions the type requires`() {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertTrue(declared.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(declared.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(declared.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(declared.contains("android.permission.INTERNET"))
    }

    @Test
    fun `declares CAMERA for the pairing scanner`() {
        // Regression guard. CAMERA was missing from the manifest once, and the
        // symptom was invisible to the test suite and cryptic on the device:
        // requesting an undeclared runtime permission returns denied with no
        // dialog, so the button appears to do nothing at all.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertTrue(
            "CAMERA must be declared or QR pairing silently cannot work",
            declared.contains("android.permission.CAMERA"),
        )
    }

    @Test
    fun `declares ACCESS_LOCAL_NETWORK, without which nothing in this app works`() {
        // Android 17 gates local-network access behind a runtime permission for
        // apps targeting SDK 37+. Every connection this product makes is to a
        // hub on the home LAN, and without the permission they do not fail --
        // they time out, which reads as a network fault rather than a missing
        // permission. This cost an hour of diagnosis once.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertTrue(
            "ACCESS_LOCAL_NETWORK must be declared for Android 17+",
            declared.contains("android.permission.ACCESS_LOCAL_NETWORK"),
        )
    }

    @Test
    fun `the service is not exported`() {
        assertEquals(false, serviceInfo().exported)
    }
}
