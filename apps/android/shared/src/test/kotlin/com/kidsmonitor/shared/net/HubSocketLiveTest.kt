package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.ParentToHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opens a real authenticated socket to a running hub over pinned TLS and
 * completes a message round-trip. MockWebServer proves the client's behaviour;
 * this proves the hub accepts what it produces.
 *
 *   HUB_HOST=192.168.1.100 HUB_PORT=8444 HUB_FINGERPRINT=... HUB_PAIRING_CODE=... \
 *     ./gradlew :shared:testDebugUnitTest --tests '*HubSocketLiveTest*' --rerun-tasks
 */
class HubSocketLiveTest {

    private val host: String? = System.getenv("HUB_HOST")
    private val port: String? = System.getenv("HUB_PORT")
    private val fingerprint: String? = System.getenv("HUB_FINGERPRINT")
    private val code: String? = System.getenv("HUB_PAIRING_CODE")

    @Before
    fun requireHub() {
        assumeTrue(
            "set HUB_HOST, HUB_PORT, HUB_FINGERPRINT and HUB_PAIRING_CODE to run",
            !host.isNullOrBlank() && !port.isNullOrBlank() &&
                !fingerprint.isNullOrBlank() && !code.isNullOrBlank(),
        )
    }

    @Test
    fun `connects over pinned TLS and round-trips a heartbeat`() = runBlocking {
        val scanned = parsePairingPayload(
            """{"v":1,"host":"$host","port":$port,"fingerprint":"$fingerprint","code":"$code","role":"parent"}""",
        )
        val client = HubTrust.clientFor(scanned.fingerprint)
        val credentials = HubApi(client, scanned.baseUrl, null).pair(scanned, "Kotlin Socket Test")

        val socket = HubSocket(
            client = client,
            wsUrl = credentials.wsUrl,
            token = credentials.token,
            deviceName = "Kotlin Socket Test",
            appVersion = "0.1.0",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            socket.connect(scope)
            withTimeout(10_000) { socket.events.filterIsInstance<HubSocketEvent.Connected>().first() }
            assertTrue("socket reported connected but the flag says otherwise", socket.isConnected.value)

            // Await the ack before sending, so a fast reply cannot be missed.
            val ack = async(Dispatchers.IO) {
                withTimeout(10_000) {
                    socket.events.filterIsInstance<HubSocketEvent.Message>()
                        .first { it.message is HubToParent.HeartbeatAck }
                }
            }
            Thread.sleep(200)
            socket.send(ParentToHub.Heartbeat)
            socket.send(ParentToHub.Subscribe(monitorIds = null))
            ack.await()

            // Subscribe was accepted rather than answered with an error frame:
            // if the hub had rejected the encoding it would have sent
            // {"t":"error","code":"bad_message"} instead.
            assertTrue(socket.isConnected.value)
        } finally {
            socket.disconnect()
            scope.cancel()
        }
    }
}
