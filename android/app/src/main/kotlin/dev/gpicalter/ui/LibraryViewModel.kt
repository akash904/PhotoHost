package dev.gpicalter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpicalter.di.AppContainer
import dev.gpicalter.net.BucketDto
import dev.gpicalter.net.TimelineItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /** Empty means normal browsing; non-empty puts the grid in selection mode. */
        val selected: Set<Long> = emptySet(),
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

    init {
        refresh()
    }

    fun refresh() {
        cursor = null
        hasMore = true
        seen.clear()
        _state.update { it.copy(items = emptyList(), endpoint = api.baseUrl()) }
        loadMore()
        viewModelScope.launch {
            val buckets = api.buckets()
            _state.update { it.copy(buckets = buckets) }
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

    fun rescan() {
        viewModelScope.launch {
            api.requestScan()
        }
    }
}
