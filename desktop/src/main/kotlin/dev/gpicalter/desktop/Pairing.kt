package dev.gpicalter.desktop

import dev.gpicalter.server.NetInterfaces

/**
 * The pairing link, built exactly as the phone builds it (gpicAlter's `pairingPayload` and
 * MediaServerService's `advertisedUrls` / `secureUrl`), because the unmodified phone app parses it:
 *
 *     https://<host>:<tls>/pair?c=<token>&f=<sha256>&a=<every other address, comma-separated>
 *
 * The primary address is the TLS one: it is the only one safe off the LAN, and the fingerprint in
 * the same link is what lets a client pin it. Every other address rides along in `a=`, so a phone
 * that cannot reach the primary from where it stands still has somewhere to go.
 */
object Pairing {

    /** Every address a client could use, plain and TLS, LAN first. */
    fun advertisedUrls(port: Int, httpsPort: Int, tls: Boolean): List<String> {
        val endpoints = NetInterfaces.displayEndpoints()
        val plain = endpoints.map { "http://${it.host}:$port" }
        val secure = if (tls) endpoints.map { "https://${it.host}:$httpsPort" } else emptyList()
        return plain + secure
    }

    /** Tailscale when it is up, otherwise the first (LAN) address -- the phone's rule. */
    fun secureUrl(httpsPort: Int): String? {
        val endpoint = NetInterfaces.remoteEndpoint() ?: NetInterfaces.endpoints().firstOrNull()
        return endpoint?.let { "https://${it.host}:$httpsPort" }
    }

    fun payload(
        port: Int,
        httpsPort: Int,
        token: String,
        fingerprint: String?,
    ): String? {
        val alternates = advertisedUrls(port, httpsPort, fingerprint != null)
        val httpsUrl = if (fingerprint != null) secureUrl(httpsPort) else null
        val base = NetInterfaces.displayEndpoints().firstOrNull()?.url(port)

        val primary = when {
            httpsUrl != null && fingerprint != null -> "$httpsUrl/pair?c=$token&f=$fingerprint"
            else -> base?.let { "$it/pair?c=$token" }
        } ?: return null

        val extras = alternates.filterNot { it.contains("127.0.0.1") }.distinct()
        return if (extras.isEmpty()) primary else primary + "&a=" + encode(extras.joinToString(","))
    }

    /**
     * `android.net.Uri.encode(s, ":/,")`: percent-encodes everything except RFC 3986 unreserved
     * characters and the three allowed separators, which are legal in a query value and left bare
     * to keep the QR small.
     */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "_-!.~'()*" || c in ":/,") {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
        }
        return sb.toString()
    }
}
