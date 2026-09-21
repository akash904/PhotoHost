package dev.gpicalter.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses the server is actually reachable on, so the app can print exact URLs instead of
 * making you hunt for the phone's IP.
 *
 * Tailscale shows up as its own interface (`tailscale0`). That address is the one that works off
 * the LAN, which matters because a phone on mobile data sits behind carrier CGNAT and cannot
 * accept inbound connections at all.
 */
object NetInterfaces {

    data class Endpoint(val label: String, val host: String) {
        fun url(port: Int) = "http://$host:$port"
    }

    fun endpoints(): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    out += Endpoint(label = labelFor(nif.name), host = host)
                }
            }
        } catch (t: Throwable) {
            // Enumerating interfaces can fail transiently while the network reconfigures.
        }
        // Tailscale first: it is the address that still works when you are not on the LAN.
        return out.sortedByDescending { it.label == TAILSCALE }
    }

    private fun labelFor(name: String): String = when {
        name.startsWith("tailscale") -> TAILSCALE
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Mobile (usually unreachable: CGNAT)"
        name.startsWith("eth") -> "Ethernet"
        else -> name
    }

    private const val TAILSCALE = "Tailscale"
}
