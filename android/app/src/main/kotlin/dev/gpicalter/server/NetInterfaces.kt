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
                val addresses = nif.inetAddresses.toList()

                if (isTailscale(nif, addresses)) {
                    // The 100.x address is the one Tailscale shows the user and the one that goes
                    // in a pairing code. The unique-local address works too, but only as a fallback
                    // worth having if a tailnet ever hands out no IPv4.
                    val preferred = addresses.filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                        ?: addresses.filterIsInstance<Inet6Address>()
                            .mapNotNull { it.hostAddress }
                            .firstOrNull { it.startsWith(TAILSCALE_ULA, ignoreCase = true) }
                            ?.let { "[" + it.substringBefore('%') + "]" }
                    preferred?.let { out += Endpoint(TAILSCALE, it) }
                    continue
                }

                for (addr in addresses) {
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

    /**
     * The endpoints worth listing on screen.
     *
     * When Tailscale is up it is the way in from outside, and the global IPv6 address becomes noise:
     * it is long, it changes whenever the ISP re-delegates the prefix, and behind a router that
     * drops inbound traffic it reaches nothing. Showing it only invites pairing against an address
     * that cannot work and will not stay put.
     *
     * Without Tailscale it is kept, because it is then the only candidate for reaching the library
     * from outside, and hiding the only option is worse than showing a difficult one.
     *
     * [endpoints] stays unfiltered, since the certificate should name every address the server might
     * be reached at whether or not the address is worth putting in front of a person.
     */
    fun displayEndpoints(): List<Endpoint> {
        val all = endpoints()
        if (all.none { it.label == TAILSCALE }) return all
        return all.filterNot { it.label.contains("IPv6") }
    }

    /** Whether a Tailscale interface is up, which changes what remote access has to do. */
    fun hasTailscale(): Boolean = endpoints().any { it.label == TAILSCALE }

    /**
     * Recognises Tailscale's interface by the addresses on it rather than its name.
     *
     * The name is not dependable: on Android the client is a `VpnService`, so the kernel calls it
     * `tun0` or `tun1` depending on what else has held a VPN slot this boot, and only desktop
     * builds produce `tailscale0`. Matching on the name silently fails on exactly the platform this
     * app runs on.
     *
     * The unique-local prefix is dependable. `fd7a:115c:a1e0::/48` is Tailscale's, appears on every
     * node, and belongs to nothing else. Checking it also avoids mistaking a phone whose carrier
     * puts it behind NAT for a tailnet: that interface carries a 100.64/10 address too, but never
     * this prefix.
     */
    private fun isTailscale(nif: NetworkInterface, addresses: List<java.net.InetAddress>): Boolean =
        nif.name.startsWith("tailscale") ||
            addresses.any { it.hostAddress?.startsWith(TAILSCALE_ULA, ignoreCase = true) == true }

    private fun labelFor(name: String): String = when {
        name.startsWith("tailscale") -> TAILSCALE
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Mobile (usually unreachable: CGNAT)"
        name.startsWith("eth") -> "Ethernet"
        else -> name
    }

    private const val TAILSCALE = "Tailscale"

    /** Tailscale's assigned unique-local prefix, the one unmistakable marker of a tailnet. */
    private const val TAILSCALE_ULA = "fd7a:115c:a1e0"
}
