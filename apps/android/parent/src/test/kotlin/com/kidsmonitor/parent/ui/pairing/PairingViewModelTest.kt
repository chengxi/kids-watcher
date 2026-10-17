package com.kidsmonitor.parent.ui.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.store.Credentials
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
class PairingViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var credentials: Credentials
    private lateinit var viewModel: PairingViewModel

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    /**
     * Pairing does real network work on Dispatchers.IO, which the test
     * scheduler does not control -- advanceUntilIdle() returns before it
     * finishes. Wait for the view model to actually reach a terminal state.
     */
    private suspend fun awaitTerminal(): PairingState =
        // withContext(Default) because inside runTest, withTimeout counts
        // *virtual* time and would expire instantly against real network work.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                viewModel.state.first { it is PairingState.Paired || it is PairingState.Failed }
            }
        }

    private fun qr(role: String = "parent", code: String = "GHXF47G6") =
        """{"v":1,"host":"192.168.1.100","port":8444,"fingerprint":"$fp","code":"$code","role":"$role"}"""

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
        // Point the API at MockWebServer while keeping the QR's own values for
        // the fingerprint and the URLs that get stored.
        viewModel = PairingViewModel(credentials) {
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
        assertTrue(viewModel.state.value is PairingState.Idle)
    }

    @Test
    fun `a successful scan pairs and stores credentials`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(201).body("""{"deviceId":"dev-1","token":"tok-1"}""").build(),
        )
        viewModel.onQrScanned(qr(), "Mum's Pixel")
        assertTrue(awaitTerminal() is PairingState.Paired)
        val stored = credentials.load()!!
        assertEquals("dev-1", stored.deviceId)
        assertEquals(fp, stored.fingerprint)
        // Stored URLs come from the QR, not from whatever server answered.
        assertEquals("https://192.168.1.100:8444", stored.baseUrl)
        assertEquals("wss://192.168.1.100:8444", stored.wsUrl)
    }

    @Test
    fun `a monitor code is rejected with a message naming the problem`() = runTest {
        viewModel.onQrScanned(qr(role = "monitor"), "Phone")
        val state = awaitTerminal()
        assertTrue(state is PairingState.Failed)
        assertTrue((state as PairingState.Failed).message.contains("monitor"))
        assertNull("nothing should be stored on failure", credentials.load())
    }

    @Test
    fun `an unrelated QR code fails without crashing`() = runTest {
        viewModel.onQrScanned("https://example.com/some-other-qr", "Phone")
        assertTrue(awaitTerminal() is PairingState.Failed)
        assertNull(credentials.load())
    }

    @Test
    fun `an expired code surfaces the hub's reason`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"invalid_code"}""").build())
        viewModel.onQrScanned(qr(), "Phone")
        val state = awaitTerminal() as PairingState.Failed
        assertTrue(state.message.contains("expired") || state.message.contains("used"))
        assertNull(credentials.load())
    }

    @Test
    fun `an unreachable hub fails rather than hanging`() = runTest {
        server.close()
        viewModel.onQrScanned(qr(), "Phone")
        assertTrue(awaitTerminal() is PairingState.Failed)
    }

    @Test
    fun `reset returns to idle so the user can retry`() = runTest {
        viewModel.onQrScanned("garbage", "Phone")
        awaitTerminal()
        viewModel.reset()
        assertTrue(viewModel.state.value is PairingState.Idle)
    }

    @Test
    fun `a repeat scan while already connecting is ignored`() = runTest {
        // The camera fires continuously: the same QR is decoded many times a
        // second. Without this guard every frame would redeem the code again,
        // and the second attempt would fail with 403 and overwrite the success.
        server.enqueue(
            MockResponse.Builder().code(201).body("""{"deviceId":"dev-1","token":"tok-1"}""").build(),
        )
        viewModel.onQrScanned(qr(), "Phone")
        viewModel.onQrScanned(qr(), "Phone")
        viewModel.onQrScanned(qr(), "Phone")
        assertTrue(awaitTerminal() is PairingState.Paired)
        assertEquals("only one pairing request should reach the hub", 1, server.requestCount)
    }
}
