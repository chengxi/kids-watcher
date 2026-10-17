package com.kidsmonitor.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsmonitor.monitor.store.monitorNameError
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.HubApiException
import com.kidsmonitor.shared.net.HubTrust
import com.kidsmonitor.shared.net.PairingPayload
import com.kidsmonitor.shared.net.PairingPayloadException
import com.kidsmonitor.shared.net.parsePairingPayload
import com.kidsmonitor.shared.store.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pairing is the step most likely to fail on someone else's network, and it
 * fails on a phone the developer is not holding. Everything on this path logs
 * under one tag so `adb logcat -s KidsMonitor` explains what happened.
 *
 * Never logs the pairing code or the device token.
 */
private const val TAG = "KidsMonitor"

sealed interface MonitorPairingState {
    data object Idle : MonitorPairingState
    data object Connecting : MonitorPairingState
    data class Paired(val deviceId: String) : MonitorPairingState
    data class Failed(val message: String) : MonitorPairingState
}

class MonitorPairingViewModel(
    private val credentials: Credentials,
    private val saveDeviceName: (String) -> Unit = {},
    private val apiFactory: (PairingPayload) -> HubApi = { payload ->
        HubApi(HubTrust.clientFor(payload.fingerprint), payload.baseUrl, token = null)
    },
) : ViewModel() {

    private val mutableState = MutableStateFlow<MonitorPairingState>(MonitorPairingState.Idle)
    val state: StateFlow<MonitorPairingState> = mutableState.asStateFlow()

    fun onQrScanned(raw: String, deviceName: String) {
        // The camera decodes the same QR many times a second. Without this
        // guard every frame would redeem the code again, and the second attempt
        // would fail with 403 and overwrite a success that already happened.
        if (mutableState.value is MonitorPairingState.Connecting) return
        if (mutableState.value is MonitorPairingState.Paired) return

        monitorNameError(deviceName)?.let {
            mutableState.value = MonitorPairingState.Failed(it)
            return
        }
        val name = deviceName.trim()

        // Not necessarily a QR any more: debug builds can be handed the
        // same payload over adb. The log should not name a source it
        // cannot actually tell apart.
        Log.i(TAG, "pairing payload received: ${raw.length} chars")

        val payload = try {
            parsePairingPayload(raw).requireRole("monitor")
        } catch (e: PairingPayloadException) {
            Log.w(TAG, "pairing payload rejected: ${e.message}")
            mutableState.value = MonitorPairingState.Failed(
                e.message ?: "that QR code is not usable",
            )
            return
        }

        Log.i(TAG, "payload accepted, contacting ${payload.baseUrl}")
        mutableState.value = MonitorPairingState.Connecting
        viewModelScope.launch {
            try {
                val stored = apiFactory(payload).pair(payload, name)
                saveDeviceName(name)
                credentials.save(stored)
                Log.i(TAG, "paired as device ${stored.deviceId}")
                mutableState.value = MonitorPairingState.Paired(stored.deviceId)
            } catch (e: HubApiException) {
                Log.w(TAG, "hub refused pairing (status ${e.statusCode}): ${e.message}")
                mutableState.value = MonitorPairingState.Failed(e.message ?: "pairing failed")
            } catch (e: Exception) {
                Log.w(TAG, "could not reach the hub while pairing", e)
                mutableState.value = MonitorPairingState.Failed(
                    "could not reach the hub: ${e.message}",
                )
            }
        }
    }

    fun reset() {
        mutableState.value = MonitorPairingState.Idle
    }
}
