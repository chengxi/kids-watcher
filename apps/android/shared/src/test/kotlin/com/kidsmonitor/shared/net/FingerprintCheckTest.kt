package com.kidsmonitor.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FingerprintCheckTest {

    private val fp = "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
        "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3"

    @Test
    fun `is the first three bytes of the fingerprint`() {
        assertEquals("D8:52:26", FingerprintCheck.of(fp))
    }

    @Test
    fun `accepts the same fingerprint without colons or in lowercase`() {
        assertEquals("D8:52:26", FingerprintCheck.of(fp.replace(":", "").lowercase()))
    }

    @Test
    fun `refuses anything that is not a SHA-256 fingerprint`() {
        assertThrows(IllegalArgumentException::class.java) { FingerprintCheck.of("D8:52:26") }
    }
}
