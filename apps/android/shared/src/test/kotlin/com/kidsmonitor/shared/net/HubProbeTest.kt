package com.kidsmonitor.shared.net

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class HubProbeTest {

    private val held = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .build()

    private val server = MockWebServer()

    private fun startTls(response: MockResponse) {
        val certs = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(certs.sslSocketFactory())
        server.enqueue(response)
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `returns the certificate fingerprint of a hub that answers healthz`() = runTest {
        startTls(MockResponse(code = 200, body = """{"ok":true}"""))

        val result = HubProbe.probe(server.hostName, server.port)

        assertEquals(ProbeResult.Found(HubTrust.fingerprintOf(held.certificate)), result)
    }

    @Test
    fun `reports not a hub when something else is listening on TLS`() = runTest {
        startTls(MockResponse(code = 200, body = "<html>router admin</html>"))

        assertEquals(ProbeResult.NotAHub, HubProbe.probe(server.hostName, server.port))
    }

    @Test
    fun `reports not a hub when the path is missing`() = runTest {
        startTls(MockResponse(code = 404, body = "not found"))

        assertEquals(ProbeResult.NotAHub, HubProbe.probe(server.hostName, server.port))
    }

    @Test
    fun `reports a TLS error when the server does not speak TLS`() = runTest {
        // A plain MockWebServer waits for an HTTP request line. TLS ClientHello
        // contains arbitrary bytes, so that setup sometimes times out instead
        // of answering. Send a deterministic non-TLS response on acceptance.
        ServerSocket(0).use { plain ->
            val responder = thread(isDaemon = true) {
                plain.accept().use { socket ->
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nplain".toByteArray())
                }
            }
            val result = HubProbe.probe("127.0.0.1", plain.localPort)
            responder.join(1_000)
            assertTrue("expected TlsError, got $result", result is ProbeResult.TlsError)
        }
    }

    @Test
    fun `reports unreachable when nothing is listening`() = runTest {
        server.start()
        val port = server.port
        server.close()

        val result = HubProbe.probe("127.0.0.1", port)

        assertTrue("expected Unreachable, got $result", result is ProbeResult.Unreachable)
    }
}
