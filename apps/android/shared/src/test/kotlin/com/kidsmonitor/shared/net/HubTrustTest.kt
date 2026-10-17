package com.kidsmonitor.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HubTrustTest {

    private val valid = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    @Test
    fun `accepts a colon separated uppercase fingerprint unchanged`() {
        assertEquals(valid, HubTrust.normalizeFingerprint(valid))
    }

    @Test
    fun `normalizes lowercase and whitespace`() {
        assertEquals(valid, HubTrust.normalizeFingerprint("  " + valid.lowercase() + "  "))
    }

    @Test
    fun `normalizes a fingerprint with no separators`() {
        assertEquals(valid, HubTrust.normalizeFingerprint(valid.replace(":", "")))
    }

    @Test
    fun `rejects a fingerprint that is too short`() {
        assertThrows(IllegalArgumentException::class.java) {
            HubTrust.normalizeFingerprint("D8:52:26")
        }
    }

    @Test
    fun `rejects non hex characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            HubTrust.normalizeFingerprint(valid.replaceFirst("D8", "ZZ"))
        }
    }

    @Test
    fun `rejects an empty fingerprint`() {
        assertThrows(IllegalArgumentException::class.java) { HubTrust.normalizeFingerprint("") }
    }
}
