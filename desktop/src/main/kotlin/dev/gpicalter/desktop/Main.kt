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

    runWithWindow(config, minimized = "minimized" in opts)
}

/**
 * The normal way in: double-clicking PhotoHost.exe, or a start at sign-in with `--minimized`. The
 * window comes up first and the server behind it, so a server that fails to start still has
 * somewhere to say why.
 */
private fun runWithWindow(config: Config, minimized: Boolean) {
    // Already running (most likely in the tray): bring that one forward instead of starting a second
    // server that could only fail on the port.
    val instance = SingleInstance(config.dataDir)
    if (!instance.acquire()) {
        if (!instance.signalRunning()) {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "PhotoHost is already running but is not responding.\nClose it from the tray, or restart the PC.",
                "PhotoHost", javax.swing.JOptionPane.WARNING_MESSAGE,
            )
        }
        exitProcess(0)
    }

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var server = DesktopServer(config)
    val lock = Any()
    var toldAboutTray = false

    lateinit var window: PairingWindow
    lateinit var tray: Tray
    val quit = {
        synchronized(lock) { server.stop() }
        tray.remove()
        window.close()
        instance.release()
        exitProcess(0)
    }
    tray = Tray(
        onOpen = { window.bringToFront() },
        onOpenBrowser = { window.openInBrowser() },
        onQuit = { quit() },
    )
    window = PairingWindow(
        scope = uiScope,
        onClose = {
            if (tray.available) {
                window.hide()
                // Once per run: a window that disappears while its server carries on needs saying.
                if (!toldAboutTray) {
                    toldAboutTray = true
                    tray.notify("PhotoHost is still running", "Your library is still available to your phones. Quit from this icon.")
                }
            } else {
                quit()
            }
        },
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
        onQuit = { quit() },
    )
    val hasTray = tray.install()
    instance.listen { window.bringToFront() }
    Autostart.refreshIfEnabled()

    // Hidden at sign-in only when there is a tray to find it in; otherwise it could not be reached.
    javax.swing.SwingUtilities.invokeAndWait { window.show(startHidden = minimized && hasTray) }
    window.attach(server)
    runBlocking { server.start() }
    // Follows whichever server is current, since a library switch replaces it.
    uiScope.launch {
        while (true) {
            val s = server.status.value
            tray.tooltip(
                when {
                    s.error != null -> "PhotoHost: not running"
                    s.running -> "PhotoHost: serving ${s.assets} photos and videos"
                    else -> "PhotoHost: starting"
                },
            )
            kotlinx.coroutines.delay(5_000)
        }
    }
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
