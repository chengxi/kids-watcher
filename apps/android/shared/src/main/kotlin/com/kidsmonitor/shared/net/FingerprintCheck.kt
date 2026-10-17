package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.FINGERPRINT_CHECK_BYTES

/**
 * The few characters a person is asked to compare against the hub's screen
 * when there is no QR code to carry the fingerprint.
 *
 * It is a view of the fingerprint, not a second encoding of it: the same bytes
 * appear in the pair command's output and in `openssl x509 -fingerprint`.
 */
object FingerprintCheck {
    fun of(fingerprint: String): String =
        HubTrust.normalizeFingerprint(fingerprint)
            .split(":")
            .take(FINGERPRINT_CHECK_BYTES)
            .joinToString(":")
}
