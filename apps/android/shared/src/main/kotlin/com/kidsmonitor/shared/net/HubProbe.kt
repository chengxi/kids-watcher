package com.kidsmonitor.shared.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager

sealed interface ProbeResult {
    /** A Kids Monitor hub answered, presenting this certificate fingerprint. */
    data class Found(val fingerprint: String) : ProbeResult

    /** Nothing answered at that address. */
    data class Unreachable(val detail: String) : ProbeResult

    /** Something answered, but it is not a Kids Monitor hub. */
    data object NotAHub : ProbeResult

    /** Something answered and TLS failed before any request could be made. */
    data class TlsError(val detail: String) : ProbeResult
}

/**
 * Reads a hub's certificate before anything has been trusted, so a person can
 * compare [FingerprintCheck] against the hub's own screen and only then send a
 * pairing code.
 *
 * DANGER: this holds the one client in the codebase that trusts any
 * certificate. It is private to this file and reachable only through [probe],
 * and what it returns is unusable until the user confirms the fingerprint.
 * Everything after pairing goes through `HubTrust.clientFor`.
 */
object HubProbe {

    suspend fun probe(host: String, port: Int): ProbeResult = withContext(Dispatchers.IO) {
        val recorder = RecordingTrustManager()
        val client = clientTrusting(recorder)
        val request = Request.Builder().url("https://$host:$port/healthz").build()

        try {
            client.newCall(request).execute().use { response ->
                val fingerprint = recorder.seen
                    ?: return@withContext ProbeResult.TlsError("the server presented no certificate")
                val body = response.body?.string().orEmpty()
                // /healthz is the hub's unauthenticated liveness route. Asking
                // for it rather than opening a bare socket means a typo that
                // lands on a router's admin page fails here, with an honest
                // message, instead of passing the certificate step and failing
                // later at /pair.
                if (response.code != 200 || !body.contains("\"ok\":true")) {
                    return@withContext ProbeResult.NotAHub
                }
                ProbeResult.Found(fingerprint)
            }
        } catch (e: SSLException) {
            ProbeResult.TlsError(e.message ?: "TLS handshake failed")
        } catch (e: IOException) {
            ProbeResult.Unreachable(e.message ?: "could not reach $host:$port")
        }
    }

    private fun clientTrusting(trustManager: X509ExtendedTrustManager): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The hub's certificate is self-signed with CN=kids-monitor-hub and
            // is reached by IP, so no hostname can ever match.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private class RecordingTrustManager : X509ExtendedTrustManager() {
        @Volatile
        var seen: String? = null

        private fun record(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: return
            seen = HubTrust.fingerprintOf(leaf)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            record(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = record(chain)

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = record(chain)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
