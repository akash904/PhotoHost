package dev.gpicalter.net

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "gpic"

/** Requested lease. Renewed at half of it, which absorbs one missed renewal without an outage. */
private const val LEASE_SECONDS = 3600

/** Observable remote-access state, for the Settings screen. */
object RemoteState {

    data class Snapshot(
        val attempting: Boolean = false,
        val attemptedAt: Long = 0L,
        val opened: Boolean = false,
        val via: String? = null,
        val summary: String? = null,
        /** The router's own WAN address, when it will say. Compare against what the internet sees. */
        val routerWanIpv4: String? = null,
        val carrierNat: Boolean = false,
        /** The address a remote client should dial. */
        val publicUrl: String? = null,
        val lines: List<String> = emptyList(),
        val testing: Boolean = false,
        val testResult: String? = null,
        val testPassed: Boolean? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) = _state.update(transform)
}

/**
 * Makes the library reachable from outside the home network, with as little asked of the user as the
 * router permits.
 *
 * ### The shape of the problem
 * For an unsolicited connection to cross a home router, exactly one of two things must be true: the
 * border was told to permit it, or both ends dial out to a meeting point in the middle. There is no
 * third mechanism. This class pursues the first, because the second means running an intermediary.
 *
 * Telling the border can be done by a person editing a firewall table, or by the app asking over one
 * of the three protocols that exist for asking -- see [PortMapper]. Asking is strictly better: it
 * needs no admin password, survives the phone changing address, and works the same on every router
 * that supports it. When no protocol is available this reports that plainly instead of leaving the
 * user to wonder, because the honest next step then is a one-time router change or a VPN, and
 * pretending otherwise just wastes their evening.
 *
 * ### Why it is opt-in
 * Opening a port to the internet is a real decision. What protects the library once it is open is the
 * bearer token and the pinned certificate, not the fact that nobody knows the port -- but the user
 * should still be the one who chooses to expose it.
 */
class RemoteAccess(private val context: Context) {

    private var renewal: Job? = null

    /**
     * Asks the router to open [port], and records exactly what happened.
     *
     * Runs on IO: discovery waits on UDP timeouts and the SOAP calls are blocking.
     */
    suspend fun open(port: Int): PortMapper.Report = withContext(Dispatchers.IO) {
        RemoteState.update { it.copy(attempting = true) }
        val lan = lanIpv4()
        val v6 = stableGlobalIpv6()
        val gateways = gateways()
        Log.i(TAG, "remote access: lan=$lan ipv6=$v6 gateways=${gateways.map { it.hostAddress }}")

        val report = PortMapper.open(
            port = port,
            lanIpv4 = lan,
            globalIpv6 = v6,
            gateways = gateways,
            leaseSeconds = LEASE_SECONDS,
        )

        // Prefer IPv6 for the advertised URL. It needs no address translation, so the address the
        // client dials is literally this phone rather than the router standing in for it.
        val publicHost = when {
            v6 != null -> "[$v6]"
            report.externalIpv4 != null && !PortMapper.isCarrierNat(report.externalIpv4) ->
                report.externalIpv4
            else -> null
        }

        RemoteState.update {
            it.copy(
                attempting = false,
                attemptedAt = System.currentTimeMillis(),
                opened = report.opened,
                via = report.via,
                summary = report.summary,
                routerWanIpv4 = report.externalIpv4,
                carrierNat = report.externalIpv4?.let(PortMapper::isCarrierNat) ?: false,
                publicUrl = publicHost?.let { host -> "https://$host:$port" },
                lines = report.lines,
            )
        }
        report
    }

    /**
     * Keeps the opening alive for as long as the server runs.
     *
     * Leases expire by design, and re-requesting is also how the mapping recovers from a router
     * reboot or a new IPv6 prefix from the ISP -- both of which silently invalidate it.
     */
    fun keepOpen(scope: CoroutineScope, port: Int) {
        renewal?.cancel()
        renewal = scope.launch {
            while (isActive) {
                runCatching { open(port) }
                delay(LEASE_SECONDS * 1000L / 2)
            }
        }
    }

    fun stop() {
        renewal?.cancel()
        renewal = null
    }

    /**
     * Confirms reachability from outside by dialling in over the cellular network.
     *
     * @param port the TLS port.
     * @param fingerprint the server certificate's fingerprint, so a reply can be proven to be ours.
     */
    suspend fun test(port: Int, fingerprint: String?) {
        val host = RemoteState.state.value.publicUrl
            ?.substringAfter("https://")
            ?.substringBeforeLast(':')
            ?: stableGlobalIpv6()?.let { "[$it]" }

        if (host == null) {
            RemoteState.update {
                it.copy(
                    testing = false,
                    testPassed = false,
                    testResult = "No public address on this phone. " +
                        "Without IPv6 or a public IPv4 there is nothing for a remote client to dial.",
                )
            }
            return
        }

        RemoteState.update { it.copy(testing = true, testResult = null, testPassed = null) }
        val result = RemoteProbe(context).overCellular(host, port, fingerprint)
        val (passed, text) = when (result) {
            is RemoteProbe.Result.Reachable -> if (result.matched) {
                true to "Reachable from the internet in ${result.millis} ms, and the certificate matches."
            } else {
                // Something answered on that address and port, but presented a different key. On
                // IPv4 behind carrier NAT this is what reaching another customer looks like.
                false to "Something answered but it is not this server " +
                    "(certificate ${result.fingerprint.take(16)}...). Do not pair against this address."
            }

            is RemoteProbe.Result.Blocked ->
                false to "Not reachable: ${result.detail}. The router is still dropping inbound connections."

            is RemoteProbe.Result.NoCellular ->
                false to "Test did not run: ${result.detail}"
        }
        RemoteState.update { it.copy(testing = false, testPassed = passed, testResult = text) }
    }

    // ------------------------------------------------------------------ addresses

    /** This phone's private address on the LAN, the target of an IPv4 port mapping. */
    fun lanIpv4(): String? = interfaceAddresses()
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress

    /**
     * The stable global IPv6 address, which is the one worth opening a pinhole for.
     *
     * Android also holds temporary privacy addresses that rotate every few hours. A pinhole or a
     * certificate naming one of those works until it rotates and then fails in a way that looks like
     * the server going down. The EUI-64 address, recognisable by its `ff:fe` marker, lasts as long as
     * the ISP keeps the prefix.
     */
    fun stableGlobalIpv6(): String? = interfaceAddresses()
        .filterIsInstance<Inet6Address>()
        .asSequence()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        // Unique-local addresses (fc00::/7) are not routable on the internet, so they are no use here.
        .filterNot { it.startsWith("fc", ignoreCase = true) || it.startsWith("fd", ignoreCase = true) }
        .firstOrNull { it.contains("ff:fe", ignoreCase = true) }

    private fun interfaceAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Default-route gateways, for the two UDP protocols that have no discovery mechanism and must be
     * addressed directly.
     */
    private fun gateways(): List<InetAddress> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
            .filterNot { it.isAnyLocalAddress }
            .distinct()
    }
}
