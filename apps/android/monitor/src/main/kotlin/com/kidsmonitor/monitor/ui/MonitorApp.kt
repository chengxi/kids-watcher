package com.kidsmonitor.monitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kidsmonitor.monitor.service.MonitorService
import com.kidsmonitor.monitor.store.MonitorName
import com.kidsmonitor.monitor.store.defaultMonitorName
import com.kidsmonitor.shared.store.Credentials

@Composable
fun MonitorApp(debugPairingPayload: String? = null) {
    val context = LocalContext.current
    val credentials = remember { Credentials(context) }
    val monitorName = remember { MonitorName(context) }
    var paired by remember { mutableStateOf(credentials.isPaired) }

    if (!paired) {
        MonitorSetup(credentials, monitorName, debugPairingPayload, onPaired = { paired = true })
        return
    }

    // Deliberately NOT auto-started. Capture is a thing someone chooses to
    // begin, and the permanent notice that comes with it should never appear
    // without a deliberate act.
    MonitorStatusScreen(
        monitorName = monitorName.load(),
        hubAddress = credentials.load()?.baseUrl ?: "Home hub",
        onStart = { MonitorService.start(context) },
        onStop = { MonitorService.stop(context) },
    )
}

@Composable
internal fun MonitorSetup(
    credentials: Credentials,
    monitorName: MonitorName,
    debugPairingPayload: String?,
    defaultName: String = defaultMonitorName(),
    onPaired: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var nameConfirmed by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    if (!nameConfirmed) {
        MonitorNamingScreen(name, onNameChange = { name = it }, onContinue = {
            name = name.trim()
            nameConfirmed = true
        })
        return
    }

    val viewModel = remember { MonitorPairingViewModel(credentials, saveDeviceName = monitorName::save) }
    // Even an ADB payload waits for the user to choose a name. Do not redeem
    // a single-use code with Build.MODEL before this step has completed.
    LaunchedEffect(debugPairingPayload) {
        debugPairingPayload?.let { viewModel.onQrScanned(it, name) }
    }
    if (typing) {
        ManualPairingScreen(
            credentials = credentials,
            deviceName = name,
            onPaired = onPaired,
            onBack = { typing = false },
            onChangeName = { nameConfirmed = false },
        )
    } else {
        MonitorPairingScreen(
            viewModel = viewModel,
            deviceName = name,
            onPaired = onPaired,
            onEnterCodeInstead = { typing = true },
            onChangeName = { nameConfirmed = false },
            debugPairingPayload = debugPairingPayload,
        )
    }
}
