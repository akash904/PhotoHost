package io.github.akash904.photohost.net

import io.github.akash904.photohost.core.Library
import io.github.akash904.photohost.core.Prefs
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.formData
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one way this app reads a library.
 *
 * It is used identically whether the library is on this phone or another one: when serving locally
 * the base URL is loopback. That keeps a single browsing code path, so the server phone exercises
 * exactly the same client the laptop does and local-only bugs cannot hide.
 */
class LibraryApi(
    private val prefs: Prefs,
    /**
     * Which library this client talks to, read on every use. The browsing client follows the library
     * on screen; the backup client follows the backup target. Two instances, so switching what you
     * look at can never redirect an upload in flight.
     */
    private val target: () -> Library,
    /** The HTTP stack for a library: its certificate pin and its token. */
    private val httpStack: (Library) -> okhttp3.OkHttpClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Rebuilt whenever the certificate pin changes.
     *
     * This was a plain `val` and it was a real bug: the Ktor client captures its engine at
     * construction, and this class is created lazily -- in practice before the user has paired.
     * The pin was therefore fixed as "none" forever, and pairing with a TLS server afterwards
     * failed with "trust anchor not found" even though the fingerprint had been stored correctly.
     */
    @Volatile
    private var cached: Pair<String, HttpClient>? = null

    /**
     * Rebuilt whenever the library or its pin changes -- a different library has a different
     * certificate to trust and a different token to present.
     */
    val client: HttpClient
        get() {
            val lib = target()
            val key = "${lib.id}|${lib.fingerprint}"
            cached?.let { (cachedKey, existing) -> if (cachedKey == key) return existing }
            val fresh = HttpClient(OkHttp) {
                engine { preconfigured = httpStack(lib) }
                expectSuccess = false
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    // Generous: the server is a phone that may be busy thumbnailing a backlog.
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
            }
            // The old client owns a connection pool and threads; dropping it without closing
            // leaks both every time the user re-pairs.
            cached?.second?.let { old -> runCatching { old.close() } }
            cached = key to fresh
            return fresh
        }

    /** The library the cached address below belongs to. */
    @Volatile
    private var boundId: String? = null

    /**
     * Drops what was learned about the previous library when the target changes. The address that
     * answered for the phone library is meaningless for the PC one, and keeping it would send the
     * first requests after a switch to the wrong server.
     */
    private fun followTarget(): Library {
        val lib = target()
        if (lib.id != boundId) {
            boundId = lib.id
            activeBase = null
            failedSweeps = 0
        }
        return lib
    }

    /**
     * The address currently known to work.
     *
     * Cached because Coil and ExoPlayer build URLs synchronously and cannot wait on a probe, and
     * because re-testing every candidate per request would add a round trip to each thumbnail.
     */
    @Volatile
    private var activeBase: String? = null
        set(value) {
            field = value
            _activeEndpoint.value = value
        }

    /**
     * The address in use right now, for screens that report it.
     *
     * Observable rather than a plain read, because the value changes underneath any screen showing
     * it -- a VPN going down moves it seconds after the user last touched anything.
     */
    private val _activeEndpoint = MutableStateFlow<String?>(null)
    val activeEndpoint: StateFlow<String?> = _activeEndpoint.asStateFlow()

    /**
     * How many consecutive sweeps found nothing, and when the last one ended.
     *
     * A transport failure drops the cached address, so while the library is unreachable every single
     * request triggers a fresh sweep of every candidate. With six addresses at two seconds each that
     * is twelve seconds of radio per attempt, repeating for as long as the outage lasts -- which is
     * precisely when the phone is least likely to be on a charger.
     */
    @Volatile
    private var failedSweeps = 0

    @Volatile
    private var lastFailedSweepAt = 0L

    /** Why the last request failed. Without this, every network problem looks identical. */
    @Volatile
    var lastError: String? = null
        private set

    fun baseUrl(): String {
        val lib = followTarget()
        return activeBase ?: lib.baseUrl(prefs.port)
    }

    /**
     * The address for a request that can wait for a probe: the working one already found, or the
     * first candidate that answers now.
     *
     * Every suspending call goes through this rather than [baseUrl], which only returns what a probe
     * already found and otherwise falls back to the paired address. Only browsing used to probe, so
     * the backup's own client for the same library never did and kept dialling the pairing code's
     * primary address. Observed: a phone paired from a code whose primary was the server's Tailscale
     * address, itself not on Tailscale, failed every upload while browsing the same library worked.
     *
     * [baseUrl] stays for the URLs Coil and ExoPlayer build synchronously; browsing resolves first.
     */
    private suspend fun base(): String = resolveEndpoint()

    /** Records why a request failed, and forgets the address when the failure says it is dead. */
    private fun failed(t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        noteTransportFailure(t)
    }

    /**
     * Picks the address to use: an encrypted one whenever any answers, and among those the fastest.
     *
     * Encrypted first because the plain address carries the photos and the bearer token in the
     * clear, and the servers list plain addresses first. Taking candidates in that order put home
     * backups on http:// although https:// to the same server answered just as well.
     *
     * The encrypted candidates are probed all at once and the first to answer wins. In order, one at
     * a time, every unreachable address ahead of the right one cost a full probe timeout: a code
     * whose primary was the server's Tailscale address made each re-probe at home, on a phone
     * without Tailscale, wait two seconds before trying the LAN. Racing them also picks the shorter
     * route when several work, the LAN at home rather than a VPN.
     *
     * Plain addresses are raced only when no encrypted one answers, so a library still works if its
     * TLS side is down, as it did before encryption existed.
     *
     * Probes run with a short timeout: an address that is not on this network fails fast by design,
     * and waiting the full request timeout would make startup feel broken.
     */
    suspend fun resolveEndpoint(): String {
        val lib = followTarget()
        activeBase?.let { return it }
        val candidates = buildList {
            lib.url?.let { add(it) }
            addAll(lib.candidates)
        }.distinct()
        if (candidates.isEmpty()) return lib.baseUrl(prefs.port)

        val quietFor = backoffMillis()
        if (failedSweeps > 0 && System.currentTimeMillis() - lastFailedSweepAt < quietFor) {
            // Still inside the quiet period after a sweep that found nothing. Returning the
            // preferred address without probing keeps error messages naming something meaningful
            // while the radio stays idle.
            return candidates.first()
        }

        val (secure, plain) = candidates.partition { it.startsWith("https://", ignoreCase = true) }
        val found = firstAnswering(secure) ?: firstAnswering(plain)
        if (found != null) {
            failedSweeps = 0
            activeBase = found
            // Logged on every successful probe, not only on a change. When the question is
            // "which way is it talking to the library right now", inferring it from which
            // requests failed is guesswork, and this is one line.
            android.util.Log.i("photohost", "library reachable at $found")
            return found
        }
        // Nothing answered. Keep the preferred address so the error names something meaningful.
        failedSweeps++
        lastFailedSweepAt = System.currentTimeMillis()
        android.util.Log.w(
            "photohost",
            "no candidate answered; ${candidates.size} tried, " +
                "not sweeping again for ${backoffMillis() / 1000}s",
        )
        return candidates.first()
    }

    /**
     * Forces the next call to re-probe, e.g. after moving between networks.
     *
     * Also clears the backoff, because every caller of this is a reason to believe the answer has
     * changed -- the user asked, or the network did something. Making them wait out a quiet period
     * earned by earlier failures would defeat the point of asking.
     */
    fun invalidateEndpoint() {
        activeBase = null
        failedSweeps = 0
    }

    /**
     * How long to stay quiet after a sweep that found nothing: 2s, 4s, 8s, 16s, then 30s.
     *
     * Capped rather than unbounded, since the app should still notice unaided when a network comes
     * back -- the cap is what keeps an unattended phone from taking minutes to recover.
     */
    private fun backoffMillis(): Long = when {
        failedSweeps <= 0 -> 0L
        else -> ((1L shl minOf(failedSweeps, 5)) * 1_000L).coerceAtMost(30_000L)
    }

    /**
     * Drops the cached address when a failure says it is no longer reachable.
     *
     * A connection that refuses, times out or cannot resolve means the address itself is wrong now
     * -- a VPN went away, the phone moved, the server changed address. Without this the app keeps
     * dialling it until something else forces a re-probe, and every request fails the same way for
     * as long as that takes.
     *
     * An HTTP status is deliberately not treated this way. A 401 or a 500 proves the address is
     * right and something else is wrong, and re-probing would hide the real problem behind a
     * changing endpoint.
     */
    private fun noteTransportFailure(t: Throwable) {
        val name = t.javaClass.simpleName
        val transport = t is java.io.IOException ||
            name.contains("Timeout", ignoreCase = true) ||
            name.contains("Connect", ignoreCase = true) ||
            name.contains("UnresolvedAddress", ignoreCase = true)
        // Deliberately does not touch failedSweeps: this is the very churn the backoff exists to
        // damp, so letting it reset the counter would make the backoff unreachable.
        if (transport) activeBase = null
    }

    /**
     * Probes [group] concurrently and returns the first address to answer, or null when none does.
     * The remaining probes are cancelled as soon as one wins.
     */
    private suspend fun firstAnswering(group: List<String>): String? = coroutineScope {
        if (group.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<String?>()
        val probes = group.map { candidate ->
            launch {
                if (reachable(candidate)) winner.complete(candidate)
                else android.util.Log.i("photohost", "no answer from $candidate")
            }
        }
        launch {
            probes.joinAll()
            winner.complete(null)
        }
        val result = winner.await()
        coroutineContext.cancelChildren()
        result
    }

    private suspend fun reachable(base: String): Boolean = try {
        val r = client.get("$base/health") {
            // The connect timeout is the one that matters, and it has to be overridden explicitly:
            // the shared client allows ten seconds, which is right for a request that is going to
            // succeed and far too long for a probe that is expected to fail. An address on a network
            // this phone is no longer attached to hangs until the TCP attempt gives up, so with the
            // inherited value a single dead candidate stalled failover for ten seconds.
            timeout {
                connectTimeoutMillis = 2_000
                requestTimeoutMillis = 2_500
                socketTimeoutMillis = 2_500
            }
        }
        r.status.isSuccess()
    } catch (c: kotlinx.coroutines.CancellationException) {
        // A losing probe in a race, not an unreachable address: rethrown, so it neither counts as
        // "no answer" nor keeps its coroutine running.
        throw c
    } catch (t: Throwable) {
        false
    }

    /**
     * Learns the server's other addresses so a later move to another network still works.
     *
     * The response is an object wrapping the list, not a bare list. It used to be a bare list, and
     * when the TLS work added a fingerprint alongside it the shape changed here without this call
     * being updated -- so every response failed to deserialise, the failure was swallowed as "no
     * endpoints", and the candidate list stayed empty. A client paired over one address then had
     * exactly that address to try and no way back when it stopped working, which read as the server
     * being down rather than as a client that had never learned where else to look.
     *
     * The fingerprint in the response is deliberately ignored. A pin has to arrive out of band -- it
     * comes from the pairing QR -- because accepting one from the server being authenticated is
     * circular and would let any server that answered nominate its own identity.
     */
    suspend fun refreshEndpoints() {
        val found = getOrNull<EndpointsDto>("/api/v1/endpoints") ?: run {
            android.util.Log.w("photohost", "could not learn the server's other addresses: $lastError")
            return
        }
        val urls = found.endpoints.map { it.url }.filterNot { it.contains("127.0.0.1") }
        if (urls.isNotEmpty()) {
            prefs.updateCandidates(target().id, urls)
            android.util.Log.i("photohost", "learned ${urls.size} candidate addresses")
        }
    }

    /**
     * [v] is the start of the asset's content hash, from the timeline. Coil caches these images by
     * URL for as long as the server allows, which for a thumbnail is a year; an asset id alone is
     * reused for a different photo whenever a library's index is rebuilt, and the cache then showed
     * the old photo in the new one's place. With the content in the URL, a different photo is
     * always a different URL. The server ignores the parameter.
     */
    fun thumbUrl(assetId: Long, size: String = "grid", v: String? = null): String =
        "${baseUrl()}/api/v1/assets/$assetId/thumb?size=$size" + (v?.let { "&v=$it" } ?: "")

    /** As [thumbUrl]: the original is cached too, when zoomed in. */
    fun originalUrl(assetId: Long, v: String? = null): String =
        "${baseUrl()}/api/v1/assets/$assetId/original" + (v?.let { "?v=$it" } ?: "")

    /** Coil and ExoPlayer both need this, since thumbnails and originals are authenticated too. */
    fun authHeader(): String = "Bearer ${target().token}"

    /** The library this client is talking to right now. */
    fun library(): Library = target()

    suspend fun health(): HealthDto? = getOrNull("/health")

    suspend fun stats(): StatsDto? = getOrNull("/api/v1/stats")

    suspend fun timeline(cursor: String?, limit: Int = 200): TimelineDto? =
        getOrNull("/api/v1/timeline") {
            parameter("limit", limit)
            if (cursor != null) parameter("cursor", cursor)
        }

    suspend fun buckets(): List<BucketDto> = getOrNull<List<BucketDto>>("/api/v1/timeline/buckets") ?: emptyList()

    suspend fun asset(id: Long): AssetDetailDto? = getOrNull("/api/v1/assets/$id")

    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean = try {
        client.patch("${base()}/api/v1/assets/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(favorite = favorite))
        }.status.isSuccess()
    } catch (t: Throwable) {
        failed(t)
        false
    }

    suspend fun trashAsset(id: Long): Boolean = try {
        client.patch("${base()}/api/v1/assets/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(deleted = true))
        }.status.isSuccess()
    } catch (t: Throwable) {
        failed(t)
        false
    }

    // ---------------------------------------------------------------- upload

    /**
     * Asks which of these hashes the library already holds.
     *
     * This is what makes continuous backup cheap: a client re-offering its whole camera roll pays
     * one small round trip per batch instead of re-uploading everything it cannot remember sending.
     */
    suspend fun knownHashes(hashes: List<String>): Map<String, Long> {
        if (hashes.isEmpty()) return emptyMap()
        return try {
            val r = client.post("${base()}/api/v1/upload/check") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(HashCheckDto(hashes))
            }
            if (r.status.isSuccess()) r.body<HashCheckResultDto>().ids else emptyMap()
        } catch (t: Throwable) {
            failed(t)
            emptyMap()
        }
    }

    sealed interface VerifyOutcome {
        /** The library answered: exactly these hashes are held safely right now. */
        data class Checked(val safe: Set<String>) : VerifyOutcome

        /** A library too old to have the check. Nothing may be deleted on its word. */
        data object Unsupported : VerifyOutcome

        data class Failed(val error: String) : VerifyOutcome
    }

    /**
     * Asks which of [hashes] the library holds safely: stored, not in its trash, and present on its
     * disk at the recorded size right now. The only answer "Free up space" deletes on.
     *
     * Deliberately not [knownHashes]: that one also vouches for trashed and unreachable copies,
     * which is right for "is there any point uploading this?" and wrong for "may I delete mine?".
     */
    suspend fun verifySafe(hashes: List<String>): VerifyOutcome {
        if (hashes.isEmpty()) return VerifyOutcome.Checked(emptySet())
        return try {
            val r = client.post("${base()}/api/v1/verify") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(HashCheckDto(hashes))
            }
            when {
                r.status.value == 404 -> VerifyOutcome.Unsupported
                r.status.isSuccess() -> VerifyOutcome.Checked(r.body<VerifyResultDto>().safe.toSet())
                else -> VerifyOutcome.Failed("HTTP ${r.status.value}")
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            failed(t)
            VerifyOutcome.Failed(lastError ?: t.toString())
        }
    }

    /**
     * Offers a capture date the client knows from MediaStore. The server applies it only if what it
     * holds is weaker, so this repairs historical mistakes without ever degrading a good date.
     */
    suspend fun offerCapturedAt(assetId: Long, capturedAt: Long): Boolean = try {
        client.patch("${base()}/api/v1/assets/$assetId") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssetPatchDto(capturedAt = capturedAt, capturedAtSource = 2))
        }.status.isSuccess()
    } catch (t: Throwable) {
        false
    }

    suspend fun uploadInit(
        name: String,
        size: Long,
        sha256: String,
        capturedAt: Long?,
        sourceAlbum: String? = null,
    ): UploadInitResultDto? =
        try {
            val r = client.post("${base()}/api/v1/upload/init") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(UploadInitDto(name, size, sha256, capturedAt, sourceAlbum))
            }
            if (r.status.isSuccess()) r.body<UploadInitResultDto>() else null
        } catch (t: Throwable) {
            failed(t)
            null
        }

    /** Returns the server's offset after the chunk, or null on failure. */
    suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray, length: Int): Long? = try {
        val r = client.patch("${base()}/api/v1/upload/$uploadId") {
            auth()
            parameter("offset", offset)
            setBody(if (length == bytes.size) bytes else bytes.copyOf(length))
        }
        when {
            r.status.isSuccess() -> r.body<UploadStatusDto>().offset
            // 409 carries the server's real offset, so a desynced client can resume rather than
            // restart the whole transfer.
            r.status.value == 409 -> r.body<UploadStatusDto>().offset
            else -> null
        }
    } catch (t: Throwable) {
        failed(t)
        null
    }

    /**
     * Streams an original into [dest], calling [onProgress] with bytes so far and the total when
     * the server gives one. True when the whole file arrived.
     *
     * The request timeout is lifted for this call only: the client's 30 seconds suits API calls,
     * and would cut off a video of a few hundred megabytes on an ordinary Wi-Fi link. A stalled
     * connection is still caught, by the socket timeout.
     */
    suspend fun downloadOriginal(assetId: Long, dest: java.io.File, onProgress: (Long, Long?) -> Unit): Boolean = try {
        client.prepareGet("${base()}/api/v1/assets/$assetId/original") {
            auth()
            timeout {
                requestTimeoutMillis = io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                lastError = "HTTP ${response.status.value} fetching the original"
                return@execute false
            }
            val total = response.contentLength()
            val input = response.bodyAsChannel().toInputStream()
            withContext(Dispatchers.IO) {
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive() // Cancel in the UI stops the copy between chunks
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            total == null || dest.length() == total
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        failed(t)
        false
    }

    suspend fun uploadOffset(uploadId: String): Long? =
        getOrNull<UploadStatusDto>("/api/v1/upload/$uploadId")?.offset

    suspend fun uploadFinish(uploadId: String): UploadFinishDto? = try {
        val r = client.post("${base()}/api/v1/upload/$uploadId/finish") { auth() }
        if (r.status.isSuccess()) {
            r.body<UploadFinishDto>()
        } else {
            lastError = "finish: HTTP ${r.status.value}"
            null
        }
    } catch (t: Throwable) {
        failed(t)
        null
    }

    /** Bulk op over a selection: trash, restore, favorite, unfavorite, purge. */
    suspend fun batch(ids: List<Long>, op: String): Int = try {
        val r = client.post("${base()}/api/v1/assets/batch") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(BatchDto(ids, op))
        }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        failed(t)
        0
    }

    suspend fun sources(): List<SourceDto> = getOrNull<List<SourceDto>>("/api/v1/sources") ?: emptyList()

    suspend fun trash(): List<TrashItemDto> = getOrNull<List<TrashItemDto>>("/api/v1/trash") ?: emptyList()

    suspend fun batchBySource(album: String, op: String): Int = try {
        val r = client.post("${base()}/api/v1/assets/batch") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(BatchDto(op = op, sourceAlbum = album))
        }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        failed(t)
        0
    }

    suspend fun emptyTrash(): Int = try {
        val r = client.post("${base()}/api/v1/trash/empty") { auth() }
        if (r.status.isSuccess()) r.body<BatchResultDto>().affected else 0
    } catch (t: Throwable) {
        failed(t)
        0
    }

    suspend fun requestScan(): Boolean = try {
        client.post("${base()}/api/v1/scan") { auth() }.status.isSuccess()
    } catch (t: Throwable) {
        false
    }

    private suspend inline fun <reified T> getOrNull(
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T? = try {
        val response: HttpResponse = client.get("${base()}$path") {
            header(HttpHeaders.Authorization, authHeader())
            configure()
        }
        if (response.status.isSuccess()) {
            lastError = null
            response.body<T>()
        } else {
            lastError = "HTTP ${response.status.value} from $path"
            null
        }
    } catch (t: Throwable) {
        failed(t)
        android.util.Log.w("photohost", "api $path failed", t)
        null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, authHeader())
    }
}

// ---------------------------------------------------------------- wire types
// Deliberately mirrors the server's DTOs. ignoreUnknownKeys means a newer server can add fields
// without breaking an older client -- which will matter once the two are updated separately.

@Serializable
data class TimelineItemDto(
    val id: Long,
    val mime: String = "",
    val mediaType: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 0,
    val capturedAt: Long = 0,
    val tzOffsetMinutes: Int? = null,
    val durationMs: Long? = null,
    val favorite: Boolean = false,
    val blurhash: String? = null,
    val isVideo: Boolean = false,
    /** The start of the content hash, for image URLs; see [LibraryApi.thumbUrl]. */
    val v: String? = null,
) {
    /** Falls back to square so a missing dimension cannot break the row solver. */
    val aspectRatio: Float
        get() {
            val w = width ?: return 1f
            val h = height ?: return 1f
            if (w <= 0 || h <= 0) return 1f
            return (w.toFloat() / h).coerceIn(0.4f, 3.5f)
        }
}

@Serializable
data class TimelineDto(
    val items: List<TimelineItemDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class BatchDto(
    val ids: List<Long> = emptyList(),
    val op: String = "",
    val sourceAlbum: String? = null,
)

@Serializable
data class SourceDto(val album: String = "", val count: Int = 0, val bytes: Long = 0)

@Serializable
data class TrashItemDto(
    val id: Long,
    val mime: String = "",
    val byteSize: Long = 0,
    val capturedAt: Long = 0,
    val deletedAt: Long = 0,
    val blurhash: String? = null,
    val sourceAlbum: String? = null,
    val v: String? = null,
)

@Serializable
data class BatchResultDto(val affected: Int = 0, val op: String = "")

@Serializable
data class HashCheckDto(val hashes: List<String> = emptyList())

@Serializable
data class HashCheckResultDto(
    val known: List<String> = emptyList(),
    val ids: Map<String, Long> = emptyMap(),
)

@Serializable
data class VerifyResultDto(val safe: List<String> = emptyList())

@Serializable
data class UploadInitDto(
    val name: String,
    val size: Long,
    val sha256: String? = null,
    val capturedAt: Long? = null,
    val sourceAlbum: String? = null,
)

@Serializable
data class UploadInitResultDto(
    val uploadId: String? = null,
    val offset: Long = 0,
    val duplicate: Boolean = false,
    val assetId: Long? = null,
)

@Serializable
data class UploadStatusDto(val uploadId: String = "", val offset: Long = 0)

@Serializable
data class UploadFinishDto(val assetId: Long, val duplicate: Boolean = false, val relPath: String? = null)

@Serializable
data class AssetPatchDto(
    val favorite: Boolean? = null,
    val deleted: Boolean? = null,
    val capturedAt: Long? = null,
    val capturedAtSource: Int? = null,
    val tzOffsetMinutes: Int? = null,
)

@Serializable
data class EndpointDto(val label: String = "", val url: String = "", val secure: Boolean = false)

/** Mirrors the server's reply shape; see [LibraryApi.refreshEndpoints] for why that matters. */
@Serializable
data class EndpointsDto(
    val endpoints: List<EndpointDto> = emptyList(),
    val tlsFingerprint: String? = null,
)

@Serializable
data class BucketDto(val bucket: String, val count: Int, val newestCapturedAt: Long)

@Serializable
data class AssetDetailDto(
    val id: Long,
    val contentHash: String = "",
    val mime: String = "",
    val mediaType: Int = 0,
    val byteSize: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 0,
    val durationMs: Long? = null,
    val capturedAt: Long = 0,
    val capturedAtSource: Int = 5,
    val tzOffsetMinutes: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val blurhash: String? = null,
    val favorite: Boolean = false,
    val relPath: String? = null,
    val present: Boolean = true,
)

@Serializable
data class StatsDto(
    val assets: Int = 0,
    val trashed: Int = 0,
    val libraryBytes: Long = 0,
    val fingerprints: Int = 0,
    val missingFiles: Int = 0,
    val gridThumbs: Int = 0,
    val previewThumbs: Int = 0,
    val thumbCacheBytes: Long = 0,
    val pendingJobs: Int = 0,
    val blockedJobs: Int = 0,
    val failedJobs: Int = 0,
)

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val uptimeS: Long = 0,
    val backend: String = "",
    val label: String = "",
    val mounted: Boolean = false,
    val writable: Boolean = false,
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val openFds: Int = 0,
)
