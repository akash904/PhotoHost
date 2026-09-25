package dev.gpicalter.desktop

import dev.gpicalter.server.NetInterfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Entry point. With no arguments -- which is how PhotoHost.exe starts -- it opens the window; the
 * library folder can be chosen there.
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

    if ("headless" in opts) {
        val server = DesktopServer(config)
        if (!runBlocking { server.start() }) {
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

    runWithWindow(config)
}

/**
 * The normal way in: double-clicking PhotoHost.exe. The window comes up first and the server behind
 * it, so a server that fails to start still has somewhere to say why.
 */
private fun runWithWindow(config: Config) {
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var server = DesktopServer(config)
    val lock = Any()

    lateinit var window: PairingWindow
    window = PairingWindow(
        scope = uiScope,
        onChangeLibrary = { folder ->
            window.setBusy("Switching library...")
            uiScope.launch {
                // Serialised: two quick changes must not leave two servers fighting over one port.
                synchronized(lock) {
                    server.stop()
                    config.libraryRoot = folder
                    server = DesktopServer(config)
                    window.attach(server)
                }
                server.start()
            }
        },
        onQuit = {
            synchronized(lock) { server.stop() }
            window.close()
            exitProcess(0)
        },
    )
    javax.swing.SwingUtilities.invokeAndWait { window.show() }
    window.attach(server)
    runBlocking { server.start() }
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
