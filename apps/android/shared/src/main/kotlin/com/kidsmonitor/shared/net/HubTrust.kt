package com.kidsmonitor.shared.net

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class FingerprintMismatchException(expected: String, actual: String) : CertificateException(
    "hub certificate does not match the pinned fingerprint.\nexpected: $expected\nactual:   $actual",
)

object HubTrust {

    private val HEX = Regex("^[0-9A-F]{64}$")

    /**
     * Canonical form is uppercase hex, colon-separated -- the same shape the
     * hub's pair CLI prints and `openssl x509 -fingerprint -sha256` emits.
     */
    fun normalizeFingerprint(raw: String): String {
        val bare = raw.trim().replace(":", "").replace(" ", "").uppercase()
        require(HEX.matches(bare)) {
            "not a SHA-256 fingerprint: expected 64 hex characters, got '${raw.trim()}'"
        }
        return bare.chunked(2).joinToString(":")
    }

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Trusts exactly one certificate, identified by fingerprint, and nothing
     * else. This deliberately does not chain to the system trust store: the
     * hub's certificate is self-signed, so CA validation would reject it before
     * any pin could be checked. OkHttp's CertificatePinner is unusable for the
     * same reason -- it layers on top of CA validation rather than replacing it.
     */
    private class PinnedTrustManager(expected: String) : X509ExtendedTrustManager() {
        private val expected = normalizeFingerprint(expected)

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("server presented no certificate")
            val actual = fingerprintOf(leaf)
            if (actual != expected) throw FingerprintMismatchException(expected, actual)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            check(chain)

        // This app is never a TLS server and never presents a client
        // certificate. Refuse rather than silently accept.
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
            throw CertificateException("client authentication is not used")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
            throw CertificateException("client authentication is not used")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
            throw CertificateException("client authentication is not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun clientFor(fingerprintSha256: String): OkHttpClient {
        val trustManager = PinnedTrustManager(fingerprintSha256)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The hub's certificate has CN=kids-monitor-hub but is reached by IP,
            // so the hostname can never match. The fingerprint pin is the
            // identity check; hostname verification would add nothing and would
            // reject every connection.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 0 = no timeout: the socket is long-lived
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
