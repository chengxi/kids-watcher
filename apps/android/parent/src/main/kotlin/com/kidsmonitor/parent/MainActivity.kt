package com.kidsmonitor.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kidsmonitor.shared.ui.KidMonitorTheme
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import com.kidsmonitor.parent.service.Notifications
import com.kidsmonitor.shared.net.DebugPairing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Channels exist as soon as the app runs, not only once the service
        // starts, so their settings are visible to the user immediately.
        Notifications.createChannels(this)

        // Debug builds accept the pairing payload over adb so development does
        // not mean holding the phone up to a laptop screen after every
        // `pm clear`. Returns null in a release build - see DebugPairing.
        val debugPayload = DebugPairing.payloadFrom(
            intent?.getStringExtra(DebugPairing.EXTRA_PAYLOAD),
            debugBuild = BuildConfig.DEBUG,
        )

        setContent {
            KidMonitorTheme() { Surface { ParentApp(debugPairingPayload = debugPayload) } }
        }
    }
}
