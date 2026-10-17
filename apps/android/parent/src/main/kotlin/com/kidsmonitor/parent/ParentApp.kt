package com.kidsmonitor.parent

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kidsmonitor.parent.data.EventRepository
import com.kidsmonitor.parent.data.ParentDatabase
import com.kidsmonitor.parent.data.NoiseHistoryRepository
import com.kidsmonitor.parent.service.AlertService
import com.kidsmonitor.parent.service.ServiceState
import com.kidsmonitor.parent.ui.alerts.AlertsScreen
import com.kidsmonitor.parent.ui.alerts.AlertsViewModel
import com.kidsmonitor.parent.ui.alerts.MonitorStatusStore
import com.kidsmonitor.parent.ui.alerts.NoiseHistoryStore
import com.kidsmonitor.parent.ui.alerts.refreshWhileVisible
import com.kidsmonitor.parent.ui.alerts.DndAccessBanner
import com.kidsmonitor.parent.ui.live.LiveViewScreen
import com.kidsmonitor.parent.ui.pairing.ManualPairingScreen
import com.kidsmonitor.parent.ui.pairing.PairingScreen
import com.kidsmonitor.parent.ui.pairing.PairingViewModel
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.HubTrust
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.store.Credentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

@Composable
fun ParentApp(debugPairingPayload: String? = null) {
    val context = LocalContext.current
    val credentials = remember { Credentials(context) }
    var paired by remember { mutableStateOf(credentials.isPaired) }

    LaunchedEffect(paired) {
        if (paired) AlertService.start(context)
    }

    if (!paired) {
        val viewModel = remember { PairingViewModel(credentials) }
        val deviceName = Build.MODEL ?: "Android phone"
        // Keyed on the payload so a second launch with a new code pairs again,
        // and a recomposition with the same one does not.
        LaunchedEffect(debugPairingPayload) {
            debugPairingPayload?.let { viewModel.onQrScanned(it, deviceName) }
        }
        var typing by rememberSaveable { mutableStateOf(false) }
        if (typing) {
            ManualPairingScreen(
                credentials = credentials,
                deviceName = deviceName,
                onPaired = { paired = true },
                onBack = { typing = false },
            )
        } else {
            PairingScreen(
                viewModel = viewModel,
                deviceName = deviceName,
                onPaired = { paired = true },
                onEnterCodeInstead = { typing = true },
            )
        }
        return
    }

    val stored = remember(paired) { credentials.load() }
    if (stored == null) {
        // Credentials vanished under us -- revoked and cleared, or storage
        // failed. Fall back to pairing rather than crashing on a null.
        paired = false
        return
    }

    // One HubApi for both the alert history and the monitor list.
    val api = remember(stored.deviceId) {
        HubApi(HubTrust.clientFor(stored.fingerprint), stored.baseUrl, stored.token)
    }
    val alertsViewModel: AlertsViewModel = viewModel(key = "events-${stored.deviceId}") {
        AlertsViewModel(
            EventRepository(ParentDatabase.open(context).events(), api),
            ServiceState.connection,
        )
    }

    val monitorStore = remember(api) { MonitorStatusStore { api.monitors() } }
    val monitorList by monitorStore.state.collectAsStateWithLifecycle()
    val connection by ServiceState.connection.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshRequest by remember { mutableStateOf(0) }
    // Composition survives locking a phone. Poll only while visible, and fetch
    // immediately on resume, reconnect and Refresh instead of waiting 15 s.
    LaunchedEffect(monitorStore, lifecycleOwner, refreshRequest) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            monitorStore.invalidate()
            AlertService.retry(context)
            val statusRefresh = Channel<Unit>(Channel.CONFLATED)
            launch {
                ServiceState.connection.collect { state ->
                    if (state == ConnectionState.Connected) statusRefresh.trySend(Unit)
                    else monitorStore.invalidate()
                }
            }
            launch {
                // Serialize polls but retain a reconnect request that arrives
                // during an older request. Its result cannot overwrite a gap.
                for (request in statusRefresh) {
                    if (ServiceState.connection.value == ConnectionState.Connected) monitorStore.refresh()
                }
            }
            launch {
                while (true) {
                    delay(15_000)
                    if (ServiceState.connection.value == ConnectionState.Connected) statusRefresh.trySend(Unit)
                }
            }
        }
    }

    var watchingId by rememberSaveable { mutableStateOf<String?>(null) }
    var watchingName by rememberSaveable { mutableStateOf("") }
    val alertsState by alertsViewModel.state.collectAsStateWithLifecycle()
    var chartMonitorId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedMonitor = chartMonitorId?.takeIf { id -> monitorList.monitors.any { it.id == id } }
        ?: monitorList.monitors.firstOrNull()?.id ?: alertsState.events.firstOrNull()?.monitorId
    val noiseStore = remember(api) { NoiseHistoryStore(NoiseHistoryRepository(ParentDatabase.open(context), api)) }
    val noiseHistory by noiseStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(noiseStore, selectedMonitor, lifecycleOwner, refreshRequest, watchingId) {
        if (watchingId != null || selectedMonitor == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            noiseStore.select(selectedMonitor)
            noiseStore.refreshWhileVisible(ServiceState.connection)
        }
    }

    val watching = watchingId
    if (watching != null) {
        LiveViewScreen(
            monitorId = watching,
            monitorName = watchingName,
            monitor = monitorList.monitors.find { it.id == watching },
            hubConnected = connection == ConnectionState.Connected && monitorList.current,
            onBack = { watchingId = null },
        )
        return
    }

    AlertsScreen(
        viewModel = alertsViewModel,
        dndBanner = { DndAccessBanner() },
        monitorList = monitorList,
        onRefresh = { refreshRequest++ },
        onWatch = { watchingId = it.id; watchingName = it.name },
        noiseHistory = noiseHistory,
        selectedMonitorId = selectedMonitor,
        hubAddress = stored.baseUrl,
        onSelectChartMonitor = { chartMonitorId = it },
    )
}
