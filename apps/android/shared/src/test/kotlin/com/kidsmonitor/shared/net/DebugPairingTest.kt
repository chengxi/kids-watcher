package com.kidsmonitor.shared.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugPairingTest {

    private val payload = """{"v":1,"host":"192.168.1.2","port":8443,"code":"ABCD1234"}"""

    @Test
    fun `returns the payload in a debug build`() {
        assertEquals(payload, DebugPairing.payloadFrom(payload, debugBuild = true))
    }

    // The load-bearing one. The receiving activity is exported, so in a release
    // build this extra would let any installed app nominate a hub address and a
    // certificate fingerprint for this app to trust for the rest of its life.
    @Test
    fun `returns null in a release build even when a payload is present`() {
        assertNull(DebugPairing.payloadFrom(payload, debugBuild = false))
    }

    @Test
    fun `returns null when the extra is absent`() {
        assertNull(DebugPairing.payloadFrom(null, debugBuild = true))
    }

    @Test
    fun `returns null when the extra is blank`() {
        assertNull(DebugPairing.payloadFrom("   ", debugBuild = true))
    }

    // adb and the device shell both add whitespace in ways that are tedious to
    // control from a script; a stray newline must not become a parse error.
    @Test
    fun `trims whitespace the device shell adds`() {
        assertEquals(payload, DebugPairing.payloadFrom("\n  $payload  \n", debugBuild = true))
    }
}
