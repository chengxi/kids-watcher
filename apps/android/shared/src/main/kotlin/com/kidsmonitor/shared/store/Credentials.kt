package com.kidsmonitor.shared.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class HubCredentials(
    val baseUrl: String,
    val wsUrl: String,
    val fingerprint: String,
    val deviceId: String,
    val token: String,
)

/**
 * The device token is a bearer credential for a camera in a child's room.
 * In production it lives in Keystore-backed encrypted preferences, and it is
 * never logged.
 *
 * The storage is injected rather than constructed inline because Robolectric
 * does not emulate the Android Keystore -- `EncryptedSharedPreferences` throws
 * `KeyStoreException: AndroidKeyStore not found` in a JVM unit test. Tests
 * therefore exercise this exact class over plain preferences, so all the field
 * and null handling is covered; only the encryption layer itself is device-only
 * and is verified in the on-device pass.
 */
class Credentials internal constructor(
    private val prefsProvider: () -> SharedPreferences,
) {
    constructor(context: Context) : this({ encryptedPrefs(context) })

    private val prefs: SharedPreferences by lazy(prefsProvider)

    val isPaired: Boolean get() = prefs.contains(KEY_TOKEN)

    fun save(credentials: HubCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, credentials.baseUrl)
            .putString(KEY_WS_URL, credentials.wsUrl)
            .putString(KEY_FINGERPRINT, credentials.fingerprint)
            .putString(KEY_DEVICE_ID, credentials.deviceId)
            .putString(KEY_TOKEN, credentials.token)
            .commit()
    }

    fun load(): HubCredentials? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return HubCredentials(
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null,
            wsUrl = prefs.getString(KEY_WS_URL, null) ?: return null,
            fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: return null,
            deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null,
            token = token,
        )
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS_NAME = "hub_credentials"

        /**
         * Builds a store over caller-supplied preferences.
         *
         * Exists because Robolectric has no Android Keystore, so
         * [EncryptedSharedPreferences] cannot be constructed in a JVM test.
         * Production code uses `Credentials(context)`, which encrypts; this
         * does not encrypt anything by itself and must never be used to hold a
         * real token.
         */
        fun withPreferences(provider: () -> SharedPreferences): Credentials =
            Credentials(provider)
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
