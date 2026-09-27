package io.github.akash904.photohost.desktop

import io.github.akash904.photohost.core.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

private const val TAG = "gpic"

/**
 * One PhotoHost per data directory.
 *
 * Without this, opening PhotoHost while it already sits in the tray started a second server, which
 * failed on the port with "already in use" -- correct, and baffling to anyone who had just clicked
 * the icon. Now the second launch asks the first to show its window, and exits.
 *
 * The lock is an OS file lock, so it is released however the first instance ends, crash included;
 * a stale lock file from a previous run is harmless. The request travels over a loopback socket
 * whose port is written beside the lock, and nothing is accepted from anywhere but 127.0.0.1.
 */
class SingleInstance(private val dataDir: File) {

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True when this process is the one PhotoHost; false when another already holds the lock. */
    fun acquire(): Boolean {
        dataDir.mkdirs()
        val ch = FileChannel.open(File(dataDir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val l = runCatching { ch.tryLock() }.getOrNull()
        if (l == null) {
            ch.close()
            return false
        }
        channel = ch
        lock = l
        return true
    }

    /** Starts listening for later launches. [onShow] runs whenever one asks for the window. */
    fun listen(onShow: () -> Unit) {
        val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = socket
        File(dataDir, "instance.port").writeText(socket.localPort.toString())
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        it.soTimeout = 2_000
                        val line = it.getInputStream().bufferedReader().readLine()
                        if (line == SHOW) onShow()
                    }
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    /** From a second launch: ask the running PhotoHost to show itself. True if it answered. */
    fun signalRunning(): Boolean {
        val port = runCatching { File(dataDir, "instance.port").readText().trim().toInt() }.getOrNull() ?: return false
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
                s.getOutputStream().write("$SHOW\n".toByteArray())
                s.getOutputStream().flush()
            }
            true
        }.onFailure { Log.w(TAG, "PhotoHost is running but did not answer: ${it.message}") }.getOrDefault(false)
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    private companion object {
        const val SHOW = "show"
    }
}
