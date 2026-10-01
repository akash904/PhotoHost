package io.github.akash904.photohost.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local view of the service, for the UI to observe.
 *
 * Deliberately not persisted and not cross-process: the Activity and the service share one
 * process, and anything that must outlive the process belongs in the database (from M3) or in
 * the heartbeat file, not here.
 */
object ServerState {

    data class Snapshot(
        val running: Boolean = false,
        val port: Int = 8080,
        val startedAt: Long = 0L,
        /** The store kind, for diagnostics. Screens show [location]. */
        val backend: String = "",
        /** Where the photos are, as a person would say it; see LibraryStore.location. */
        val location: String = "",
        val urls: List<String> = emptyList(),
        val token: String = "",
        val heartbeats: Int = 0,
        val lastHeartbeatAt: Long = 0L,
        val error: String? = null,
        val scanNote: String? = null,
        val httpsUrl: String? = null,
        val tlsFingerprint: String? = null,
        /**
         * Every address a pairing code should hand over, plain and TLS.
         *
         * Carried in the QR so a freshly paired client knows all of them immediately. Without it a
         * client knows only the address it paired against, and if that one is unreachable -- paired
         * over Wi-Fi from a code advertising a VPN address, say -- it has no way to discover any
         * other and no way to recover.
         */
        val pairUrls: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }
}
