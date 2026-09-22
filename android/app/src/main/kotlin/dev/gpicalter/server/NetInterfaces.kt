package dev.gpicalter.server

import java.net.Inet4Address
import java.net.Inet6Address
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
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val raw = addr.hostAddress ?: continue
                    when (addr) {
                        is Inet4Address -> out += Endpoint(labelFor(nif.name), raw)
                        is Inet6Address -> {
                            // Skip temporary privacy addresses: they rotate every few hours, so
                            // pairing with one produces a client that mysteriously stops working.
                            // The EUI-64 address (identifiable by its ff:fe marker) is stable for
                            // as long as the ISP keeps the same prefix.
                            if (!raw.contains("ff:fe", ignoreCase = true)) continue
                            // Strip any %scope suffix and bracket it, as a URL requires.
                            val host = "[" + raw.substringBefore('%') + "]"
                            out += Endpoint(labelFor(nif.name) + " (IPv6)", host)
                        }
                        else -> continue
                    }
                }
            }
        } catch (t: Throwable) {
            // Enumerating interfaces can fail transiently while the network reconfigures.
        }
        // Order is preference order for the client's failover probe: a LAN IPv4 address first
        // because it is the fastest path when at home, then anything that also works from outside.
        return out.sortedBy {
            when {
                it.label == TAILSCALE -> 1
                it.label.contains("IPv6") -> 2
                else -> 0
            }
        }
    }

    /**
     * The address to hand a client that is not on this Wi-Fi, or null when there is none.
     *
     * Tailscale ranks first because it is the only address here that works without the router being
     * reconfigured -- and on a connection behind carrier NAT it is the only one that works at all.
     * A global IPv6 address is next, and only reaches anything if the router has been told to allow
     * inbound traffic to it.
     *
     * A LAN address is never returned. Handing someone 192.168.x.y as a remote endpoint produces a
     * connection timeout they will read as "the server is down", which is worse than being told
     * plainly that there is no remote address yet.
     */
    fun remoteEndpoint(): Endpoint? {
        val all = endpoints()
        return all.firstOrNull { it.label == TAILSCALE }
            ?: all.firstOrNull { it.host.startsWith("[") }
    }

    /** Whether a Tailscale interface is up, which changes what remote access has to do. */
    fun hasTailscale(): Boolean = endpoints().any { it.label == TAILSCALE }

    private fun labelFor(name: String): String = when {
        name.startsWith("tailscale") -> TAILSCALE
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Mobile (usually unreachable: CGNAT)"
        name.startsWith("eth") -> "Ethernet"
        else -> name
    }

    private const val TAILSCALE = "Tailscale"
}
