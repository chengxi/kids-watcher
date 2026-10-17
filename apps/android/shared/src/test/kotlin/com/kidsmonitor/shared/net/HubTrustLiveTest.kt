package com.kidsmonitor.shared.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs only when a real hub is reachable, so CI stays green off-network.
 * Unit tests cover fingerprint parsing; only a real TLS handshake proves the
 * trust manager. Point it at your own instance:
 *
 *   HUB_URL=https://192.168.1.100:8444 HUB_FINGERPRINT=D8:52:... \
 *     ./gradlew :shared:testDebugUnitTest --tests '*HubTrustLiveTest*'
 */
class HubTrustLiveTest {

    private val url: String? = System.getenv("HUB_URL")
    private val fingerprint: String? = System.getenv("HUB_FINGERPRINT")

    @Before
    fun requireHub() {
        assumeTrue(
            "set HUB_URL and HUB_FINGERPRINT to run",
            !url.isNullOrBlank() && !fingerprint.isNullOrBlank(),
        )
    }

    @Test
    fun `connects to the hub when the fingerprint matches`() {
        val client = HubTrust.clientFor(fingerprint!!)
        client.newCall(Request.Builder().url("$url/healthz").build()).execute().use { res ->
            assertEquals(200, res.code)
            assertTrue(res.body!!.string().contains("\"ok\":true"))
        }
    }

    @Test
    fun `refuses to connect when the fingerprint is wrong`() {
        // Flip the first byte. Everything else about the connection is valid,
        // so only the pin can be what rejects it. If this passes, the pin is
        // decorative and the entire LAN security model is void.
        val wrong = "00:" + fingerprint!!.substringAfter(":")
        val client = HubTrust.clientFor(wrong)
        val failure = runCatching {
            client.newCall(Request.Builder().url("$url/healthz").build()).execute().close()
        }.exceptionOrNull()

        assertTrue("expected the pin to reject the connection, but it succeeded", failure != null)
        // Insist on the mismatch specifically. Asserting merely "something threw"
        // would also pass if the hub were unreachable, which would make this
        // test claim the pin works on a night when it was never exercised.
        val causes = generateSequence(failure) { it.cause }.toList()
        assertTrue(
            "rejected, but not by the fingerprint pin: ${causes.map { it::class.simpleName }}",
            causes.any { it is FingerprintMismatchException },
        )
    }

    // A third test asserting the presented certificate equals the pinned one
    // would be redundant: the first test cannot pass unless that is already
    // true, because the trust manager throws on any mismatch.
}
