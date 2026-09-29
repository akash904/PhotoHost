package io.github.akash904.photohost.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

private const val TAG = "photohost"

/**
 * Answers "can the library actually be reached from the internet?" without trusting anyone to tell us.
 *
 * ### Why it runs over cellular
 * Testing inbound reachability requires a client that is genuinely outside the home network. The
 * obvious approaches are both bad: asking a port-checking website hands our address to a stranger,
 * and talking the user through switching a second phone off Wi-Fi turns a diagnostic into a chore.
 *
 * Android offers a third way. [ConnectivityManager.requestNetwork] can bring up the cellular network
 * *alongside* Wi-Fi and hand back a [Network] whose socket factory routes over it. So the phone can
 * dial its own public address from the mobile network -- a real path in from the internet, crossing
 * the router's firewall from outside -- while never leaving Wi-Fi. One device, no third party, and an
 * unambiguous answer.
 *
 * ### What a pass actually proves
 * A completed TLS handshake whose certificate fingerprint matches the one the server generated proves
 * the whole chain end to end: the router forwarded, the phone accepted, and the thing that answered
 * is this server rather than something else that happens to hold the address. That last part matters
 * on IPv4, where a carrier NAT can route the port to an entirely different customer.
 *
 * Costs a few kilobytes of mobile data, and needs mobile data switched on.
 */
class RemoteProbe(private val context: Context) {

    sealed interface Result {
        /**
         * @param matched whether the certificate is the one this server presents. False means
         *   something answered, but it was not us.
         */
        data class Reachable(
            val fingerprint: String,
            val matched: Boolean,
            val millis: Long,
        ) : Result

        /** The connection itself failed: almost always the router still dropping inbound traffic. */
        data class Blocked(val detail: String) : Result

        /** Could not get a cellular path, so the test never ran. Not evidence either way. */
        data class NoCellular(val detail: String) : Result
    }

    /**
     * @param host the public address to dial, bracketed if IPv6.
     * @param port the TLS port.
     * @param expectedFingerprint the server's certificate fingerprint, to confirm identity.
     */
    suspend fun overCellular(host: String, port: Int, expectedFingerprint: String?): Result {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return Result.NoCellular("no connectivity service")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        var callback: ConnectivityManager.NetworkCallback? = null
        return try {
            val network = withTimeoutOrNull(20_000) {
                // Explicit type parameter: inference would otherwise settle on Nothing? from the
                // resume(null) branch below and reject the Network passed on success.
                suspendCancellableCoroutine<Network?> { continuation ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (continuation.isActive) continuation.resume(network)
                        }

                        override fun onUnavailable() {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    callback = cb
                    try {
                        manager.requestNetwork(request, cb)
                    } catch (t: Throwable) {
                        // Missing CHANGE_NETWORK_STATE, or no telephony at all on this device.
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } ?: return Result.NoCellular("mobile data unavailable; switch it on and retry")

            connect(network, host, port, expectedFingerprint)
        } catch (t: Throwable) {
            Result.NoCellular("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // Releasing matters: an outstanding request keeps the cellular radio up indefinitely.
            callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        }
    }

    private fun connect(
        network: Network,
        host: String,
        port: Int,
        expectedFingerprint: String?,
    ): Result {
        val target = host.trim('[', ']')
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            val plain = network.socketFactory.createSocket() ?: return Result.Blocked("no socket")
            socket = plain
            plain.connect(InetSocketAddress(target, port), 10_000)

            val tls = captureContext().socketFactory
                .createSocket(plain, target, port, true) as SSLSocket
            socket = tls
            tls.soTimeout = 10_000
            tls.startHandshake()

            val leaf = tls.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return Result.Blocked("handshake produced no certificate")
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            val expected = expectedFingerprint?.lowercase()?.replace(":", "")
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "cellular probe reached $target:$port in ${elapsed}ms")
            Result.Reachable(actual, matched = expected != null && actual == expected, millis = elapsed)
        } catch (t: Throwable) {
            Log.i(TAG, "cellular probe could not reach $target:$port: ${t.message}")
            Result.Blocked("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * A context that completes the handshake with any certificate, so the real fingerprint can be
     * read and reported rather than hidden behind a validation failure.
     *
     * Safe here, and only here: this socket carries no token, no cookie and no photo bytes. It sends
     * nothing and reads nothing beyond the certificate the peer volunteers during the handshake. The
     * comparison against the expected fingerprint happens in [connect], which is where the trust
     * decision actually lives -- a mismatch is reported to the user as a failure.
     *
     * The app's real traffic goes through [Pinning], which rejects anything unpinned.
     */
    private fun captureContext(): SSLContext {
        @SuppressLint("CustomX509TrustManager")
        val permissive = object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(permissive), java.security.SecureRandom())
        }
    }
}
