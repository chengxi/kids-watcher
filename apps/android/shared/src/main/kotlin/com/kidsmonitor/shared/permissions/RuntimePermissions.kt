package com.kidsmonitor.shared.permissions

/**
 * The runtime permissions each app needs, as one list to request in one go.
 *
 * **Request them together, with a single launcher.** Android shows one
 * permission dialog at a time and drops any request made while another is
 * open, logging only `Can request only one set of permissions at a time`.
 * Launching camera, then notifications, then the local network back-to-back
 * meant the last was never asked for - and on Android 17 a missing
 * ACCESS_LOCAL_NETWORK makes hub connections time out rather than fail, so the
 * symptom was a pairing that hung forever. Found on a Pixel 8 Pro in M5.
 *
 * Plain strings and an SDK parameter so this is testable without Robolectric.
 */
object RuntimePermissions {
    /** Not in `Manifest.permission` on the compile SDK this project uses yet. */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val CAMERA = "android.permission.CAMERA"
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    private const val NOTIFICATIONS_SDK = 33
    private const val LOCAL_NETWORK_SDK = 37

    /** A permission and the first SDK on which it must be requested at runtime. */
    class Requirement internal constructor(val permission: String, val sinceSdk: Int)

    /** Camera scans the pairing QR; the microphone is the product. */
    val MONITOR: List<Requirement> = listOf(
        Requirement(CAMERA, 1),
        Requirement(RECORD_AUDIO, 1),
        Requirement(POST_NOTIFICATIONS, NOTIFICATIONS_SDK),
        Requirement(ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_SDK),
    )

    val PARENT: List<Requirement> = listOf(
        Requirement(CAMERA, 1),
        Requirement(POST_NOTIFICATIONS, NOTIFICATIONS_SDK),
        Requirement(ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_SDK),
    )

    /**
     * What the typed pairing path needs: no camera, because nothing is scanned,
     * but still the local network, or on Android 17 the app cannot reach a hub
     * on a LAN address at all. Found on a Pixel 8 Pro while verifying M6: the
     * typed screen asked for nothing, and pairing timed out after 10 s with
     * "failed to connect" on a freshly installed app.
     */
    val PARENT_TYPED: List<Requirement> = listOf(
        Requirement(POST_NOTIFICATIONS, NOTIFICATIONS_SDK),
        Requirement(ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_SDK),
    )

    val MONITOR_TYPED: List<Requirement> = listOf(
        Requirement(RECORD_AUDIO, 1),
        Requirement(POST_NOTIFICATIONS, NOTIFICATIONS_SDK),
        Requirement(ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_SDK),
    )

    /** The permissions from [required] that apply on [sdkInt] and are not yet granted. */
    fun missing(
        required: List<Requirement>,
        sdkInt: Int,
        isGranted: (String) -> Boolean,
    ): List<String> = required
        .filter { sdkInt >= it.sinceSdk }
        .map { it.permission }
        .filterNot(isGranted)
}
