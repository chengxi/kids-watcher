package com.kidsmonitor.shared.net

/**
 * A pairing path for development that does not involve a camera.
 *
 * Re-pairing happens constantly while developing — every `pm clear`, every
 * fresh install, every revoked token — and each one otherwise means holding a
 * phone up to a QR on a laptop screen. This lets `tools/pair-device.sh` hand
 * the same payload straight to the app over adb.
 *
 * **This is not a second way to establish trust.** The payload is identical to
 * the QR's, fingerprint included, so the pin is unchanged. What it removes is
 * the camera, not the verification.
 *
 * It must never exist in a release build. The activity that receives it is
 * exported (it is the launcher activity), so in a release build any installed
 * app could hand this one a hub address and a fingerprint to trust. The
 * [debugBuild] flag is the whole guard, which is why it is a parameter with a
 * test rather than a comment asking someone to remember.
 */
object DebugPairing {
    /** Extra name used by `tools/pair-device.sh`. */
    const val EXTRA_PAYLOAD = "pairing_payload"

    /**
     * The pairing payload carried by an intent extra, or null if there is none
     * or this is a release build.
     *
     * @param extra the raw `EXTRA_PAYLOAD` string, as read from the intent
     * @param debugBuild `BuildConfig.DEBUG` of the calling app module
     */
    fun payloadFrom(extra: String?, debugBuild: Boolean): String? {
        if (!debugBuild) return null
        val raw = extra?.trim().orEmpty()
        return raw.ifEmpty { null }
    }
}
