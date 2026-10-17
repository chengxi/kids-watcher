package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.store.HubCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for android.util.Log, which ManualPairing uses so that a pairing
// that fails on someone else's network can be read back with `adb logcat`.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ManualPairingTest {

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    private class FakeApi {
        var pairedCode: String? = null
        var expectRole: String? = null
        var deviceName: String? = null
        var baseUrl: String? = null
        var fail: HubApiException? = null

        fun credentials() = HubCredentials(
            baseUrl = "https://192.168.1.100:8444",
            wsUrl = "wss://192.168.1.100:8444",
            fingerprint = "",
            deviceId = "device-1",
            token = "token-1",
        )
    }

    private fun pairing(
        scope: TestScope,
        api: FakeApi,
        probeResult: ProbeResult = ProbeResult.Found(fp),
        saved: MutableList<HubCredentials> = mutableListOf(),
    ) = ManualPairing(
        scope = scope,
        role = "parent",
        deviceName = "Pixel 8 Pro",
        save = { saved.add(it) },
        probe = { _, _ -> probeResult },
        pairCall = { code, expectRole, baseUrl, _, name ->
            api.fail?.let { throw it }
            api.pairedCode = code
            api.expectRole = expectRole
            api.baseUrl = baseUrl
            api.deviceName = name
            api.credentials()
        },
    )

    @Test
    fun `asks for confirmation before sending the code`() = runTest(StandardTestDispatcher()) {
        val api = FakeApi()
        val manual = pairing(this, api)

        manual.submit("192.168.1.100", "8444", "123456")
        advanceUntilIdle()

        assertEquals(ManualPairingState.Confirm("D8:52:26"), manual.state.value)
        assertNull("the code must not be sent before the user confirms", api.pairedCode)
    }

    @Test
    fun `a mismatch sends nothing and leaves the code unused`() = runTest(StandardTestDispatcher()) {
        val api = FakeApi()
        val manual = pairing(this, api)
        manual.submit("192.168.1.100", "8444", "123456")
        advanceUntilIdle()

        manual.confirmMismatch()
        advanceUntilIdle()

        assertNull(api.pairedCode)
        val state = manual.state.value
        assertTrue("expected Failed, got $state", state is ManualPairingState.Failed)
    }

    @Test
    fun `a match pairs and stores credentials with the probed fingerprint`() =
        runTest(StandardTestDispatcher()) {
            val api = FakeApi()
            val saved = mutableListOf<HubCredentials>()
            val manual = pairing(this, api, saved = saved)
            manual.submit("192.168.1.100", "8444", "123 456")
            advanceUntilIdle()

            manual.confirmMatch()
            advanceUntilIdle()

            assertEquals("123456", api.pairedCode)
            assertEquals("parent", api.expectRole)
            assertEquals("Pixel 8 Pro", api.deviceName)
            assertEquals("https://192.168.1.100:8444", api.baseUrl)
            assertEquals(ManualPairingState.Paired("device-1"), manual.state.value)
            assertEquals(1, saved.size)
        }

    @Test
    fun `an unreachable address fails before any confirmation`() =
        runTest(StandardTestDispatcher()) {
            val api = FakeApi()
            val manual = pairing(this, api, probeResult = ProbeResult.Unreachable("timeout"))

            manual.submit("192.168.1.99", "8444", "123456")
            advanceUntilIdle()

            val state = manual.state.value
            assertTrue("expected Failed, got $state", state is ManualPairingState.Failed)
            assertNull(api.pairedCode)
        }

    @Test
    fun `a hub refusal is shown in the hub's own words`() = runTest(StandardTestDispatcher()) {
        val api = FakeApi().apply {
            fail = HubApiException(409, "that code belongs to the other phone")
        }
        val manual = pairing(this, api)
        manual.submit("192.168.1.100", "8444", "123456")
        advanceUntilIdle()

        manual.confirmMatch()
        advanceUntilIdle()

        val state = manual.state.value as ManualPairingState.Failed
        assertTrue(state.message.contains("other phone"))
    }

    @Test
    fun `validates the form before probing anything`() {
        assertTrue(validateManualEntry("192.168.1.100", "8444", "123456") is FormResult.Valid)
        assertEquals(
            "123456",
            (validateManualEntry("192.168.1.100", "8444", " 123 456 ") as FormResult.Valid).code,
        )
        assertTrue(validateManualEntry("", "8444", "123456") is FormResult.Invalid)
        assertTrue(validateManualEntry("has space", "8444", "123456") is FormResult.Invalid)
        assertTrue(validateManualEntry("192.168.1.100", "0", "123456") is FormResult.Invalid)
        assertTrue(validateManualEntry("192.168.1.100", "70000", "123456") is FormResult.Invalid)
        assertTrue(validateManualEntry("192.168.1.100", "8444", "12345") is FormResult.Invalid)
        assertTrue(validateManualEntry("192.168.1.100", "8444", "12345A") is FormResult.Invalid)
    }
}
