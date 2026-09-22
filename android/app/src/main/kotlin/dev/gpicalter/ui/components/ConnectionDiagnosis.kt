package dev.gpicalter.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.gpicalter.server.NetInterfaces

/**
 * What to tell someone whose library will not load.
 *
 * "Library unreachable" plus a stack trace is true and useless. The phone already knows which of the
 * handful of possible causes applies -- whether it has any network at all, whether it is on Wi-Fi,
 * whether Tailscale is up -- and each one has a different first thing to try. Working that out here
 * turns a dead end into an instruction.
 *
 * @param headline names the situation, not the symptom.
 * @param steps what to do, most likely to work first.
 */
data class Diagnosis(val headline: String, val steps: List<String>)

/**
 * @param knownAddresses the addresses this client would try, used to name the network the server
 *   lives on. Telling someone to "join the right Wi-Fi" is only useful if it says which one.
 */
fun diagnose(context: Context, knownAddresses: List<String>): Diagnosis {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
    val online = capabilities != null
    val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val tailscaleUp = NetInterfaces.hasTailscale()

    // The server's LAN address, so the advice can name the network rather than gesture at it.
    val lanAddress = knownAddresses
        .map { it.substringAfter("://").substringBefore(':') }
        .firstOrNull { addressLabel("http://$it:0") == "Wi-Fi" }
    val wifiStep = if (lanAddress != null) {
        "Join the Wi-Fi the server is on — it answers at $lanAddress."
    } else {
        "Join the same Wi-Fi network as the phone serving the library."
    }

    return when {
        !online -> Diagnosis(
            headline = "This phone has no network",
            steps = listOf(
                "Turn on Wi-Fi, or switch on mobile data.",
                "Then this screen will retry on its own.",
            ),
        )

        tailscaleUp -> Diagnosis(
            headline = "Tailscale is on, but the library is not answering",
            steps = listOf(
                "Check the phone serving the library is awake and its server is running.",
                "Check Tailscale is on there too — both ends have to be signed in.",
                wifiStep,
            ),
        )

        !onWifi -> Diagnosis(
            headline = "Away from home, and Tailscale is off",
            steps = listOf(
                "Turn on Tailscale. It reaches the library from any network, including mobile data.",
                wifiStep,
            ),
        )

        else -> Diagnosis(
            headline = "This Wi-Fi cannot see the library",
            steps = listOf(
                wifiStep,
                "Or turn on Tailscale, which works from any network.",
                "If you are on the right Wi-Fi, check the server is running on the other phone.",
            ),
        )
    }
}

/**
 * Turns a thrown exception into a sentence.
 *
 * The raw text names a Ktor class, repeats the URL and quotes a timeout in milliseconds. All of that
 * is useful when debugging and noise when the reader simply wants to know what went wrong, so it
 * stays available behind Details rather than leading.
 */
fun friendlyError(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val name = raw.substringBefore(':')
    return when {
        name.contains("ConnectTimeout", true) || name.contains("SocketTimeout", true) ->
            "The address did not respond in time."

        name.contains("ConnectException", true) ->
            "The connection was refused — something is at that address, but nothing is serving."

        name.contains("UnknownHost", true) || name.contains("UnresolvedAddress", true) ->
            "That address could not be found on this network."

        name.contains("SSL", true) || name.contains("Certificate", true) ->
            "The server answered but presented an unexpected certificate. Pair again to trust it."

        name.contains("HttpRequestTimeout", true) ->
            "The server accepted the connection but did not reply in time."

        else -> raw.take(160)
    }
}
