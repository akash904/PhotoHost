package dev.gpicalter.net

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom

private const val TAG = "gpic"
private const val SSDP_HOST = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val PCP_PORT = 5351

/**
 * IPv4 port-mapping services, newest first. A router offers one of these, not all three, and the
 * name differs by whether its WAN link is PPPoE.
 */
private val IPV4_SERVICES = listOf(
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
)

/** IPv6 is a firewall pinhole rather than a mapping, so it is a different service entirely. */
private const val IPV6_SERVICE = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1"

/**
 * Asks the router to accept inbound connections on our port, so nobody has to open an admin page.
 *
 * ### Why this exists
 * A home router drops unsolicited inbound traffic. On IPv4 it has no choice -- many devices share one
 * public address, and an arriving packet carries nothing that says which device it belongs to. On
 * IPv6 every device has its own globally unique address, so the router knows exactly where a packet
 * belongs and is merely declining to forward it; that is firewall policy, and a good default.
 *
 * Either way the border has to be told. The alternative to telling it programmatically is talking a
 * person through a firewall table, which is where most people abandon self-hosting -- so this tries
 * all three protocols that exist for asking:
 *
 *  - **UPnP IGD** (2001): multicast discovery, then XML-over-HTTP. Ubiquitous, frequently disabled.
 *    `AddPortMapping` for IPv4, `AddPinhole` for IPv6.
 *  - **NAT-PMP** (Apple, 2005): two-byte requests on UDP 5351. IPv4 only.
 *  - **PCP** (RFC 6887): NAT-PMP's successor, handles IPv6 properly.
 *
 * ### What it deliberately does not do
 * It never reports success it did not observe. A router that answers discovery and then refuses the
 * mapping is a different situation from one that never answers at all, and the person debugging this
 * needs to know which they have -- so [Report.lines] records every step and [Report.opened] is set
 * only by an actual success response.
 *
 * All methods block on network I/O; call them from a background dispatcher.
 */
object PortMapper {

    /**
     * @param opened true only if the router confirmed a mapping or pinhole.
     * @param via which protocol succeeded, for display.
     * @param externalIpv4 the WAN address the router reports, when it will say. Worth surfacing
     *   because if this differs from the address the internet sees, the connection is behind
     *   carrier-grade NAT and no amount of port mapping will help.
     * @param lines an ordered trace of what was tried and what came back.
     */
    data class Report(
        val opened: Boolean,
        val via: String?,
        val externalIpv4: String?,
        val leaseSeconds: Int,
        val lines: List<String>,
    ) {
        val summary: String
            get() = when {
                opened -> "Opened via $via"
                lines.any { it.startsWith("gateway:") } -> "Router found but it refused"
                else -> "No router support for automatic opening"
            }
    }

    /**
     * @param port the port to open. The same number is used inside and outside: a mismatch would
     *   mean the pairing QR advertises one thing and the router expects another.
     * @param lanIpv4 this phone's private address, the target of an IPv4 mapping.
     * @param globalIpv6 this phone's stable global address, the target of an IPv6 pinhole.
     * @param gateways candidate router addresses, for the two UDP protocols which have no discovery.
     * @param leaseSeconds requested lifetime. Leases are finite on purpose -- a permanent mapping
     *   outlives the app that wanted it and becomes an opening nobody remembers making.
     */
    fun open(
        port: Int,
        lanIpv4: String?,
        globalIpv6: String?,
        gateways: List<InetAddress>,
        leaseSeconds: Int = 3600,
    ): Report {
        val lines = ArrayList<String>()
        var opened = false
        var via: String? = null
        var externalIpv4: String? = null

        // ---- UPnP first: it is the only one of the three that can open an IPv6 pinhole on
        // ---- consumer firmware, and IPv6 is the path that avoids NAT altogether.
        val locations = ssdpSearch(lanIpv4, lines)
        val described = locations.mapNotNull { describe(it, lines) }
        val routers = described.filter { it.isGateway }
        for (other in described.filterNot { it.isGateway }) {
            lines += "${other.label}: not a router, ignored"
        }
        if (described.isNotEmpty() && routers.isEmpty()) {
            lines += "no router among the devices that replied"
        }
        for (description in routers) {
            val services = description.services
            lines += "gateway: ${description.label}, ${services.size} service(s)"

            if (!opened && globalIpv6 != null) {
                services.firstOrNull { it.type == IPV6_SERVICE }?.let { svc ->
                    if (addPinhole(svc, globalIpv6, port, leaseSeconds, lines)) {
                        opened = true
                        via = "UPnP IPv6 pinhole"
                    }
                }
            }
            if (!opened && lanIpv4 != null) {
                for (type in IPV4_SERVICES) {
                    val svc = services.firstOrNull { it.type == type } ?: continue
                    externalIpv4 = externalIpv4 ?: externalAddress(svc, lines)
                    if (addPortMapping(svc, lanIpv4, port, leaseSeconds, lines)) {
                        opened = true
                        via = "UPnP IPv4 port mapping"
                        break
                    }
                }
            }
            if (opened) break
        }

        // ---- PCP, then NAT-PMP. Both are unicast to the router, so they can work on firmware that
        // ---- never answers multicast discovery.
        if (!opened) {
            for (gateway in gateways) {
                if (globalIpv6 != null && pcpMap(gateway, globalIpv6, port, leaseSeconds, lines)) {
                    opened = true
                    via = "PCP"
                    break
                }
                if (lanIpv4 != null && pcpMap(gateway, lanIpv4, port, leaseSeconds, lines)) {
                    opened = true
                    via = "PCP"
                    break
                }
                externalIpv4 = externalIpv4 ?: natPmpExternal(gateway, lines)
                if (natPmpMap(gateway, port, leaseSeconds, lines)) {
                    opened = true
                    via = "NAT-PMP"
                    break
                }
            }
        }

        if (!opened && locations.isEmpty() && gateways.isEmpty()) {
            lines += "no gateway address known; is this phone on Wi-Fi?"
        }
        Log.i(TAG, "port mapping: opened=$opened via=$via lines=${lines.size}")
        return Report(opened, via, externalIpv4, leaseSeconds, lines)
    }

    // ------------------------------------------------------------------ SSDP discovery

    /**
     * Multicasts "any internet gateways out there?" and collects the description URLs that answer.
     *
     * The interface is pinned explicitly when [lanIpv4] is known. A phone often has several routable
     * interfaces at once -- Wi-Fi, mobile data, sometimes a VPN -- and multicast sent out of the
     * wrong one is silently lost, which is indistinguishable from a router with no UPnP at all.
     */
    private fun ssdpSearch(lanIpv4: String?, lines: MutableList<String>): List<String> {
        val found = LinkedHashSet<String>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket()
            socket.soTimeout = 400
            interfaceFor(lanIpv4)?.let { nif ->
                runCatching { socket.networkInterface = nif }
                    .onFailure { lines += "could not pin multicast to ${nif.name}" }
            }
            val group = InetAddress.getByName(SSDP_HOST)
            val targets = listOf(
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "upnp:rootdevice",
            )
            for (target in targets) {
                val probe = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $target\r\n\r\n"
                    ).toByteArray()
                // Sent twice. SSDP is UDP with no retransmission, and one dropped datagram would
                // read as "this router has no UPnP".
                repeat(2) {
                    runCatching { socket.send(DatagramPacket(probe, probe.size, group, SSDP_PORT)) }
                }
            }
            val deadline = System.currentTimeMillis() + 3_000
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (t: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length)
                headerOf(text, "LOCATION")?.let { found += it }
            }
        } catch (t: Throwable) {
            lines += "discovery failed: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { socket?.close() }
        }
        lines += if (found.isEmpty()) {
            "UPnP discovery: no replies"
        } else {
            "UPnP discovery: ${found.size} device(s) replied"
        }
        return found.toList()
    }

    private fun interfaceFor(lanIpv4: String?): NetworkInterface? {
        if (lanIpv4 == null) return null
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
                nif.isUp && !nif.isLoopback &&
                    nif.inetAddresses.toList().any { it.hostAddress == lanIpv4 }
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ device description

    private data class Service(val type: String, val controlUrl: String)

    /**
     * A device's identity plus the services it offers.
     *
     * SSDP answers come from everything on the network that speaks UPnP -- televisions, speakers,
     * media servers -- not only routers. Knowing what replied is what lets the trace say "a TV
     * answered, your router did not", which is a different problem from "nothing answered".
     */
    private data class Description(
        val deviceType: String?,
        val friendlyName: String?,
        val services: List<Service>,
    ) {
        val isGateway: Boolean get() = deviceType?.contains("InternetGatewayDevice", true) == true
        val label: String get() = friendlyName ?: deviceType?.substringAfterLast(':') ?: "unknown device"
    }

    /**
     * Parses the router's description document into the services we can act on.
     *
     * `controlURL` is usually a relative path, so it is resolved against `URLBase` when the document
     * supplies one and against the description URL otherwise -- the rule the UPnP spec sets out.
     */
    private fun describe(location: String, lines: MutableList<String>): Description? {
        val xml = httpGet(location) ?: run {
            lines += "$location did not answer"
            return null
        }
        val services = ArrayList<Service>()
        var deviceType: String? = null
        var friendlyName: String? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var base: String? = null
            var tag: String? = null
            var type: String? = null
            var control: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (parser.name == "service") {
                            type = null
                            control = null
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) when (tag) {
                            "URLBase" -> base = text
                            "serviceType" -> type = text
                            "controlURL" -> control = text
                            // The outermost device element is the one that matters; nested embedded
                            // devices would otherwise overwrite it with a sub-device's identity.
                            "deviceType" -> deviceType = deviceType ?: text
                            "friendlyName" -> friendlyName = friendlyName ?: text
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val currentType = type
                        val currentControl = control
                        if (parser.name == "service" && currentType != null && currentControl != null) {
                            services += Service(currentType, absolute(base ?: location, currentControl))
                        }
                        tag = null
                    }
                }
            }
        } catch (t: Throwable) {
            // Plenty of devices answer SSDP with something that is not a UPnP description. That is
            // their business, not a fault worth showing a parser exception for.
            lines += "$location is not a UPnP device description"
            return null
        }
        return Description(deviceType, friendlyName, services)
    }

    private fun absolute(base: String, ref: String): String =
        runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    // ------------------------------------------------------------------ UPnP actions

    /**
     * Opens an IPv6 firewall pinhole straight to this phone.
     *
     * `RemotePort` 0 and an empty `RemoteHost` mean "from anywhere", which is what a library reached
     * from an arbitrary phone on an arbitrary network needs. What stands between that opening and
     * the library is the bearer token and the pinned certificate, not obscurity about the port.
     *
     * The spec caps `LeaseTime` at 86400 seconds, so a longer request is clamped rather than sent
     * and rejected.
     */
    private fun addPinhole(
        service: Service,
        internalClient: String,
        port: Int,
        leaseSeconds: Int,
        lines: MutableList<String>,
    ): Boolean {
        status(service, lines)
        val reply = soap(
            service, "AddPinhole",
            listOf(
                "RemoteHost" to "",
                "RemotePort" to "0",
                "Protocol" to "6", // TCP, as IANA numbers it
                "InternalClient" to internalClient,
                "InternalPort" to port.toString(),
                "LeaseTime" to leaseSeconds.coerceIn(1, 86_400).toString(),
            ),
        )
        return if (reply != null && reply.code == 200) {
            lines += "AddPinhole ok for [$internalClient]:$port"
            true
        } else {
            lines += "AddPinhole refused: ${describe(reply)}"
            false
        }
    }

    /** Reports whether the router admits to allowing pinholes, purely so the trace explains a refusal. */
    private fun status(service: Service, lines: MutableList<String>) {
        val reply = soap(service, "GetFirewallStatus", emptyList()) ?: return
        if (reply.code != 200) return
        val enabled = valueOf(reply.body, "FirewallEnabled")
        val allowed = valueOf(reply.body, "InboundPinholeAllowed")
        lines += "firewall: enabled=$enabled pinholesAllowed=$allowed"
    }

    /**
     * Maps an external port to this phone on IPv4.
     *
     * Retried with a zero lease on failure. A finite lease is the better choice, but a good number
     * of routers only ever implement permanent mappings and reject any non-zero duration outright,
     * so trying the safe form first and the blunt one second is what actually works in the field.
     */
    private fun addPortMapping(
        service: Service,
        internalClient: String,
        port: Int,
        leaseSeconds: Int,
        lines: MutableList<String>,
    ): Boolean {
        for (lease in listOf(leaseSeconds, 0)) {
            val reply = soap(
                service, "AddPortMapping",
                listOf(
                    "NewRemoteHost" to "",
                    "NewExternalPort" to port.toString(),
                    "NewProtocol" to "TCP",
                    "NewInternalPort" to port.toString(),
                    "NewInternalClient" to internalClient,
                    "NewEnabled" to "1",
                    "NewPortMappingDescription" to "gpicAlter",
                    "NewLeaseDuration" to lease.toString(),
                ),
            )
            if (reply != null && reply.code == 200) {
                lines += "AddPortMapping ok for $internalClient:$port (lease ${lease}s)"
                return true
            }
            lines += "AddPortMapping refused (lease ${lease}s): ${describe(reply)}"
        }
        return false
    }

    /**
     * Asks the router what public IPv4 it holds.
     *
     * This is the carrier-NAT test. If the router reports a private or shared address -- `100.64/10`
     * especially -- then the public address the internet sees belongs to the ISP, is shared with
     * other customers, and no port mapping can ever make this phone reachable over IPv4.
     */
    private fun externalAddress(service: Service, lines: MutableList<String>): String? {
        val reply = soap(service, "GetExternalIPAddress", emptyList()) ?: return null
        if (reply.code != 200) return null
        val address = valueOf(reply.body, "NewExternalIPAddress") ?: return null
        lines += "router WAN address: $address" + if (isCarrierNat(address)) " (carrier NAT)" else ""
        return address
    }

    /** True for the shared-address range reserved for carrier-grade NAT, and for private ranges. */
    fun isCarrierNat(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return when {
            a == 100 && b in 64..127 -> true // RFC 6598, the CGNAT range
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    private data class Reply(val code: Int, val body: String)

    private fun soap(service: Service, action: String, args: List<Pair<String, String>>): Reply? {
        val envelope = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(service.type).append("\">")
            for ((name, value) in args) {
                append('<').append(name).append('>').append(escape(value))
                append("</").append(name).append('>')
            }
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        return try {
            val connection = (URL(service.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 6_000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${service.type}#$action\"")
            }
            connection.outputStream.use { it.write(envelope.toByteArray()) }
            val code = connection.responseCode
            // A UPnP fault arrives as HTTP 500 with the reason in the body, so the error stream is
            // as interesting as the success stream.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            Reply(code, body)
        } catch (t: Throwable) {
            null
        }
    }

    private fun describe(reply: Reply?): String {
        if (reply == null) return "no response"
        val code = valueOf(reply.body, "errorCode")
        val text = valueOf(reply.body, "errorDescription")
        return when {
            code != null -> "HTTP ${reply.code}, UPnP $code ${text.orEmpty()}".trim()
            else -> "HTTP ${reply.code}"
        }
    }

    /** Reads an element's text without a full parse; UPnP replies are flat and this cannot nest. */
    private fun valueOf(xml: String, tag: String): String? {
        val open = xml.indexOf("<$tag", ignoreCase = true, startIndex = 0)
        if (open < 0) return null
        val gt = xml.indexOf('>', open)
        if (gt < 0) return null
        val close = xml.indexOf("</", gt)
        if (close < 0) return null
        return xml.substring(gt + 1, close).trim().ifEmpty { null }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun httpGet(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        body
    } catch (t: Throwable) {
        null
    }

    private fun headerOf(response: String, name: String): String? = response
        .lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.ifEmpty { null }

    // ------------------------------------------------------------------ PCP and NAT-PMP

    /**
     * PCP MAP (RFC 6887). Unlike NAT-PMP this handles IPv6, where the "mapping" is a pinhole.
     *
     * Layout: a 24-byte header (version 2, opcode 1, lifetime, and the client's own address as 16
     * bytes) followed by 36 bytes of MAP data (a random nonce that ties the reply to this request,
     * the protocol number, the internal port, and a suggested external port and address).
     */
    private fun pcpMap(
        gateway: InetAddress,
        client: String,
        port: Int,
        leaseSeconds: Int,
        lines: MutableList<String>,
    ): Boolean {
        val clientBytes = sixteenBytes(client) ?: return false
        val request = ByteArray(60)
        request[0] = 2 // version
        request[1] = 1 // MAP
        writeInt(request, 4, leaseSeconds)
        clientBytes.copyInto(request, 8)
        // The nonce ties the reply to this request; it must be generated before it is copied.
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        nonce.copyInto(request, 24)
        request[36] = 6 // TCP
        writeShort(request, 40, port)
        writeShort(request, 42, port)
        // Bytes 44..59 stay zero: no preference for the external address.

        val reply = udpExchange(gateway, request, expected = 24) ?: return false
        if (reply[0] != 2.toByte()) return false
        val result = reply[3].toInt() and 0xFF
        return if (result == 0) {
            lines += "PCP mapped $client:$port via ${gateway.hostAddress}"
            true
        } else {
            lines += "PCP refused by ${gateway.hostAddress}: result $result"
            false
        }
    }

    /** NAT-PMP external address request: two bytes out, twelve back, the last four being the WAN IP. */
    private fun natPmpExternal(gateway: InetAddress, lines: MutableList<String>): String? {
        val reply = udpExchange(gateway, byteArrayOf(0, 0), expected = 12) ?: return null
        if (reply[1] != 128.toByte()) return null
        val address = "${reply[8].toInt() and 0xFF}.${reply[9].toInt() and 0xFF}." +
            "${reply[10].toInt() and 0xFF}.${reply[11].toInt() and 0xFF}"
        lines += "NAT-PMP WAN address: $address" + if (isCarrierNat(address)) " (carrier NAT)" else ""
        return address
    }

    private fun natPmpMap(
        gateway: InetAddress,
        port: Int,
        leaseSeconds: Int,
        lines: MutableList<String>,
    ): Boolean {
        val request = ByteArray(12)
        request[1] = 2 // TCP mapping
        writeShort(request, 4, port)
        writeShort(request, 6, port)
        writeInt(request, 8, leaseSeconds)
        val reply = udpExchange(gateway, request, expected = 16) ?: return false
        val result = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        return if (result == 0) {
            lines += "NAT-PMP mapped :$port via ${gateway.hostAddress}"
            true
        } else {
            lines += "NAT-PMP refused by ${gateway.hostAddress}: result $result"
            false
        }
    }

    private fun udpExchange(gateway: InetAddress, request: ByteArray, expected: Int): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 1_200
            socket.send(DatagramPacket(request, request.size, gateway, PCP_PORT))
            val buffer = ByteArray(1100)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (packet.length < expected) null else buffer.copyOf(packet.length)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** An address as 16 bytes, mapping IPv4 into the `::ffff:0:0/96` space as PCP requires. */
    private fun sixteenBytes(address: String): ByteArray? = try {
        val raw = InetAddress.getByName(address.trim('[', ']')).address
        when (raw.size) {
            16 -> raw
            4 -> ByteArray(16).also {
                it[10] = 0xFF.toByte()
                it[11] = 0xFF.toByte()
                raw.copyInto(it, 12)
            }
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
