package com.kidsmonitor.shared.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pairs for real against a running hub. MockWebServer proves the request and
 * response shapes this app produces; only the real server proves those shapes
 * are the ones it actually accepts.
 *
 * Needs a fresh single-use pairing code, so it is driven by a script rather
 * than run casually:
 *
 *   HUB_HOST=192.168.1.100 HUB_PORT=8444 HUB_FINGERPRINT=... HUB_PAIRING_CODE=... \
 *     ./gradlew :shared:testDebugUnitTest --tests '*HubApiLiveTest*'
 */
class HubApiLiveTest {

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

    private fun payload(withCode: String) = parsePairingPayload(
        """{"v":1,"host":"$host","port":$port,"fingerprint":"$fingerprint","code":"$withCode","role":"parent"}""",
    )

    @Test
    fun `pairs, reads the log, and then rejects the reused code`() = runBlocking {
        val scanned = payload(code!!)
        val client = HubTrust.clientFor(scanned.fingerprint)

        // 1. Redeem the code over pinned TLS.
        val credentials = HubApi(client, scanned.baseUrl, null).pair(scanned, "Kotlin Live Test")
        assertTrue("device id looks wrong: ${credentials.deviceId}", credentials.deviceId.length >= 32)
        assertEquals(43, credentials.token.length)

        // 2. The token works on an authenticated endpoint.
        val api = HubApi(client, credentials.baseUrl, credentials.token)
        api.events(since = 0) // must not throw
        api.monitors()        // must not throw

        // 3. The same code is refused the second time. Single-use enforcement
        //    is the property that makes a photographed QR harmless.
        val reuse = assertThrows(HubApiException::class.java) {
            runBlocking { HubApi(client, scanned.baseUrl, null).pair(scanned, "Impostor") }
        }
        assertEquals(403, reuse.statusCode)

        // 4. A forged token of the right shape is refused.
        val forged = HubApi(client, credentials.baseUrl, "x".repeat(43))
        val denied = assertThrows(HubApiException::class.java) {
            runBlocking { forged.events(since = 0) }
        }
        assertEquals(401, denied.statusCode)
    }
}
