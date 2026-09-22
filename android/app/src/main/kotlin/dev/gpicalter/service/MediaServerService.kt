package dev.gpicalter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.gpicalter.data.entity.JobEntity
import dev.gpicalter.data.entity.JobType
import dev.gpicalter.di.AppContainer
import dev.gpicalter.index.LibraryIndexer
import dev.gpicalter.index.StoreScanner
import dev.gpicalter.jobs.JobHandler
import dev.gpicalter.jobs.JobRunner
import dev.gpicalter.jobs.ScanHandler
import dev.gpicalter.jobs.ThumbnailHandler
import dev.gpicalter.media.ThumbnailGenerator
import dev.gpicalter.probe.ProbeActivity
import dev.gpicalter.server.Auth
import dev.gpicalter.server.HttpServer
import dev.gpicalter.server.CertStore
import dev.gpicalter.server.NetInterfaces
import dev.gpicalter.server.TlsProxy
import dev.gpicalter.server.UploadService
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "gpic"
private const val CHANNEL = "server"
private const val NOTIF_ID = 1
private const val HEARTBEAT_INTERVAL_MS = 60_000L

/**
 * Hosts the HTTP server and the job runner for as long as the phone is on.
 *
 * ### Why `specialUse`
 * On Android 15+, `dataSync` -- the obvious pick for something that syncs data -- is capped at six
 * cumulative hours per 24, and is forbidden from being started by `BOOT_COMPLETED`, so the server
 * could not come back after a reboot. `specialUse` is exempt from both. This device runs Android 13,
 * where neither restriction exists yet, so the declaration is forward-compatibility rather than
 * something being proven here.
 *
 * ### Why the wake locks
 * A foreground service keeps the *process* alive but does not stop the CPU suspending, and a
 * suspended CPU does not accept TCP connections. Without a partial wake lock the server becomes
 * unreachable within minutes of the screen going off.
 */
class MediaServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("gpic-svc"))
    private lateinit var container: AppContainer

    private var server: HttpServer? = null
    private var runner: JobRunner? = null
    private var tlsProxy: TlsProxy? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeats = 0
    private var bringingUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Explicit stop: remember that, so an app update does not resurrect it.
            container.prefs.wasRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Must reach startForeground within ~5s or the system raises an ANR, so this notification is
        // built from constants only -- no store access, no database, no network.
        startInForeground(buildNotification("Starting..."))

        if (server == null && !bringingUp) {
            bringingUp = true
            scope.launch {
                try {
                    bringUp()
                } catch (t: Throwable) {
                    Log.e(TAG, "bring-up failed", t)
                    ServerState.update {
                        it.copy(running = false, error = "${t.javaClass.simpleName}: ${t.message}")
                    }
                    notify(buildNotification("Failed: ${t.javaClass.simpleName}"))
                } finally {
                    bringingUp = false
                }
            }
        }
        return START_STICKY
    }

    private suspend fun bringUp() {
        val store = container.buildStore()
        val volumeId = container.ensureVolume(store)
        val port = container.prefs.port
        val token = container.prefs.token()

        acquireLocks()

        val generator = ThumbnailGenerator(this, store)
        val indexer = LibraryIndexer(container.db, store, volumeId)
        val scanner = StoreScanner(store, indexer)

        val handlers: Map<String, JobHandler> = mapOf(
            JobType.THUMBNAIL to ThumbnailHandler(container.db, store, generator),
            JobType.SCAN_VOLUME to ScanHandler(scanner) { p ->
                ServerState.update {
                    it.copy(scanNote = "scanned ${p.scanned}, new ${p.indexed}, dup ${p.duplicates}, skip ${p.skipped}, fail ${p.failed}")
                }
            },
        )

        runner = JobRunner(
            jobs = container.db.jobs(),
            handlers = handlers,
            dispatcher = container.dispatchers.dbIo,
            concurrency = ::concurrency,
            owner = "${android.os.Process.myPid()}:${System.currentTimeMillis()}",
        ).also { it.start(scope) }

        // Any work parked because a volume was missing gets a fresh chance on every start-up.
        container.db.jobs().unblockAll(System.currentTimeMillis())

        val uploads = UploadService(
            db = container.db,
            store = store,
            indexer = indexer,
            stagingRoot = File(filesDir, "staging"),
        )
        // Transfers abandoned by a flaky client must not slowly fill internal storage.
        uploads.sweepStale()

        // The certificate has to name every address the server may be reached at, so it is built
        // from the interfaces as they are right now. If the phone later gains an address the cert
        // does not cover, clients reaching it by that name will reject the certificate.
        val sans = NetInterfaces.endpoints().map { it.host.trim('[', ']') }
        val tls = CertStore(File(filesDir, "tls")).loadOrCreate(sans)
        if (tls == null) Log.w(TAG, "TLS unavailable; serving plain HTTP only")

        val http = HttpServer(
            store = store,
            db = container.db,
            assets = assets,
            thumbs = generator,
            uploads = uploads,
            auth = Auth(token),
            port = port,
            httpsPort = port + 363,
            tls = tls,
            openFds = { File("/proc/self/fd").list()?.size ?: -1 },
            onScanRequested = { scope.launch { enqueueScan() } },
        )
        http.start()
        server = http

        // Started after the HTTP server, since it relays to it.
        tls?.let { identity ->
            val proxy = TlsProxy(identity, listenPort = port + 363, targetPort = port)
            if (proxy.start(scope)) tlsProxy = proxy
        }

        // Asking the router to accept inbound connections is opt-in, and only ever for the TLS
        // port. Port 8080 carries the token in clear text and must never leave the LAN.
        if (container.prefs.remoteAccess) {
            container.remoteAccess.keepOpen(scope, port + 363)
        }

        val urls = NetInterfaces.endpoints().map { "${it.label}: ${it.url(port)}" }
        ServerState.update {
            it.copy(
                running = true,
                port = port,
                startedAt = System.currentTimeMillis(),
                backend = store.kind.name,
                urls = urls,
                token = token,
                httpsUrl = tls?.let { _ ->
                    // Prefer an address reachable from outside; a LAN address over TLS still works
                    // but defeats the point of showing a secure code.
                    val eps = NetInterfaces.endpoints()
                    val pick = eps.firstOrNull { it.host.startsWith("[") } ?: eps.firstOrNull()
                    pick?.let { "https://${it.host}:${port + 363}" }
                },
                tlsFingerprint = tls?.fingerprint,
                error = null,
            )
        }
        container.prefs.wasRunning = true
        Log.i(TAG, "server up on :$port, backend ${store.kind}, volumeId=$volumeId, urls=$urls")
        notify(buildNotification("Serving ${store.label} on :$port"))

        startHeartbeat()
    }

    private suspend fun enqueueScan() {
        val now = System.currentTimeMillis()
        container.db.jobs().enqueue(
            JobEntity(
                type = JobType.SCAN_VOLUME,
                payload = "{}",
                // Unique dedupe key: requesting a scan while one is queued is a no-op, not a
                // second walk of the whole library.
                dedupeKey = "${JobType.SCAN_VOLUME}:pending",
                priority = 10,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Log.i(TAG, "scan enqueued")
    }

    /**
     * How much parallel work the phone can take right now. Re-read on every batch, because on a
     * phone this genuinely changes minute to minute -- and sustained decoding is what makes it hot.
     */
    private fun concurrency(): Int {
        val thermal = thermalStatus()
        val charging = isCharging()
        val battery = batteryPercent()
        return when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 0
            !charging && battery in 0..30 -> 0
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            charging -> container.dispatchers.decodeParallelism
            else -> 1
        }
    }

    /**
     * M1 instrumentation, running from day one so the soak clock starts as early as possible. A gap
     * longer than a few minutes means the process was killed -- on a Samsung, most likely by One UI's
     * battery management rather than by the platform.
     */
    private fun startHeartbeat() = scope.launch {
        val file = File(filesDir, "heartbeat.csv")
        if (!file.exists()) file.appendText("epochMs,pid,batteryPct,charging,thermal\n")
        while (isActive) {
            val line = "${System.currentTimeMillis()},${android.os.Process.myPid()}," +
                "${batteryPercent()},${isCharging()},${thermalStatus()}\n"
            runCatching { file.appendText(line) }
            heartbeats++
            ServerState.update { it.copy(heartbeats = heartbeats, lastHeartbeatAt = System.currentTimeMillis()) }
            refreshAddresses()
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    /**
     * Re-reads the phone's addresses, because they change underneath a running server.
     *
     * Observed on Airtel: the delegated IPv6 prefix rotated overnight, so the phone kept its
     * interface identifier but moved to a different /64. Anything that captured an address at
     * start-up is then pointing at somewhere nobody lives -- and the pairing QR embeds exactly that,
     * so a phone paired from a stale code fails with a connect timeout that reads as "the server is
     * down" rather than "this address expired".
     *
     * The certificate is deliberately **not** regenerated when this happens. Its subject names would
     * be stale, but every paired client pins the fingerprint rather than the name, so a new
     * certificate would break all of them to fix something none of them check.
     */
    private fun refreshAddresses() {
        val endpoints = NetInterfaces.endpoints()
        // An empty list means the network is mid-reconfiguration, not that the phone has no
        // addresses. Publishing that would blank the pairing screen for no reason.
        if (endpoints.isEmpty()) return
        val port = container.prefs.port
        val urls = endpoints.map { "${it.label}: ${it.url(port)}" }
        val secure = ServerState.state.value.tlsFingerprint?.let {
            val preferred = endpoints.firstOrNull { e -> e.host.startsWith("[") } ?: endpoints.first()
            "https://${preferred.host}:${port + 363}"
        }
        ServerState.update { current ->
            if (current.urls == urls && current.httpsUrl == secure) {
                current
            } else {
                Log.i(TAG, "addresses changed: $urls")
                current.copy(urls = urls, httpsUrl = secure)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "service stopping")
        runCatching { server?.stop() }
        server = null
        runCatching { tlsProxy?.stop() }
        tlsProxy = null
        runCatching { container.remoteAccess.stop() }
        runner = null
        releaseLocks()
        scope.cancel()
        ServerState.update { it.copy(running = false, urls = emptyList()) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ locks

    private fun acquireLocks() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpic:server").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "gpic:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // On API < 34 the specialUse type does not exist; the 2-arg form is correct there.
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Media server", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Ongoing status of the photo library server"
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ProbeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("gpicAlter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    // ------------------------------------------------------------------ telemetry

    private fun batteryStatus(): Intent? =
        registerReceiver(null as BroadcastReceiver?, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryPercent(): Int {
        val i = batteryStatus() ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun isCharging(): Boolean =
        (batteryStatus()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun thermalStatus(): Int = try {
        (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
    } catch (t: Throwable) {
        -1
    }

    companion object {
        const val ACTION_START = "dev.gpicalter.START"
        const val ACTION_STOP = "dev.gpicalter.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, MediaServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MediaServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
