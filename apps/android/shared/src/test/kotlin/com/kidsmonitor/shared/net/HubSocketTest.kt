package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaFrames
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.ParentToHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HubSocketTest {

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
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverInbox.put(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                serverBinaryInbox.put(bytes.toByteArray())
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        server.start()
    }

    @After
    fun tearDown() {
        // MockWebServer refuses to shut down while OkHttp still holds a
        // connection, and reports it as "Gave up waiting for queue to shut
        // down" from tearDown -- which looks like a failure of whichever test
        // ran, not of the cleanup. Release everything explicitly.
        serverSocket?.close(1000, null)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }

    private fun newSocket() = HubSocket(
        client = client,
        wsUrl = "ws://${server.hostName}:${server.port}",
        token = "tok",
        deviceName = "Test Phone",
        appVersion = "0.1.0",
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

        withTimeout(2_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `backoff grows exponentially and is capped at 60 seconds`() {
        assertEquals(1_000L, backoffDelayMs(0))
        assertEquals(2_000L, backoffDelayMs(1))
        assertEquals(4_000L, backoffDelayMs(2))
        assertEquals(16_000L, backoffDelayMs(4))
        assertEquals(60_000L, backoffDelayMs(10))
        assertEquals(60_000L, backoffDelayMs(99))
    }

    @Test
    fun `healthy reconnect resets backoff and foreground retry skips the wait`() = runBlocking {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val socket = newSocket()
        val nextServerSocket = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                nextServerSocket.put(webSocket)
            }
        }).build())
        try {
            assertEquals(ConnectionState.Stopped, socket.connectionState.value)
            socket.connect(scope)
            withTimeout(5_000) { socket.isConnected.first { it } }
            serverSocket!!.close(1000, "first outage")
            val firstWait = withTimeout(5_000) {
                socket.connectionState.first { it is ConnectionState.WaitingToRetry }
            } as ConnectionState.WaitingToRetry
            assertEquals(1_000L, firstWait.delayMs)
            socket.retryNow()
            val reconnectedServer = nextServerSocket.poll(5, TimeUnit.SECONDS)!!
            withTimeout(5_000) { socket.isConnected.first { it } }
            reconnectedServer.close(1000, "second outage")
            val secondWait = withTimeout(5_000) {
                socket.connectionState.first { it is ConnectionState.WaitingToRetry }
            } as ConnectionState.WaitingToRetry
            assertEquals(1_000L, secondWait.delayMs)
        } finally {
            socket.disconnect()
            dispatcher.scheduler.runCurrent()
            scope.cancel()
        }
        assertEquals(ConnectionState.Stopped, socket.connectionState.value)
    }

    @Test
    fun `sends the bearer token and the parent role on the upgrade`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok", upgrade.headers["Authorization"])
        assertTrue(upgrade.target!!.contains("role=parent"))
        assertTrue(upgrade.target!!.startsWith("/ws"))

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `sends hello immediately on connect`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        val hello = serverInbox.poll(5, TimeUnit.SECONDS)!!
        assertTrue(hello.contains(""""t":"hello""""))
        assertTrue(hello.contains(""""deviceName":"Test Phone""""))
        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `surfaces a hub event as a decoded message`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        val received = async(Dispatchers.IO) {
            withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Message>().first() }
        }
        serverSocket!!.send(
            """{"t":"event","eventId":9,"monitorId":"m1","type":"LOUD_NOISE","tsMs":1,"metadata":{"peakDb":-7.0}}""",
        )
        val event = received.await().message as HubToParent.Event
        assertEquals(9L, event.eventId)

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `ignores an undecodable frame without dropping the connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        val received = async(Dispatchers.IO) {
            withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Message>().first() }
        }
        serverSocket!!.send("{not json")
        serverSocket!!.send("""{"t":"heartbeat_ack","serverTsMs":5}""")
        assertTrue(received.await().message is HubToParent.HeartbeatAck)
        assertTrue(socket.isConnected.value)

        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `send encodes a parent message onto the wire`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        serverInbox.poll(5, TimeUnit.SECONDS) // discard hello
        socket.send(ParentToHub.AckEvent(eventId = 3))
        val sent = serverInbox.poll(5, TimeUnit.SECONDS)!!
        assertTrue(sent.contains(""""t":"ack_event""""))
        assertTrue(sent.contains(""""eventId":3"""))
        socket.disconnect(); scope.cancel()
    }

    @Test
    fun `reports disconnection when the server closes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        val disconnected = async(Dispatchers.IO) {
            withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Disconnected>().first() }
        }
        serverSocket!!.close(1000, "server going away")
        disconnected.await()
        assertEquals(false, socket.isConnected.value)

        socket.disconnect(); scope.cancel()
    }

    // Media is a lossy broadcast by design, so the collector subscribes
    // UNDISPATCHED - before the frame is sent - rather than relying on timing.
    @Test
    fun `surfaces a binary media frame on the media flow`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.isConnected.first { it } }

        val received = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { socket.video.first() }
        }
        val frame = MediaFrame(MediaKind.JPEG, streamId = 7, timestampUs = 1_000, payload = byteArrayOf(1, 2, 3))
        serverSocket!!.send(MediaFrames.encode(frame).toByteString())

        val got = received.await()
        assertEquals(MediaKind.JPEG, got.kind)
        assertEquals(7, got.streamId)
        assertArrayEquals(byteArrayOf(1, 2, 3), got.payload)
        socket.disconnect(); scope.cancel()
    }

    // Control events stay a lossless queue; a frame must never appear there.
    @Test
    fun `a media frame does not enter the event queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }

        serverSocket!!.send(MediaFrames.encode(MediaFrame(MediaKind.PCM16, 1, 0, ByteArray(4))).toByteString())
        serverSocket!!.send("""{"t":"heartbeat_ack","serverTsMs":5}""")

        val next = withTimeout(5_000) { socket.events.first() }
        assertTrue("expected the heartbeat_ack, got $next", next is HubSocketEvent.Message)
        socket.disconnect(); scope.cancel()
    }

    // The media flow is lossy, so "nothing arrived" proves nothing on its own.
    // The collector subscribes before anything is sent; garbage then a valid
    // frame go out in that order; the first frame collected must be the valid
    // one, on a connection that is still up. This fails if binary messages are
    // not handled at all, and if undecodable bytes break the listener.
    @Test
    fun `ignores an undecodable binary frame without dropping the connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        socket.connect(scope)
        withTimeout(5_000) { socket.isConnected.first { it } }

        val received = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { socket.video.first() }
        }
        serverSocket!!.send(byteArrayOf(0x00, 0x01).toByteString())
        val valid = MediaFrame(MediaKind.JPEG, streamId = 9, timestampUs = 2, payload = byteArrayOf(4, 5))
        serverSocket!!.send(MediaFrames.encode(valid).toByteString())

        val got = received.await()
        assertEquals(9, got.streamId)
        assertArrayEquals(byteArrayOf(4, 5), got.payload)
        assertTrue(socket.isConnected.value)
        socket.disconnect(); scope.cancel()
    }
    @Test
    fun `an unanswered foreground request can retire a stale connected socket and reconnect`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = newSocket()
        val replacement = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { serverSocket = webSocket; replacement.put(webSocket) }
        }).build())
        try {
            socket.connect(scope)
            withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }
            socket.reconnectNow()
            withTimeout(5_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }
            assertTrue(replacement.poll(5, TimeUnit.SECONDS) != null)
            assertEquals(ConnectionState.Connected, socket.connectionState.value)
            assertEquals(2, server.requestCount)
        } finally { socket.disconnect(); scope.cancel() }
    }

}
