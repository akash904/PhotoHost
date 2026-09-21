package dev.gpicalter.net

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "gpic"

/**
 * Trusts exactly one certificate: the one whose fingerprint was carried in the pairing QR.
 *
 * The server presents a self-signed certificate, because no public authority will issue for a bare
 * IPv6 address and obtaining one would mean a domain, DNS and renewals -- all third-party, which is
 * what this setup exists to avoid.
 *
 * Self-signed is usually the weak option, since nothing vouches for the certificate. Pinning
 * inverts that. The fingerprint travelled out of band, by being shown on a screen and photographed,
 * so the client is not asking "does some authority vouch for this?" but "is this the exact key I
 * was shown?". That is a stronger question: it cannot be defeated by a compromised or deceived
 * certificate authority, which is the standing weakness of the public CA system.
 *
 * The consequence is that this trust manager rejects everything else, including certificates the
 * device would normally accept.
 */
class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {

    private val expected = expectedFingerprint.lowercase().replace(":", "")

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // This app is never a TLS server, so it is never asked to validate a client.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("server presented no certificate")
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(leaf.encoded)
            .joinToString("") { "%02x".format(it) }
        if (!constantTimeEquals(actual, expected)) {
            throw CertificateException(
                "certificate fingerprint $actual does not match the pinned $expected",
            )
        }
    }

    /**
     * Empty on purpose. Returning the system CAs would suggest they are acceptable issuers, and
     * here they are not -- only the pinned certificate is.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

object Pinning {

    /**
     * Returns a client that trusts only [fingerprint], or the default client when no pin is set.
     *
     * Hostname verification is bypassed **only** when a pin is in force, and that is deliberate
     * rather than lazy. The certificate names bare IP addresses, which cannot be expressed as DNS
     * names, so standard verification would reject a certificate that is in fact exactly the right
     * one. With a pin, identity is established by the key itself: matching the fingerprint is a
     * strictly stronger check than matching a name in that certificate.
     *
     * Without a pin the client keeps normal platform validation, so this cannot silently weaken a
     * connection that was not explicitly pinned.
     */
    fun clientFor(base: OkHttpClient.Builder, fingerprint: String?): OkHttpClient {
        if (fingerprint.isNullOrBlank()) return base.build()
        return try {
            val trust = PinnedTrustManager(fingerprint)
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trust), java.security.SecureRandom())
            }
            base
                .sslSocketFactory(context.socketFactory, trust)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "could not install certificate pin; falling back to platform trust", t)
            base.build()
        }
    }
}
