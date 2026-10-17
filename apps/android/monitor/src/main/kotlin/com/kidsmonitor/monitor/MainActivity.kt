package com.kidsmonitor.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kidsmonitor.shared.ui.KidMonitorTheme
import androidx.compose.material3.Surface
import com.kidsmonitor.monitor.service.MonitorNotifications
import com.kidsmonitor.monitor.ui.MonitorApp
import com.kidsmonitor.shared.net.DebugPairing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitorNotifications.createChannels(this)

        // Debug builds accept the pairing payload over adb so development does
        // not mean holding the phone up to a laptop screen after every
        // `pm clear`. Returns null in a release build - see DebugPairing.
        val debugPayload = DebugPairing.payloadFrom(
            intent?.getStringExtra(DebugPairing.EXTRA_PAYLOAD),
            debugBuild = BuildConfig.DEBUG,
        )

        setContent {
            KidMonitorTheme(dark = true) { Surface { MonitorApp(debugPairingPayload = debugPayload) } }
        }
    }
}
