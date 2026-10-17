package com.kidsmonitor.shared.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PairingPayloadException(message: String) : Exception(message)

private const val SUPPORTED_VERSION = 1

// Hostname or IPv4 literal only. No scheme, no path, no port -- the port is a
// separate field. This is a trust boundary: the scanned code decides which
// server the app will talk to for the rest of its life.
private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")

@Serializable
private data class RawPayload(
    @SerialName("v") val version: Int = 0,
    val host: String = "",
    val port: Int = 0,
    val fingerprint: String = "",
    val code: String = "",
    val role: String = "",
)

data class PairingPayload(
    val version: Int,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val role: String,
) {
    val baseUrl: String get() = "https://$host:$port"
    val wsUrl: String get() = "wss://$host:$port"

    fun requireRole(expected: String): PairingPayload {
        if (role != expected) {
            throw PairingPayloadException(
                // "this app", not "the parent app": this class moved into
                // :shared in M5 and is now read by the monitor too, where
                // naming the parent app sends the reader to the wrong phone.
                "this is a $role pairing code; this app needs a $expected code",
            )
        }
        return this
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun parsePairingPayload(raw: String): PairingPayload {
    val parsed = try {
        json.decodeFromString(RawPayload.serializer(), raw)
    } catch (e: Exception) {
        throw PairingPayloadException("this QR code is not a Kids Monitor pairing code")
    }

    if (parsed.version != SUPPORTED_VERSION) {
        throw PairingPayloadException(
            "pairing code version ${parsed.version} is not supported by this app version",
        )
    }
    if (!HOST_PATTERN.matches(parsed.host)) {
        throw PairingPayloadException("pairing code contains an invalid host: '${parsed.host}'")
    }
    if (parsed.port !in 1..65535) {
        throw PairingPayloadException("pairing code contains an invalid port: ${parsed.port}")
    }
    if (parsed.code.isBlank()) {
        throw PairingPayloadException("pairing code is missing its code")
    }
    if (parsed.role != "parent" && parsed.role != "monitor") {
        throw PairingPayloadException("pairing code has an unknown role: '${parsed.role}'")
    }

    val fingerprint = try {
        HubTrust.normalizeFingerprint(parsed.fingerprint)
    } catch (e: IllegalArgumentException) {
        throw PairingPayloadException("pairing code contains an invalid certificate fingerprint")
    }

    return PairingPayload(
        version = parsed.version,
        host = parsed.host,
        port = parsed.port,
        fingerprint = fingerprint,
        code = parsed.code,
        role = parsed.role,
    )
}
