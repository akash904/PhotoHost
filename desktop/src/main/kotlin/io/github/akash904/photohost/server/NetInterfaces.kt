package io.github.akash904.photohost.server

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

                // Desktop only, after the Tailscale check because Tailscale is itself a virtual
                // adapter and is wanted. See [isHostOnlyAdapter].
                if (isHostOnlyAdapter(nif)) continue

                // A VPN that presents itself as an Ethernet card keeps its own name as the label,
                // so [displayEndpoints] leaves it out like any other VPN. See [isVpnAdapter].
                val label = if (isVpnAdapter(nif)) nif.name else labelFor(nif.name)
                for (addr in addresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val raw = addr.hostAddress ?: continue
                    when (addr) {
                        is Inet4Address -> out += Endpoint(label, raw)
                        is Inet6Address -> {
                            // Skip temporary privacy addresses: they rotate every few hours, so
                            // pairing with one produces a client that mysteriously stops working.
                            // The EUI-64 address (identifiable by its ff:fe marker) is stable for
                            // as long as the ISP keeps the same prefix.
                            if (!raw.contains("ff:fe", ignoreCase = true)) continue
                            // Strip any %scope suffix and bracket it, as a URL requires.
                            val host = "[" + raw.substringBefore('%') + "]"
                            out += Endpoint("$label (IPv6)", host)
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
     * Tailscale, or nothing.
     *
     * Global IPv6 used to be the fallback here, on the reasoning that it is the only other address
     * that could reach the library from outside. In practice it reaches nothing and does not stay
     * put: this ISP re-delegates the prefix roughly daily -- observed moving overnight -- and the
     * ONT drops inbound traffic to it anyway. Returning it made it the pairing code's primary
     * address whenever Tailscale was down, so a phone standing on the same Wi-Fi as the server
     * paired against the least stable address available and then routed over it, while a LAN
     * address that is equally encrypted and does not move sat unused in the same code.
     *
     * Returning null instead lets the caller fall through to the ordinary preference order, which
     * already puts the LAN first. "No remote address" is the honest answer when Tailscale is off.
     *
     * A LAN address is never returned from here. Handing someone 192.168.x.y as a remote endpoint
     * produces a connection timeout they will read as "the server is down", which is worse than
     * being told plainly that there is no remote address yet.
     */
    fun remoteEndpoint(): Endpoint? = endpoints().firstOrNull { it.label == TAILSCALE }

    /**
     * The endpoints worth listing on screen.
     *
     * Never the global IPv6 address, whether or not Tailscale is up.
     *
     * It was previously kept when Tailscale was down, on the grounds that hiding the only way in
     * from outside is worse than showing a difficult one. That was the wrong trade, because it is
     * not a way in: the ONT drops inbound traffic to it, and the prefix moves about once a day, so
     * anything paired against it stops working by morning. Keeping it also cost QR density, on a
     * code that has to scan off a glowing screen.
     *
     * Dropping it loses nothing real. Remote access is Tailscale; at home the LAN address is
     * shorter, stable, and available over TLS just the same.
     *
     * [endpoints] stays unfiltered, so the certificate still names the IPv6 address. It costs
     * nothing there, and it means typing that address by hand still validates for anyone whose
     * network does route it.
     *
     * DIVERGES FROM the phone app: on a PC, only Wi-Fi, wired Ethernet and Tailscale. A PC is often on a
     * work VPN as well -- observed on the development PC: a dial-up style VPN, `ppp_0` at 10.8.0.2,
     * beside Wi-Fi. That address reaches the office network, not this PC from a phone, yet it went
     * into every pairing code and every client's failover list, and could even become the code's
     * primary address when Tailscale was down. An allowlist rather than a list of VPNs to exclude,
     * because VPN clients are many and new ones appear; a network that is neither Wi-Fi, Ethernet nor
     * Tailscale is not one a phone reaches this PC over.
     */
    fun displayEndpoints(): List<Endpoint> =
        endpoints().filter { it.label in ADVERTISED }

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

    /**
     * Adapters that exist only between this PC and virtual machines on it: Hyper-V's Default Switch
     * (which Windows creates whenever Hyper-V, WSL or Docker is installed), VirtualBox and VMware.
     *
     * DIVERGES FROM the phone app, where no such adapters exist. On a PC they are up, carry an ordinary
     * private IPv4 address, and sort level with the real LAN address -- so without this filter the
     * pairing code could lead with 172.x on the Hyper-V switch, which no phone can reach, and every
     * failover sweep would spend a probe timeout on it. Observed on the development PC:
     * `vEthernet (Default Switch)` at 172.20.0.1 beside Wi-Fi at 192.168.1.20.
     *
     * Matched on the adapter description, which is what Java reports as the display name on Windows.
     */
    private fun isHostOnlyAdapter(nif: NetworkInterface): Boolean {
        val desc = (nif.displayName ?: "") + " " + nif.name
        return HOST_ONLY_MARKERS.any { desc.contains(it, ignoreCase = true) }
    }

    /**
     * VPN clients that present an Ethernet adapter, which Java then names `ethernet_NNNNN` like a
     * real network card. Recognised by the adapter description, which on Windows is what Java
     * reports as the display name. VPNs that appear as `ppp_` or tunnel adapters need no entry:
     * their names are not Wi-Fi or Ethernet to begin with.
     */
    private fun isVpnAdapter(nif: NetworkInterface): Boolean {
        val desc = nif.displayName ?: return false
        return VPN_MARKERS.any { desc.contains(it, ignoreCase = true) }
    }

    private val VPN_MARKERS = listOf(
        "VPN",
        "TAP-Windows",
        "TAP Adapter",
        "WireGuard",
        "Wintun",
        "OpenVPN",
        "AnyConnect",
        "Fortinet",
        "PANGP",
        "GlobalProtect",
        "Juniper",
        "Zscaler",
        "WAN Miniport",
    )

    /** The labels [displayEndpoints] keeps: networks a phone can reach this PC over. */
    private val ADVERTISED = setOf("Wi-Fi", "Ethernet", "Tailscale")

    private val HOST_ONLY_MARKERS = listOf(
        "Hyper-V",
        "vEthernet",
        "VirtualBox",
        "VMware",
        "WSL",
        "Docker",
    )

    private fun labelFor(name: String): String = when {
        name.startsWith("tailscale") -> TAILSCALE
        name.startsWith("wlan") -> "Wi-Fi"
        // Desktop: Java on Windows names adapters `wireless_32768`, `ethernet_32768` (observed on
        // JBR 25). `ethernet_` is already caught by the "eth" rule below.
        name.startsWith("wireless") -> "Wi-Fi"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "Mobile (usually unreachable: CGNAT)"
        name.startsWith("eth") -> "Ethernet"
        else -> name
    }

    private const val TAILSCALE = "Tailscale"

    /** Tailscale's assigned unique-local prefix, the one unmistakable marker of a tailnet. */
    private const val TAILSCALE_ULA = "fd7a:115c:a1e0"
}
