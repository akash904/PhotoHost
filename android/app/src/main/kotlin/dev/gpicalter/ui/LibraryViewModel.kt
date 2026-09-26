package dev.gpicalter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpicalter.core.DeviceRole
import dev.gpicalter.core.Library
import dev.gpicalter.core.LibraryProfile
import dev.gpicalter.di.AppContainer
import dev.gpicalter.net.BucketDto
import dev.gpicalter.net.TimelineItemDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    val api = container.api

    data class State(
        val items: List<TimelineItemDto> = emptyList(),
        val buckets: List<BucketDto> = emptyList(),
        val loading: Boolean = false,
        val reachable: Boolean = true,
        val error: String? = null,
        val endpoint: String = "",
        /** Every address this client knows about, so the UI can show what it is choosing between. */
        val knownAddresses: List<String> = emptyList(),
        /** Empty means normal browsing; non-empty puts the grid in selection mode. */
        val selected: Set<Long> = emptySet(),
        /**
         * False until this phone has been told whether it keeps the library or browses one.
         *
         * Distinct from [reachable], which answers "the server did not respond". Before anybody has
         * chosen, there is no server to respond, and saying the network is at fault would be
         * describing a problem that does not exist.
         */
        val configured: Boolean = true,
        /**
         * How many photos the server is still rendering thumbnails for.
         *
         * Without this a freshly filled library is indistinguishable from a broken one: the tiles
         * are blank either way, and nothing on screen says whether waiting will help. It cost an
         * hour of debugging a server that was simply still working.
         */
        val preparing: Int = 0,
        /** Every library the switcher offers, and which one is on screen. */
        val libraries: List<Library> = emptyList(),
        val activeLibraryId: String = Library.LOCAL_ID,
    )

    // Mutated only through MutableStateFlow.update, never by read-then-assign. Several coroutines
    // write this concurrently -- a page load and a bucket load are in flight at the same time --
    // and `_state.value = _state.value.copy(...)` loses whichever write is computed from a stale
    // snapshot. That produced a loaded timeline being silently reset to empty.
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var cursor: String? = null
    private var hasMore = true
    private val seen = HashSet<Long>()
    private var preparingWatch: Job? = null

    /**
     * Whether anybody has said what this phone is. See [State.configured].
     *
     * A configured server address counts on its own, independently of the recorded role. The role
     * and the address are written by the same handful of callers but not always in the same order,
     * and getting that order wrong once already produced a phone sitting on "not set up yet" over a
     * library it had just paired with. A phone that knows where its library is has answered the
     * question, whatever the bookkeeping says.
     */
    private fun configured() =
        container.prefs.role != DeviceRole.UNSET || container.prefs.serverUrl != null

    init {
        refresh()
        watchNetwork()
    }

    /**
     * Re-probes when the phone changes network, and reloads only if the working address moved.
     *
     * Switching a VPN on or off changes which address reaches the library, and the cached one is
     * then wrong in a way that looks like the server being down rather than like a routing change.
     *
     * `collectLatest` with a short delay is the debounce: a single switch reports several changes in
     * a second as interfaces come and go, and each new report cancels the pending probe rather than
     * queueing another one.
     *
     * The reload is conditional because most network changes do not move the address -- reloading
     * the grid regardless would throw away the user's scroll position to arrive at the same place.
     */
    /**
     * The addresses the client would try, in the order it would try them.
     *
     * Read from preferences rather than held separately, so what the UI shows is the same list
     * [dev.gpicalter.net.LibraryApi.resolveEndpoint] actually walks -- a display that drifted from
     * the real order would be worse than none.
     */
    private fun knownAddresses(): List<String> = buildList {
        container.prefs.serverUrl?.let { add(it) }
        addAll(container.prefs.serverCandidates)
    }.distinct()

    private fun watchNetwork() = viewModelScope.launch {
        container.network.changes.drop(1).collectLatest {
            delay(800)
            api.invalidateEndpoint()
            val resolved = api.resolveEndpoint()
            // resolveEndpoint returns a preferred address even when none answered, so that error
            // messages can name something. activeEndpoint is set only by an address that actually
            // replied, which is what distinguishes "we moved" from "we found nothing".
            val live = api.activeEndpoint.value != null
            when {
                !live ->
                    android.util.Log.i("gpic", "no address answered after a network change")

                resolved != _state.value.endpoint -> {
                    android.util.Log.i("gpic", "library address moved to $resolved")
                    refresh()
                }
            }
        }
    }

    /**
     * Puts another library on screen.
     *
     * The HTTP stacks and the learned address belong to the library they were built for, so both are
     * dropped, and the grid is emptied at once: showing the previous library's photos under the new
     * library's name, even for a moment, would be a lie about what is where.
     */
    fun switchTo(id: String) {
        if (id == container.prefs.activeLibraryId) return
        container.prefs.activeLibraryId = id
        container.invalidateHttp()
        api.invalidateEndpoint()
        _state.update { it.copy(items = emptyList(), buckets = emptyList(), selected = emptySet()) }
        refresh()
    }

    /**
     * Names a newly paired library after what it is -- "PC library (192.168.1.20)" or "Phone
     * library (...)" -- from the backend its /health reports. Left alone if the user has renamed it
     * already. Returns the name it ends up with.
     */
    suspend fun nameFromServer(profile: LibraryProfile): String {
        val prefs = container.prefs
        val current = prefs.library(profile.id)?.name ?: profile.name
        if (current != prefs.defaultName(profile.url)) return current
        val health = api.health() ?: return current
        val kind = when (health.backend) {
            "FOLDER" -> "PC library"
            "INTERNAL", "SAF" -> "Phone library"
            else -> "Library"
        }
        val host = runCatching { java.net.URI(profile.url).host }.getOrNull().orEmpty().trim('[', ']')
        val name = if (host.isEmpty()) kind else "$kind ($host)"
        prefs.renameLibrary(profile.id, name)
        _state.update { it.copy(libraries = prefs.libraries()) }
        return name
    }

    fun refresh() {
        _state.update {
            it.copy(libraries = container.prefs.libraries(), activeLibraryId = container.prefs.activeLibrary().id)
        }
        // A phone nobody has configured has no library to load. Probing anyway resolves to loopback,
        // is refused because no server was ever started, and the grid then renders that as a network
        // fault -- so the first thing a brand new install shows is troubleshooting steps for a
        // problem it does not have.
        if (!configured()) {
            _state.update {
                it.copy(
                    configured = false,
                    items = emptyList(),
                    loading = false,
                    reachable = true,
                    error = null,
                )
            }
            return
        }

        cursor = null
        hasMore = true
        seen.clear()
        _state.update { it.copy(configured = true, items = emptyList(), loading = true) }
        viewModelScope.launch {
            // Re-probe on every refresh: the phone may have moved between Wi-Fi and mobile data
            // since the last one, which changes which address is reachable.
            api.invalidateEndpoint()
            val endpoint = api.resolveEndpoint()
            _state.update {
                it.copy(endpoint = endpoint, knownAddresses = knownAddresses(), loading = false)
            }
            loadMore()
            api.refreshEndpoints()
            // A library carried over from before there was a list still has its placeholder name;
            // the first successful connection gives it a real one. A no-op once named or renamed.
            container.prefs.remoteLibraries()
                .firstOrNull { it.id == container.prefs.activeLibraryId }
                ?.let { runCatching { nameFromServer(it) } }
            // Re-read after the refresh: this is the call that discovers the addresses beyond the
            // one this client paired against, so before it the list is just that single address.
            _state.update { it.copy(knownAddresses = knownAddresses()) }
            val buckets = api.buckets()
            _state.update { it.copy(buckets = buckets) }
            watchPreparing()
        }
    }

    /**
     * Follows thumbnail rendering until it finishes, then reloads once.
     *
     * The count comes from stats the server already publishes rather than a new field: assets minus
     * ready grid thumbnails is exactly what is still missing, gated on there being queued work so a
     * thumbnail that has failed for good does not leave this counting down forever.
     *
     * The reload at the end matters as much as the count. Image requests made while a thumbnail was
     * still rendering came back as "not ready", and nothing reissues them, so without this the grid
     * stays blank until the app is next opened -- which is precisely the behaviour that looked like
     * a bug.
     */
    private fun watchPreparing() {
        preparingWatch?.cancel()
        preparingWatch = viewModelScope.launch {
            var sawWork = false
            while (isActive) {
                val stats = api.stats() ?: break
                val missing = (stats.assets - stats.gridThumbs).coerceAtLeast(0)
                val busy = missing > 0 && stats.pendingJobs > 0
                _state.update { it.copy(preparing = if (busy) missing else 0) }
                if (!busy) break
                sawWork = true
                delay(3_000)
            }
            if (sawWork && isActive) refresh()
        }
    }

    fun loadMore() {
        if (_state.value.loading || !hasMore) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val page = api.timeline(cursor)
            if (page == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        reachable = false,
                        error = api.lastError ?: "Cannot reach ${api.baseUrl()}",
                    )
                }
                return@launch
            }
            // A jump can re-request rows already held, so identity is deduped rather than trusted.
            val fresh = page.items.filter { seen.add(it.id) }
            cursor = page.nextCursor
            hasMore = page.hasMore && page.nextCursor != null
            _state.update { current ->
                current.copy(
                    items = (current.items + fresh).sortedWith(
                        compareByDescending<TimelineItemDto> { it.capturedAt }.thenByDescending { it.id },
                    ),
                    loading = false,
                    reachable = true,
                    error = null,
                )
            }
        }
    }

    /** Keyset seek rather than paging through everything in between. */
    fun jumpTo(bucket: BucketDto) {
        seen.clear()
        hasMore = true
        // +1 because the cursor is exclusive, so the month's newest item is included.
        cursor = "${bucket.newestCapturedAt + 1}_${Long.MAX_VALUE}"
        _state.update { it.copy(items = emptyList()) }
        loadMore()
    }

    /**
     * Optimistic: the star flips immediately and reverts only if the server refuses. A round trip
     * before any visible change makes tapping a star feel broken on a phone.
     */
    fun setFavorite(id: Long, favorite: Boolean) {
        _state.update { st ->
            st.copy(items = st.items.map { if (it.id == id) it.copy(favorite = favorite) else it })
        }
        viewModelScope.launch {
            if (!api.setFavorite(id, favorite)) {
                _state.update { st ->
                    st.copy(
                        items = st.items.map { if (it.id == id) it.copy(favorite = !favorite) else it },
                        error = api.lastError,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ selection

    fun toggleSelect(id: Long) = _state.update { st ->
        st.copy(selected = if (id in st.selected) st.selected - id else st.selected + id)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun selectAllLoaded() = _state.update { st -> st.copy(selected = st.items.map { it.id }.toSet()) }

    /**
     * Removes the selection from the visible list immediately, then tells the server.
     *
     * Waiting for a round trip before anything moves makes deleting a hundred photos feel broken.
     * On failure the items are restored by reloading, so the grid never drifts from the server for
     * longer than one request.
     */
    fun deleteSelected(purge: Boolean = false) {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        _state.update { st ->
            st.copy(items = st.items.filterNot { it.id in ids }, selected = emptySet())
        }
        viewModelScope.launch {
            val affected = api.batch(ids, if (purge) "purge" else "trash")
            if (affected != ids.size) {
                _state.update { it.copy(error = api.lastError ?: "deleted $affected of ${ids.size}") }
                refresh()
            }
        }
    }

    fun favoriteSelected(favorite: Boolean) {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        _state.update { st ->
            st.copy(
                items = st.items.map { if (it.id in ids) it.copy(favorite = favorite) else it },
                selected = emptySet(),
            )
        }
        viewModelScope.launch { api.batch(ids, if (favorite) "favorite" else "unfavorite") }
    }

    /**
     * Asks the server to walk its storage again, then reloads.
     *
     * The reload is the point. Scanning is a job queued on the server, so the request returns long
     * before anything is indexed; without waiting and re-fetching, tapping this leaves the grid
     * exactly as empty as before and reads as a button that does nothing. The delay is a guess at
     * how long a small library takes, and a second tap covers a larger one.
     */
    fun rescan() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            api.requestScan()
            delay(2500)
            refresh()
        }
    }
}
