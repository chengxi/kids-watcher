package com.kidsmonitor.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    private fun qr(
        host: String = "192.168.1.100",
        port: Int = 8444,
        code: String = "GHXF47G6",
        role: String = "parent",
        version: Int = 1,
    ) = """{"v":$version,"host":"$host","port":$port,"fingerprint":"$fp","code":"$code","role":"$role"}"""

    @Test
    fun `parses the payload the hub pair CLI prints`() {
        val p = parsePairingPayload(qr())
        assertEquals("192.168.1.100", p.host)
        assertEquals(8444, p.port)
        assertEquals(fp, p.fingerprint)
        assertEquals("GHXF47G6", p.code)
        assertEquals("parent", p.role)
    }

    @Test
    fun `builds https and wss urls from host and port`() {
        val p = parsePairingPayload(qr())
        assertEquals("https://192.168.1.100:8444", p.baseUrl)
        assertEquals("wss://192.168.1.100:8444", p.wsUrl)
    }

    @Test
    fun `normalizes a lowercase fingerprint`() {
        val p = parsePairingPayload(qr().replace(fp, fp.lowercase()))
        assertEquals(fp, p.fingerprint)
    }

    @Test
    fun `rejects a monitor code scanned into the parent app`() {
        val e = assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(role = "monitor")).requireRole("parent")
        }
        assert(e.message!!.contains("monitor"))
    }

    // Both directions are asserted because the message used to hardcode "the
    // parent app" for either one. The test above could not see it: "monitor"
    // appears in the wrong message too, as the *expected* role. On the monitor
    // it read "this is a parent pairing code; the parent app needs a monitor
    // code" - which names the wrong phone and was found only on a device.
    @Test
    fun `rejects a parent code scanned into the monitor app`() {
        val e = assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(role = "parent")).requireRole("monitor")
        }
        val message = e.message!!
        assert(message.contains("this is a parent pairing code")) { message }
        assert(message.contains("this app needs a monitor code")) { message }
    }

    @Test
    fun `the role mismatch message never names the app the user is not holding`() {
        val intoMonitor = assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(role = "parent")).requireRole("monitor")
        }.message!!
        assert(!intoMonitor.contains("parent app")) { intoMonitor }

        val intoParent = assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(role = "monitor")).requireRole("parent")
        }.message!!
        assert(!intoParent.contains("monitor app")) { intoParent }
    }

    @Test
    fun `rejects a future payload version`() {
        assertThrows(PairingPayloadException::class.java) { parsePairingPayload(qr(version = 2)) }
    }

    @Test
    fun `rejects a malformed fingerprint`() {
        assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr().replace(fp, "NOPE"))
        }
    }

    @Test
    fun `rejects a port outside the valid range`() {
        assertThrows(PairingPayloadException::class.java) { parsePairingPayload(qr(port = 70000)) }
    }

    @Test
    fun `rejects a host containing a scheme or path`() {
        // Guards against a crafted QR pointing the app somewhere unexpected.
        assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(host = "evil.example.com/x"))
        }
        assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload(qr(host = "https://evil.example.com"))
        }
    }

    @Test
    fun `rejects arbitrary scanned text`() {
        assertThrows(PairingPayloadException::class.java) {
            parsePairingPayload("https://some-random-qr-code.example.com")
        }
    }

    @Test
    fun `rejects an empty string`() {
        assertThrows(PairingPayloadException::class.java) { parsePairingPayload("") }
    }
}
