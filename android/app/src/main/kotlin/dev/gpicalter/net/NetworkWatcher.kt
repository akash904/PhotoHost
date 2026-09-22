package dev.gpicalter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "gpic"

/**
 * Emits whenever the device's default network changes, so the app can re-decide which address
 * reaches the library.
 *
 * ### Why this is needed
 * The working address is cached, because Coil and ExoPlayer build URLs synchronously and cannot
 * wait on a probe. Caching is right, but it means the app keeps using whatever worked last until
 * something tells it to look again -- and until now the only things that did were a manual refresh
 * and a fresh pairing.
 *
 * That shows up exactly where a VPN is involved. Pair over Tailscale, then switch Tailscale off and
 * join Wi-Fi: every address the library is actually on has changed, but nothing has asked, so the
 * app keeps dialling a tailnet address that is no longer routable and simply looks broken.
 *
 * Turning a VPN on or off swaps the default network, which is precisely what this reports.
 */
class NetworkWatcher(private val context: Context) {

    private val _changes = MutableStateFlow(0)

    /** Increments on each change. Collectors should skip the initial value. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bump("available")

            override fun onLost(network: Network) = bump("lost")
        }
        // registerDefaultNetworkCallback rather than requestNetwork: this only observes, and must
        // never pull up a radio the user did not ask for.
        val ok = runCatching { manager.registerDefaultNetworkCallback(registered) }.isSuccess
        callback = if (ok) registered else null
    }

    fun stop() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun bump(reason: String) {
        Log.i(TAG, "default network $reason; the library address needs re-probing")
        _changes.value = _changes.value + 1
    }
}
