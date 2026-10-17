package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.EventType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HubApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HubApi

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    private fun baseUrl() = server.url("/").toString().trimEnd('/')
    private fun unauthenticated() = HubApi(OkHttpClient(), baseUrl(), null)

    @Test
    fun `HTTP times out even when the websocket client has no read timeout`() {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(100, TimeUnit.MILLISECONDS).build()
        val boundedApi = HubApi(client, baseUrl(), "tok")
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("{\"monitors\":[]}").build())
        val error = assertThrows(HubApiException::class.java) { runBlocking { boundedApi.monitors() } }
        assertNull(error.statusCode)
        assertEquals(0, client.readTimeoutMillis)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `canceling status polling cancels its HTTP call`() = runBlocking {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val cancellableApi = HubApi(client, baseUrl(), "tok")
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("{\"monitors\":[]}").build())
        val request = async(Dispatchers.IO) { cancellableApi.monitors() }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        request.cancel()
        withTimeout(1_000) { request.join() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HubApi(OkHttpClient(), baseUrl(), token = "test-token")
    }

    @After
    fun tearDown() = server.close()

    private fun payload(code: String = "GHXF47G6") = parsePairingPayload(
        """{"v":1,"host":"192.168.1.100","port":8444,"fingerprint":"$fp","code":"$code","role":"parent"}""",
    )

    @Test
    fun `pair posts the code and device name and returns credentials`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(201)
                .body("""{"deviceId":"dev-1","token":"tok-1"}""").build(),
        )
        val creds = unauthenticated().pair(payload(), "Mum's Pixel")

        assertEquals("dev-1", creds.deviceId)
        assertEquals("tok-1", creds.token)
        assertEquals(fp, creds.fingerprint)
        // The URLs stored must come from the QR, not from whatever answered.
        assertEquals("https://192.168.1.100:8444", creds.baseUrl)
        assertEquals("wss://192.168.1.100:8444", creds.wsUrl)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pair", request.target)
        val body = request.body!!.utf8()
        assertTrue(body.contains(""""code":"GHXF47G6""""))
        assertTrue(body.contains(""""deviceName":"Mum's Pixel""""))
    }

    @Test
    fun `pair maps a used code to a readable error`() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"invalid_code"}""").build())
        val e = assertThrows(HubApiException::class.java) {
            runBlocking { unauthenticated().pair(payload(), "Phone") }
        }
        assertEquals(403, e.statusCode)
        assertTrue(e.message!!.contains("expired") || e.message!!.contains("used"))
    }

    @Test
    fun `events sends the bearer token and the since cursor`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[]}""").build())
        api.events(since = 42)
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertTrue(request.target!!.contains("since=42"))
    }

    @Test
    fun `events parses the hub payload`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"events":[
                     {"id":1,"monitorId":"m1","type":"LOUD_NOISE","tsMs":1757000000000,
                      "metadata":{"peakDb":-9.5,"durationMs":2200}},
                     {"id":2,"monitorId":"m1","type":"MONITOR_LOST","tsMs":1757000060000,"metadata":{}}
                   ]}""",
            ).build(),
        )
        val events = api.events(since = 0)
        assertEquals(2, events.size)
        assertEquals(EventType.LOUD_NOISE, events[0].type)
        assertEquals(-9.5, events[0].metadata.peakDb!!, 0.001)
        assertEquals(EventType.MONITOR_LOST, events[1].type)
        assertNull(events[1].metadata.peakDb)
    }

    @Test
    fun `events maps 401 to a revoked-credentials error`() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        val e = assertThrows(HubApiException::class.java) {
            runBlocking { api.events(since = 0) }
        }
        assertEquals(401, e.statusCode)
        assertTrue(e.message!!.contains("revoked") || e.message!!.contains("authorized"))
    }

    @Test
    fun `an unreachable hub reports no status code rather than throwing raw IO`() {
        server.close()
        val e = assertThrows(HubApiException::class.java) {
            runBlocking { api.events(since = 0) }
        }
        assertNull(e.statusCode)
        assertTrue(e.message!!.contains("reach"))
    }

    @Test
    fun `monitors parses liveness`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"monitors":[{"id":"m1","name":"Nursery","online":true,
                   "lastSeenMs":1757000000000,"batteryPct":88,"charging":true,"batteryTemperatureC":32.5,"speakerVolumePct":0}]}""",
            ).build(),
        )
        val monitors = api.monitors()
        assertEquals(1, monitors.size)
        assertTrue(monitors[0].online)
        assertEquals(88, monitors[0].batteryPct)
        assertEquals(true, monitors[0].charging)
        assertEquals(32.5, monitors[0].batteryTemperatureC)
        assertEquals(0, monitors[0].speakerVolumePct)
    }

    @Test
    fun `monitors tolerates a null battery and last seen`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"monitors":[{"id":"m1","name":"Nursery","online":false,
                   "lastSeenMs":null,"batteryPct":null}]}""",
            ).build(),
        )
        val monitors = api.monitors()
        assertNull(monitors[0].batteryPct)
        assertNull(monitors[0].lastSeenMs)
    }
    @Test
    fun `pairs with a typed code, declaring the role it expects`() = runTest {
        server.enqueue(MockResponse(code = 201, body = """{"deviceId":"d1","token":"t1"}"""))

        val stored = unauthenticated().pair(
            code = "123456",
            deviceName = "Pixel 8 Pro",
            expectRole = "parent",
            baseUrl = "https://192.168.1.100:8444",
            wsUrl = "wss://192.168.1.100:8444",
            fingerprint = fp,
        )

        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent.contains("\"code\":\"123456\""))
        assertTrue(sent.contains("\"expectRole\":\"parent\""))
        assertEquals("wss://192.168.1.100:8444", stored.wsUrl)
        assertEquals(fp, stored.fingerprint)
        assertEquals("t1", stored.token)
    }

    @Test
    fun `explains a role mismatch in the words of the person holding the phone`() = runTest {
        server.enqueue(
            MockResponse(code = 409, body = """{"error":"role_mismatch","role":"monitor"}"""),
        )

        val thrown = assertThrows(HubApiException::class.java) {
            runBlocking {
                unauthenticated().pair("123456", "Pixel 8 Pro", "parent", "https://h:1", "wss://h:1", fp)
            }
        }
        assertTrue(thrown.message!!.contains("other phone"))
    }

    @Test
    fun `explains a lockout rather than saying the code was wrong`() = runTest {
        server.enqueue(
            MockResponse(code = 429, body = """{"error":"too_many_attempts","retryAfterMs":60000}"""),
        )

        val thrown = assertThrows(HubApiException::class.java) {
            runBlocking {
                unauthenticated().pair("123456", "Pixel 8 Pro", "parent", "https://h:1", "wss://h:1", fp)
            }
        }
        assertTrue(thrown.message!!.contains("too many"))
    }
}
