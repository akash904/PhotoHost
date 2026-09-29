package io.github.akash904.photohost.server

import android.util.Log
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import android.util.Base64

private const val TAG = "photohost"
private const val ALIAS = "photohost"

/** PKCS12, because Android's providers do not offer JKS. */
private const val FORMAT = "PKCS12"

/**
 * The server's TLS identity.
 *
 * A self-signed certificate, generated once on the device and reused. No public certificate
 * authority will issue for a bare IPv6 address, and chasing a real certificate would drag in a
 * domain, DNS and a renewal process -- all of it third-party, which is exactly what this setup is
 * trying to avoid.
 *
 * Self-signed is normally weak because nothing vouches for the certificate. That is solved here by
 * **pinning**: the pairing QR carries this certificate's SHA-256 fingerprint, so the client trusts
 * exactly one certificate -- the one it was shown in person -- and nothing else. That is stronger
 * than the public CA system for this purpose, since it cannot be defeated by any CA being tricked
 * or compromised.
 *
 * The private key never leaves the phone. The fingerprint is public information.
 */
class CertStore(private val dir: File) {

    private val storeFile = File(dir, "photohost-tls.p12")
    private val passwordFile = File(dir, "photohost-tls.pw")

    data class Identity(
        val keyStore: KeyStore,
        val alias: String,
        val password: String,
        /** Lowercase hex SHA-256 of the DER-encoded certificate, as pinned by clients. */
        val fingerprint: String,
    )

    /**
     * @param sans every address the server may be reached at. They must be baked in at generation
     *   time, because a certificate cannot name a host it was not issued for -- and a client that
     *   pins the fingerprint still checks the name.
     */
    fun loadOrCreate(sans: List<String>): Identity? {
        dir.mkdirs()
        val password = readOrCreatePassword()

        if (storeFile.isFile) {
            try {
                val existing = KeyStore.getInstance(FORMAT).apply {
                    storeFile.inputStream().use { load(it, password.toCharArray()) }
                }
                return Identity(existing, ALIAS, password, fingerprintOf(existing))
            } catch (t: Throwable) {
                // An unreadable keystore is not fatal. Regenerating costs paired clients a re-scan,
                // which is far better than the server silently falling back to plain HTTP forever
                // because of one corrupt file.
                Log.w(TAG, "existing keystore unusable (${t.message}); regenerating")
                storeFile.delete()
            }
        }

        return try {
            val fresh = generate(sans, password)
            Identity(fresh, ALIAS, password, fingerprintOf(fresh))
        } catch (t: Throwable) {
            Log.e(TAG, "TLS identity unavailable", t)
            null
        }
    }

    /** Forces a new certificate, e.g. after the phone's addresses changed. Clients must re-pair. */
    fun regenerate(sans: List<String>): Identity? {
        storeFile.delete()
        return loadOrCreate(sans)
    }

    private fun generate(sans: List<String>, password: String): KeyStore {
        Log.i(TAG, "generating TLS certificate for ${sans.size} names")
        val generated = buildKeyStore {
            certificate(ALIAS) {
                this.password = password
                // localhost is always included so loopback browsing keeps working.
                domains = (listOf("localhost", "127.0.0.1", "::1") + sans).distinct()
                keySizeInBits = 2048
                // Long-lived on purpose: expiry would silently break every paired client, and
                // there is no renewal mechanism on a phone that may be offline for weeks.
                daysValid = 3650
            }
        }
        // Ktor builds a JKS, which Android cannot reliably read back. The key and its certificate
        // are copied into a PKCS12 store instead -- a format Android does support -- so that the
        // next start loads exactly what this one wrote.
        val chars = password.toCharArray()
        val store = KeyStore.getInstance(FORMAT).apply { load(null, chars) }
        store.setKeyEntry(
            ALIAS,
            generated.getKey(ALIAS, chars),
            chars,
            generated.getCertificateChain(ALIAS),
        )
        storeFile.outputStream().use { store.store(it, chars) }
        return store
    }

    private fun fingerprintOf(store: KeyStore): String {
        val cert = store.getCertificate(ALIAS) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The keystore password, generated once and kept beside it.
     *
     * It protects nothing from someone who already has the file -- both live in app-private
     * storage, and anyone who can read one can read the other. It exists because the keystore
     * format demands one, so it is random rather than a constant baked into the source.
     */
    private fun readOrCreatePassword(): String {
        if (passwordFile.isFile) return passwordFile.readText().trim()
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val generated = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        passwordFile.writeText(generated)
        return generated
    }
}
