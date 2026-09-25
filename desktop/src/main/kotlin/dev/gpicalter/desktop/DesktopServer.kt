package dev.gpicalter.desktop

import dev.gpicalter.core.AppDispatchers
import dev.gpicalter.core.Log
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.JobEntity
import dev.gpicalter.data.entity.JobState
import dev.gpicalter.data.entity.JobType
import dev.gpicalter.data.entity.VolumeEntity
import dev.gpicalter.index.LibraryIndexer
import dev.gpicalter.index.StoreScanner
import dev.gpicalter.jobs.EvictCacheHandler
import dev.gpicalter.jobs.JobHandler
import dev.gpicalter.jobs.JobRunner
import dev.gpicalter.jobs.ScanHandler
import dev.gpicalter.jobs.ThumbnailHandler
import dev.gpicalter.media.DesktopMediaProbe
import dev.gpicalter.media.ThumbnailCache
import dev.gpicalter.media.ThumbnailGenerator
import dev.gpicalter.media.VideoFrames
import dev.gpicalter.server.Auth
import dev.gpicalter.server.CertStore
import dev.gpicalter.server.HttpServer
import dev.gpicalter.server.NetInterfaces
import dev.gpicalter.server.TlsProxy
import dev.gpicalter.server.UploadService
import dev.gpicalter.storage.FolderStore
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.BindException

private const val TAG = "gpic"
private const val HEARTBEAT_INTERVAL_MS = 60_000L

/** What the window shows. */
data class ServerStatus(
    val running: Boolean = false,
    val error: String? = null,
    val libraryPath: String = "",
    val port: Int = 0,
    val httpsPort: Int = 0,
    val fingerprint: String? = null,
    val pairingLink: String? = null,
    val addresses: List<String> = emptyList(),
    val assets: Int = 0,
    val pendingJobs: Int = 0,
    val blockedJobs: Int = 0,
    val scanNote: String? = null,
    /** The ffmpeg in use for video thumbnails, or null when there is none. */
    val videoDecoder: String? = null,
)

/**
 * The desktop's counterpart of the phone's MediaServerService: builds the graph, starts the job
 * runner, the HTTP server and the TLS proxy, and keeps the advertised addresses current.
 *
 * The bring-up order is the phone's, step for step, so the two servers come up in the same state.
 * Two things are added for a PC: a scan at start-up (files may have been copied into the library
 * folder while the server was off, and nothing else would notice them), and bind failures reported
 * as such rather than as a server that silently never answers.
 */
class DesktopServer(private val config: Config) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("gpic-desktop"))
    private val dispatchers = AppDispatchers()

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    lateinit var db: AppDatabase
        private set

    private var http: HttpServer? = null
    private var tlsProxy: TlsProxy? = null
    private var runner: JobRunner? = null
    private var heartbeat: Job? = null

    /**
     * Brings everything up. Returns false, with [status] carrying the reason, when the server could
     * not start -- most usefully when a port is already taken.
     */
    suspend fun start(): Boolean {
        val dataDir = config.dataDir.apply { mkdirs() }
        Log.file = File(dataDir, "photohost.log")

        val store = FolderStore(config.libraryRoot)
        // One index per library folder, keyed on the id stored inside the folder. Choosing a
        // different folder must not show the previous folder's photos, which the new store cannot
        // serve; choosing the old one again must find its index intact rather than re-hash it all.
        // Token and TLS identity stay shared at the top level, so paired clients stay paired.
        val libDir = File(dataDir, "libraries/" + store.volumeKey().substringAfter("folder:")).apply { mkdirs() }
        db = AppDatabase.open(File(libDir, AppDatabase.NAME))
        val volumeId = ensureVolume(store)
        val port = config.port
        val httpsPort = config.httpsPort
        val token = config.token()

        val probe = DesktopMediaProbe()
        val video = VideoFrames.locate(config.ffmpeg)
        Log.i(TAG, "video thumbnails: ${video.ffmpeg?.let { "ffmpeg at $it" } ?: "off (no ffmpeg found)"}")
        val generator = ThumbnailGenerator(File(libDir, "thumbs"), store, video) { store.pathFor(it) }
        val thumbCache = ThumbnailCache(db, generator)
        val indexer = LibraryIndexer(db, store, volumeId, probe)
        val scanner = StoreScanner(store, indexer)

        val handlers: Map<String, JobHandler> = mapOf(
            JobType.THUMBNAIL to ThumbnailHandler(db, store, generator),
            JobType.EVICT_CACHE to EvictCacheHandler(thumbCache),
            JobType.SCAN_VOLUME to ScanHandler(scanner) { p ->
                _status.update {
                    it.copy(scanNote = "scanned ${p.scanned}, new ${p.indexed}, dup ${p.duplicates}, skip ${p.skipped}, fail ${p.failed}")
                }
            },
        )

        runner = JobRunner(
            jobs = db.jobs(),
            handlers = handlers,
            dispatcher = dispatchers.dbIo,
            // A PC is not a phone: no battery or thermal state to respect, so half the cores always.
            concurrency = { dispatchers.decodeParallelism },
            owner = "${ProcessHandle.current().pid()}:${System.currentTimeMillis()}",
        ).also { it.start(scope) }

        // Any work parked because a volume or a decoder was missing gets a fresh chance on start-up.
        db.jobs().unblockAll(System.currentTimeMillis())

        val uploads = UploadService(
            db = db,
            store = store,
            indexer = indexer,
            stagingRoot = File(libDir, "staging"),
        )
        uploads.sweepStale()

        // The certificate names every address the server may be reached at right now. Clients pin
        // the fingerprint rather than checking names, so a later address change needs no new cert.
        val sans = NetInterfaces.endpoints().map { it.host.trim('[', ']') }
        val tls = CertStore(File(dataDir, "tls")).loadOrCreate(sans)
        if (tls == null) Log.w(TAG, "TLS unavailable; serving plain HTTP only")

        val server = HttpServer(
            store = store,
            db = db,
            webAsset = { path -> HttpServer::class.java.getResourceAsStream("/$path")?.use { it.readBytes() } },
            probe = probe,
            thumbs = generator,
            thumbCache = thumbCache,
            uploads = uploads,
            auth = Auth(token),
            port = port,
            httpsPort = httpsPort,
            tls = tls,
            // The phone counts /proc/self/fd to catch descriptor leaks. Windows has no cheap
            // equivalent; -1 is the phone's own "unknown".
            openFds = { -1 },
            onScanRequested = { scope.launch { enqueueScan() } },
        )
        try {
            server.start()
        } catch (t: Throwable) {
            fail(bindMessage(t, port))
            return false
        }
        http = server

        tls?.let { identity ->
            val proxy = TlsProxy(identity, listenPort = httpsPort, targetPort = port)
            if (proxy.start(scope)) {
                tlsProxy = proxy
            } else {
                fail("Could not listen for TLS on port $httpsPort. Another program may be using it.")
                return false
            }
        }

        _status.update {
            it.copy(
                running = true,
                error = null,
                libraryPath = store.root.toString(),
                port = port,
                httpsPort = httpsPort,
                fingerprint = tls?.fingerprint,
                videoDecoder = video.ffmpeg?.absolutePath,
            )
        }
        refresh()
        enqueueScan()
        Log.i(TAG, "server up on :$port / :$httpsPort, library ${store.root}, volumeId=$volumeId")
        Log.i(TAG, "pairing link: ${_status.value.pairingLink}")

        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { refresh() }
            }
        }
        return true
    }

    /** Re-reads addresses and counters. Also called by the window after a user action. */
    suspend fun refresh() {
        val s = _status.value
        val link = Pairing.payload(s.port, s.httpsPort, config.token(), s.fingerprint)
        val addresses = NetInterfaces.displayEndpoints().map { "${it.label}: ${it.host}" }
        val assets = runCatching { db.assets().count() }.getOrDefault(s.assets)
        val pending = runCatching { db.jobs().countByState(JobState.PENDING) }.getOrDefault(s.pendingJobs)
        val blocked = runCatching { db.jobs().countByState(JobState.BLOCKED) }.getOrDefault(s.blockedJobs)
        _status.update {
            // An empty address list means the network is mid-change, not that the PC has no
            // addresses; keeping the old link beats blanking the pairing code.
            if (addresses.isEmpty()) it.copy(assets = assets, pendingJobs = pending, blockedJobs = blocked)
            else it.copy(pairingLink = link, addresses = addresses, assets = assets, pendingJobs = pending, blockedJobs = blocked)
        }
    }

    suspend fun enqueueScan() {
        val now = System.currentTimeMillis()
        db.jobs().enqueue(
            JobEntity(
                type = JobType.SCAN_VOLUME,
                payload = "{}",
                // Unique dedupe key: requesting a scan while one is queued is a no-op.
                dedupeKey = "${JobType.SCAN_VOLUME}:pending",
                priority = 10,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "scan enqueued")
    }

    fun stop() {
        runCatching { http?.stop() }
        http = null
        runCatching { tlsProxy?.stop() }
        tlsProxy = null
        heartbeat?.cancel()
        runCatching { runBlocking { runner?.stop() } }
        runner = null
        scope.cancel()
        if (::db.isInitialized) runCatching { db.close() }
        _status.update { it.copy(running = false) }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _status.update { it.copy(running = false, error = message) }
        stop()
        _status.update { it.copy(error = message) }
    }

    private fun bindMessage(t: Throwable, port: Int): String {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is BindException) {
                return "Port $port is already in use by another program. Close it, or set a different " +
                    "port in config.properties."
            }
            cause = cause.cause
        }
        return "Server failed to start: ${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * Registers the library folder as a volume and returns its row id, which `asset_files` points
     * at. Keyed on the id file inside the folder, so a library that moves to another drive letter
     * is still the same volume.
     */
    private suspend fun ensureVolume(store: FolderStore): Long {
        val key = store.volumeKey()
        val cap = runCatching { store.capacity() }.getOrNull()
        val now = System.currentTimeMillis()
        val existing = db.volumes().byKey(key)
        return db.volumes().upsert(
            VolumeEntity(
                id = existing?.id ?: 0,
                volumeKey = key,
                kind = store.kind.name,
                label = store.label,
                treeUri = null,
                totalBytes = cap?.totalBytes,
                freeBytes = cap?.availableBytes,
                mounted = store.isMounted,
                lastSeenAt = now,
            ),
        ).let { inserted -> if (inserted > 0) inserted else (db.volumes().byKey(key)?.id ?: -1L) }
    }
}
