package com.kidsmonitor.monitor.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.store.Credentials
import com.kidsmonitor.monitor.store.MonitorName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitorPairingViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var credentials: Credentials
    private lateinit var viewModel: MonitorPairingViewModel
    private lateinit var monitorName: MonitorName

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    private fun qr(role: String = "monitor", code: String = "GHXF47G6") =
        """{"v":1,"host":"192.168.1.100","port":8444,"fingerprint":"$fp","code":"$code","role":"$role"}"""

    /**
     * Pairing does real network work on Dispatchers.IO, which the test
     * scheduler does not control -- advanceUntilIdle() returns before it
     * finishes. And withTimeout inside runTest counts *virtual* time, so it
     * must be wrapped in a real dispatcher or it expires instantly.
     */
    private suspend fun awaitTerminal(): MonitorPairingState =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                viewModel.state.first {
                    it is MonitorPairingState.Paired || it is MonitorPairingState.Failed
                }
            }
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        client = OkHttpClient()
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        credentials = Credentials.withPreferences {
            context.getSharedPreferences(Credentials.PREFS_NAME, Context.MODE_PRIVATE)
        }
        credentials.clear()
        context.getSharedPreferences("monitor_ui", Context.MODE_PRIVATE).edit().clear().commit()
        monitorName = MonitorName(context)
        viewModel = MonitorPairingViewModel(credentials, saveDeviceName = monitorName::save) {
            HubApi(client, server.url("/").toString().trimEnd('/'), null)
        }
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `starts idle`() = runTest {
        assertTrue(viewModel.state.value is MonitorPairingState.Idle)
    }

    @Test
    fun `a successful scan pairs and stores credentials`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(201).body("""{"deviceId":"dev-1","token":"tok-1"}""").build(),
        )
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        assertTrue(awaitTerminal() is MonitorPairingState.Paired)

        val stored = credentials.load()!!
        assertEquals("dev-1", stored.deviceId)
        assertEquals(fp, stored.fingerprint)
        // Stored URLs come from the QR, not from whatever server answered.
        assertEquals("https://192.168.1.100:8444", stored.baseUrl)
        assertEquals("wss://192.168.1.100:8444", stored.wsUrl)
    }

    @Test
    fun `the chosen name is trimmed sent to the hub and retained after relaunch`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("""{"deviceId":"dev-1","token":"tok-1"}""").build())
        viewModel.onQrScanned(qr(), "  Upstairs nursery  ")
        assertTrue(awaitTerminal() is MonitorPairingState.Paired)
        val request = server.takeRequest()
        assertTrue(request.body!!.utf8().contains("\"deviceName\":\"Upstairs nursery\""))
        assertEquals("Upstairs nursery", MonitorName(ApplicationProvider.getApplicationContext<Context>()).load())
    }

    @Test
    fun `invalid names never consume a pairing code`() = runTest {
        for (name in listOf("", "  ", "x".repeat(65))) {
            viewModel.onQrScanned(qr(), name)
            assertTrue(viewModel.state.value is MonitorPairingState.Failed)
            assertEquals(0, server.requestCount)
            assertNull(credentials.load())
            viewModel.reset()
        }
    }

    @Test
    fun `a parent code is rejected in the monitor app`() {
        // The mirror of the check in :parent. Codes are role-bound on the hub,
        // but failing here gives a readable message instead of a 403.
        runTest {
            viewModel.onQrScanned(qr(role = "parent"), "Nursery Pixel")
            val state = awaitTerminal()
            assertTrue(state is MonitorPairingState.Failed)
            assertTrue((state as MonitorPairingState.Failed).message.contains("parent"))
            assertNull(credentials.load())
        }
    }

    @Test
    fun `an unrelated QR code fails without crashing`() = runTest {
        viewModel.onQrScanned("https://example.com/some-other-qr", "Nursery Pixel")
        assertTrue(awaitTerminal() is MonitorPairingState.Failed)
        assertNull(credentials.load())
    }

    @Test
    fun `an expired code surfaces the hub's reason`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"invalid_code"}""").build())
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        val state = awaitTerminal() as MonitorPairingState.Failed
        assertTrue(state.message.contains("expired") || state.message.contains("used"))
        assertNull(credentials.load())
    }

    @Test
    fun `an unreachable hub fails rather than hanging`() = runTest {
        server.close()
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        assertTrue(awaitTerminal() is MonitorPairingState.Failed)
    }

    @Test
    fun `reset returns to idle so the user can retry`() = runTest {
        viewModel.onQrScanned("garbage", "Nursery Pixel")
        awaitTerminal()
        viewModel.reset()
        assertTrue(viewModel.state.value is MonitorPairingState.Idle)
    }

    @Test
    fun `a repeat scan while already connecting is ignored`() = runTest {
        // The camera decodes the same QR many times a second. Without this
        // guard every frame redeems the code again, and the second attempt
        // fails with 403 and overwrites a success that already happened.
        server.enqueue(
            MockResponse.Builder().code(201).body("""{"deviceId":"dev-1","token":"tok-1"}""").build(),
        )
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        viewModel.onQrScanned(qr(), "Nursery Pixel")
        assertTrue(awaitTerminal() is MonitorPairingState.Paired)
        assertEquals("only one pairing request should reach the hub", 1, server.requestCount)
    }
}
