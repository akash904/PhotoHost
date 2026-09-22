package dev.gpicalter.ui.components

/**
 * Names a route from its address, so the UI can say "Tailscale" where it would otherwise print
 * `100.101.102.103` and leave the reader to work out what that is.
 *
 * Derived rather than stored. The server does send labels alongside its addresses, but deriving
 * them here keeps the saved candidate list a plain list of URLs with no format to migrate -- and the
 * ranges leave nothing to guess: `100.64/10` is what Tailscale hands out, and the RFC 1918 ranges
 * are what a home router hands out.
 *
 * Shared between the library grid and settings deliberately. Two copies would drift, and the whole
 * point is that both screens name the same connection the same way.
 */
fun addressLabel(url: String): String {
    val hostPort = url.substringAfter("://")
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringAfter('[').substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    return when {
        host == "127.0.0.1" || host == "::1" -> "This phone"
        octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 -> "Tailscale"
        octets.size == 4 && octets[0] == 10 -> "Wi-Fi"
        octets.size == 4 && octets[0] == 192 && octets[1] == 168 -> "Wi-Fi"
        octets.size == 4 && octets[0] == 172 && octets[1] in 16..31 -> "Wi-Fi"
        host.contains(':') -> "IPv6"
        else -> host
    }
}
