package io.github.akash904.photohost.server

import io.github.akash904.photohost.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

private const val TAG = "photohost"
private const val BUFFER = 32 * 1024

/**
 * Terminates TLS and relays to the plain HTTP server on loopback.
 *
 * Ktor's CIO engine does not implement HTTPS -- it throws `UnsupportedOperationException` if given
 * an SSL connector -- and the alternatives that do (Netty, Jetty) assume a server JVM and are not
 * dependable on ART. Rather than trade a working HTTP server for a broken one, TLS is handled in
 * front: this accepts encrypted connections and pipes the decrypted bytes to `127.0.0.1:<httpPort>`.
 *
 * Being a byte pipe rather than an HTTP proxy is the point. It knows nothing about requests, so
 * Range responses, chunked uploads, SSE and anything added later all pass through untouched, and
 * there is no second HTTP implementation to keep consistent with the first.
 *
 * The extra hop is loopback, so it costs a memory copy rather than a network round trip.
 */
class TlsProxy(
    private val identity: CertStore.Identity,
    private val listenPort: Int,
    private val targetPort: Int,
) {
    private var job: Job? = null
    private var serverSocket: SSLServerSocket? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(16)

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        return try {
            val context = SSLContext.getInstance("TLS").apply {
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(identity.keyStore, identity.password.toCharArray())
                init(kmf.keyManagers, null, null)
            }
            // The wildcard address on a dual-stack socket takes both IPv6 and IPv4 clients, which
            // matters because the whole reason this exists is reachability over IPv6.
            val socket = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                bind(InetSocketAddress(listenPort))
            }
            serverSocket = socket
            Log.i(TAG, "TLS proxy listening on :$listenPort -> 127.0.0.1:$targetPort")

            job = scope.launch(io) {
                while (isActive && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        if (isActive && !socket.isClosed) Log.w(TAG, "accept failed: ${t.message}")
                        break
                    }
                    launch(io) { relay(client) }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "TLS proxy failed to start", t)
            stop()
            false
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        job?.cancel()
        job = null
    }

    private suspend fun relay(client: Socket) = coroutineScopeRelay(client)

    private suspend fun coroutineScopeRelay(client: Socket) {
        var upstream: Socket? = null
        try {
            upstream = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 5_000)
            }
            client.tcpNoDelay = true

            val target = upstream
            kotlinx.coroutines.coroutineScope {
                // Both directions run concurrently. A large upload and the response that follows
                // it are not neatly sequential, and half-duplex relaying would deadlock on the
                // first request whose body outlives the response headers.
                val up = launch(io) { pipe(client.getInputStream(), target.getOutputStream()) }
                val down = launch(io) { pipe(target.getInputStream(), client.getOutputStream()) }
                up.join()
                down.cancel()
            }
        } catch (t: Throwable) {
            // Aborted connections are routine: a browser seeking a video closes mid-stream.
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                // Flushed per chunk: without it a streamed response sits in the buffer and video
                // playback stalls waiting for data the server already sent.
                output.flush()
            }
        } catch (t: Throwable) {
            // Either side closing ends the relay; the caller cleans up both sockets.
        }
    }
}
