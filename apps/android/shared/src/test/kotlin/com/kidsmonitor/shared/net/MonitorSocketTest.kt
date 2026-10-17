package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.EventMetadata
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaFrames
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.MonitorToHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import com.kidsmonitor.shared.protocol.HEARTBEAT_INTERVAL_MS
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MonitorSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val serverInbox = LinkedBlockingQueue<String>()
    private val serverBinaryInbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var serverSocket: WebSocket? = null

    @Before
    fun setUp() {
        client = OkHttpClient()
        server = MockWebServer()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) = serverInbox.put(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                serverBinaryInbox.put(bytes.toByteArray())
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        server.start()
    }

    @After
    fun tearDown() {
        // MockWebServer will not shut down while OkHttp holds a connection, and
        // reports it from tearDown as if the test failed.
        serverSocket?.close(1000, null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }

    private fun newSocket(heartbeat: () -> MonitorToHub.Heartbeat = { MonitorToHub.Heartbeat(null, "unknown") }) = MonitorSocket(
        client = client,
        wsUrl = "ws://${server.hostName}:${server.port}",
        token = "tok",
        deviceName = "Nursery Pixel",
        appVersion = "0.1.0",
        heartbeat = heartbeat,
    )

    // Found as a "flake" that only failed when all three modules' suites ran
    // together. Events used to be a SharedFlow with replay = 0, so anything
    // emitted before a collector subscribed was dropped - and every caller,
    // including the production services, launched its collector and then
    // connected immediately. Under load the handshake won the race and
    // Connected vanished: no backfill on the parent, a "Not connected" banner
    // on a working monitor. isConnected is a StateFlow and cannot be lost, so
    // waiting on it guarantees Connected was emitted before anyone collected.
    @Test
    fun `an event emitted before anyone collects is still delivered`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.isConnected.first { it } }
        Thread.sleep(200) // generous: Connected is long gone by now

        withTimeout(2_000) { socket.events.filterIsInstance<MonitorSocketEvent.Connected>().first() }

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `connects with role=monitor, not parent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<MonitorSocketEvent.Connected>().first() }

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok", upgrade.headers["Authorization"])
        assertTrue(upgrade.target!!.contains("role=monitor"))

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `sends a monitor hello on connect`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        val hello = serverInbox.poll(5, TimeUnit.SECONDS)!!
        assertTrue(hello.contains(""""t":"hello""""))
        assertTrue(hello.contains(""""deviceName":"Nursery Pixel""""))
        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `each heartbeat reads a fresh battery and thermal snapshot`() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var snapshot = MonitorToHub.Heartbeat(88, "none")
        val socket = newSocket { snapshot }
        try {
            socket.connect(scope)
            dispatcher.scheduler.runCurrent()
            withTimeout(5_000) { socket.isConnected.first { it } }
            serverInbox.poll(5, TimeUnit.SECONDS) // hello
            dispatcher.scheduler.runCurrent() // start the heartbeat timer
            dispatcher.scheduler.advanceTimeBy(HEARTBEAT_INTERVAL_MS)
            dispatcher.scheduler.runCurrent()
            assertTrue(serverInbox.poll(5, TimeUnit.SECONDS)!!.contains("\"batteryPct\":88"))
            snapshot = MonitorToHub.Heartbeat(37, "light")
            dispatcher.scheduler.advanceTimeBy(HEARTBEAT_INTERVAL_MS)
            dispatcher.scheduler.runCurrent()
            val refreshed = serverInbox.poll(5, TimeUnit.SECONDS)!!
            assertTrue(refreshed.contains("\"batteryPct\":37"))
            assertTrue(refreshed.contains("\"thermal\":\"light\""))
        } finally {
            socket.disconnect(); scope.cancel()
        }
    }

    @Test
    fun `surfaces a pushed config`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<MonitorSocketEvent.Connected>().first() }

        val received = async(Dispatchers.IO) {
            withTimeout(5_000) { socket.events.filterIsInstance<MonitorSocketEvent.Message>().first() }
        }
        serverSocket!!.send(
            """{"t":"config","config":{"thresholdDb":18.0,"sustainMs":1500,"cooldownS":60,
               "noiseFloorDb":-50.0,"motionEnabled":false,"motionThreshold":12.0,
               "calibrationWindowMs":30000}}""".trimIndent(),
        )
        val config = received.await().message as HubToMonitor.Config
        assertEquals(18.0, config.config.thresholdDb, 0.001)

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `sends an event the hub would accept`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        serverInbox.poll(5, TimeUnit.SECONDS) // discard hello
        socket.send(
            MonitorToHub.Event(
                clientEventId = "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
                type = EventType.LOUD_NOISE,
                tsMs = 1,
                metadata = EventMetadata(peakDb = -9.5, durationMs = 2200),
            ),
        )
        val sent = serverInbox.poll(5, TimeUnit.SECONDS)!!
        assertTrue(sent.contains(""""t":"event""""))
        assertTrue(sent.contains(""""peakDb":-9.5"""))
        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `ignores an undecodable frame without dropping the connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<MonitorSocketEvent.Connected>().first() }

        val received = async(Dispatchers.IO) {
            withTimeout(5_000) { socket.events.filterIsInstance<MonitorSocketEvent.Message>().first() }
        }
        serverSocket!!.send("{not json")
        serverSocket!!.send("""{"t":"heartbeat_ack","serverTsMs":5}""")
        assertTrue(received.await().message is HubToMonitor.HeartbeatAck)
        assertTrue(socket.isConnected.value)

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `sendMedia puts an encoded binary frame on the wire`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.isConnected.first { it } }

        val frame = MediaFrame(MediaKind.PCM16, streamId = 3, timestampUs = 20_000, payload = byteArrayOf(5, 6))
        assertTrue(socket.sendMedia(frame))

        val onWire = serverBinaryInbox.poll(5, TimeUnit.SECONDS)!!
        assertArrayEquals(MediaFrames.encode(frame), onWire)
        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `sendMedia reports false when not connected`() {
        val socket = newSocket()
        assertFalse(socket.sendMedia(MediaFrame(MediaKind.JPEG, 1, 0, ByteArray(1))))
    }
}
