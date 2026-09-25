package dev.gpicalter.desktop

import dev.gpicalter.server.NetInterfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Entry point.
 *
 *     photohost [--library <dir>] [--data <dir>] [--port <n>] [--headless] [--print-endpoints]
 *
 * `--library` and `--port` are remembered in config.properties, so they are needed once.
 * `--headless` runs without the window (the server stops with the process).
 * `--print-endpoints` lists the addresses that would be advertised, then exits -- for checking what
 * a PC's network adapters look like to the server without starting it.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)

    if ("print-endpoints" in opts) {
        java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
            val addrs = nif.inetAddresses.toList().joinToString { it.hostAddress }
            println("iface ${nif.name} up=${nif.isUp} '${nif.displayName}' [$addrs]")
        }
        println("--- advertised ---")
        NetInterfaces.displayEndpoints().forEach { println("${it.label}: ${it.host}") }
        return
    }

    val config = Config.load(opts["data"]?.let(::File) ?: Config.defaultDataDir())
    opts["library"]?.let { config.libraryRoot = File(it) }
    opts["port"]?.toIntOrNull()?.let { config.port = it }

    val server = DesktopServer(config)
    val started = runBlocking { server.start() }

    if ("headless" in opts) {
        if (!started) {
            System.err.println(server.status.value.error)
            exitProcess(1)
        }
        println("PhotoHost serving ${config.libraryRoot}")
        println("Pairing link: ${server.status.value.pairingLink}")
        val done = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(); done.countDown() })
        done.await()
        return
    }

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var window: PairingWindow
    window = PairingWindow(server, uiScope) {
        server.stop()
        window.close()
        exitProcess(0)
    }
    javax.swing.SwingUtilities.invokeLater { window.show() }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = args.getOrNull(i + 1)
            if (value != null && !value.startsWith("--")) {
                out[key] = value
                i += 2
                continue
            }
            out[key] = "true"
        }
        i++
    }
    return out
}
