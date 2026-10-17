package com.kidsmonitor.shared.net

import android.util.Log
import com.kidsmonitor.shared.protocol.PAIRING_CODE_DIGITS
import com.kidsmonitor.shared.store.HubCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "KidsMonitor"

// Hostname or IPv4 literal, the same shape the QR payload allows. No scheme,
// no path, no port: the port is its own field.
private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")
private val CODE_PATTERN = Regex("^\\d{$PAIRING_CODE_DIGITS}$")

sealed interface FormResult {
    data class Valid(val host: String, val port: Int, val code: String) : FormResult
    data class Invalid(val message: String) : FormResult
}

/** Pure, so the screen can show the same message the tests assert on. */
fun validateManualEntry(host: String, port: String, code: String): FormResult {
    val trimmedHost = host.trim()
    if (!HOST_PATTERN.matches(trimmedHost)) {
        return FormResult.Invalid("enter the hub's address, like 192.168.1.100")
    }
    val parsedPort = port.trim().toIntOrNull()
    if (parsedPort == null || parsedPort !in 1..65535) {
        return FormResult.Invalid("enter the hub's port, like 8444")
    }
    val digits = code.filterNot { it.isWhitespace() }
    if (!CODE_PATTERN.matches(digits)) {
        return FormResult.Invalid("the code is $PAIRING_CODE_DIGITS digits")
    }
    return FormResult.Valid(trimmedHost, parsedPort, digits)
}

sealed interface ManualPairingState {
    data object Entering : ManualPairingState
    data object Probing : ManualPairingState
    data class Confirm(val check: String) : ManualPairingState
    data object Pairing : ManualPairingState
    data class Paired(val deviceId: String) : ManualPairingState
    data class Failed(val message: String) : ManualPairingState
}

/**
 * Pairing by typing. The order is the point: the hub's certificate is shown and
 * confirmed BEFORE the code is sent, so a man in the middle never learns the
 * code and a wrong answer leaves it usable for a real retry.
 *
 * Never logs the code or the token.
 */
class ManualPairing(
    private val scope: CoroutineScope,
    private val role: String,
    private val deviceName: String,
    private val save: suspend (HubCredentials) -> Unit,
    private val probe: suspend (String, Int) -> ProbeResult = HubProbe::probe,
    // Five parameters, not four: the default implementation cannot close over
    // the constructor's own deviceName, so the caller passes it in.
    private val pairCall: suspend (
        code: String,
        expectRole: String,
        baseUrl: String,
        fingerprint: String,
        deviceName: String,
    ) -> HubCredentials = { code, expectRole, baseUrl, fingerprint, name ->
        HubApi(HubTrust.clientFor(fingerprint), baseUrl, token = null).pair(
            code = code,
            deviceName = name,
            expectRole = expectRole,
            baseUrl = baseUrl,
            wsUrl = baseUrl.replaceFirst("https://", "wss://"),
            fingerprint = fingerprint,
        )
    },
) {
    private val mutableState = MutableStateFlow<ManualPairingState>(ManualPairingState.Entering)
    val state: StateFlow<ManualPairingState> = mutableState.asStateFlow()

    private data class Pending(
        val host: String,
        val port: Int,
        val code: String,
        val fingerprint: String,
    )

    private var pending: Pending? = null

    fun submit(host: String, port: String, code: String) {
        when (val form = validateManualEntry(host, port, code)) {
            is FormResult.Invalid -> mutableState.value = ManualPairingState.Failed(form.message)

            is FormResult.Valid -> {
                mutableState.value = ManualPairingState.Probing
                scope.launch {
                    when (val result = probe(form.host, form.port)) {
                        is ProbeResult.Found -> {
                            pending = Pending(form.host, form.port, form.code, result.fingerprint)
                            Log.i(TAG, "hub found at ${form.host}:${form.port}, awaiting confirmation")
                            mutableState.value =
                                ManualPairingState.Confirm(FingerprintCheck.of(result.fingerprint))
                        }

                        is ProbeResult.Unreachable ->
                            fail("no hub answered at ${form.host}:${form.port}")

                        is ProbeResult.NotAHub ->
                            fail("something answered at ${form.host}:${form.port}, but it is not a hub")

                        is ProbeResult.TlsError ->
                            fail("could not start a secure connection: ${result.detail}")
                    }
                }
            }
        }
    }

    fun confirmMatch() {
        val target = pending ?: return
        mutableState.value = ManualPairingState.Pairing
        scope.launch {
            val baseUrl = "https://${target.host}:${target.port}"
            try {
                val stored = pairCall(target.code, role, baseUrl, target.fingerprint, deviceName)
                save(stored)
                pending = null
                Log.i(TAG, "paired as device ${stored.deviceId}")
                mutableState.value = ManualPairingState.Paired(stored.deviceId)
            } catch (e: HubApiException) {
                Log.w(TAG, "hub refused pairing (status ${e.statusCode})")
                fail(e.message ?: "the hub refused the code")
            } catch (e: Exception) {
                Log.w(TAG, "could not reach the hub while pairing", e)
                fail("could not reach the hub: ${e.message}")
            }
        }
    }

    fun confirmMismatch() {
        // Nothing was sent, so the code is still good on the real hub.
        pending = null
        Log.w(TAG, "fingerprint rejected by the user; nothing was sent")
        mutableState.value = ManualPairingState.Failed(
            "those characters did not match, so nothing was sent. Your code is still unused.",
        )
    }

    fun reset() {
        pending = null
        mutableState.value = ManualPairingState.Entering
    }

    private fun fail(message: String) {
        pending = null
        mutableState.value = ManualPairingState.Failed(message)
    }
}
