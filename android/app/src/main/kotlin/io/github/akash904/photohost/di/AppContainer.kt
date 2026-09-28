package io.github.akash904.photohost.di

import android.content.Context
import io.github.akash904.photohost.core.AppDispatchers
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.data.db.AppDatabase
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.akash904.photohost.data.entity.VolumeEntity
import io.github.akash904.photohost.net.ImageOnlyCacheStrategy
import io.github.akash904.photohost.net.LibraryApi
import io.github.akash904.photohost.net.NetworkWatcher
import io.github.akash904.photohost.net.RemoteAccess
import io.github.akash904.photohost.net.Pinning
import okhttp3.OkHttpClient
import io.github.akash904.photohost.storage.InternalStore
import io.github.akash904.photohost.storage.LibraryStore
import io.github.akash904.photohost.storage.SafStore
import io.github.akash904.photohost.storage.StoreKind

/**
 * Hand-rolled dependency graph. One process, one graph, a handful of singletons -- a DI framework's
 * annotation-processing round would cost more build time than it saves here.
 */
class AppContainer private constructor(context: Context) {

    private val app = context.applicationContext

    val prefs = Prefs(app).apply {
        // Libraries first: the role migration reads "is a server address set", which after an
        // upgrade only answers correctly once the old single address has become a library.
        migrateLibrariesIfNeeded()
        // Before anything else touches preferences, and in particular before the first-run dialog
        // sets `onboarded`, which this reads to tell an existing install apart from a new one.
        migrateRoleIfUnrecorded()
    }
    val dispatchers = AppDispatchers()
    val db = AppDatabase.get(app)

    /** The library on screen -- loopback for this phone's own, a URL for a paired one. */
    val api: LibraryApi by lazy { LibraryApi(prefs, { prefs.activeLibrary() }) { httpFor(it) } }

    /**
     * The backup target, which is not necessarily the library on screen. A separate client, so
     * switching what you browse mid-backup cannot send the rest of the upload somewhere else.
     * The fallback only keeps this non-null; the backup worker refuses to run without a target.
     */
    val backupApi: LibraryApi by lazy {
        LibraryApi(prefs, { prefs.backupLibrary() ?: prefs.activeLibrary() }) { httpFor(it) }
    }

    /**
     * Shared so the service's renewal loop and the Settings buttons act on one object. Two
     * instances would mean a manual retry that does not cancel the loop that is about to
     * overwrite its result.
     */
    val remoteAccess: RemoteAccess by lazy { RemoteAccess(app) }

    /** Started on first use and never stopped: it lives as long as the process does. */
    val network: NetworkWatcher by lazy { NetworkWatcher(app).also { it.start() } }

    /**
     * Thumbnails are authenticated like every other route, so the image loader has to carry the
     * token. The header is read per request rather than captured once, because the endpoint can be
     * repointed at a different server without restarting the app.
     */
    /**
     * One HTTP stack for everything that talks to the library: the API client, Coil's thumbnails
     * and ExoPlayer's video. They must share it, or a pinned certificate would be honoured on some
     * requests and rejected on others, which fails in a way that looks like a broken server.
     *
     * Rebuilt whenever the pin changes, since pairing with a different server changes what to trust.
     */
    private val httpCache = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    /**
     * The stack for one library: trusting only its certificate, presenting only its token.
     *
     * One per library rather than one shared, because the browsing and backup clients can be talking
     * to different libraries at the same moment, and a shared interceptor would stamp one library's
     * token onto requests meant for the other. The token is looked up per request, by the library's
     * id, so regenerating this phone's own token takes effect without rebuilding anything.
     */
    fun httpFor(lib: io.github.akash904.photohost.core.Library): OkHttpClient =
        httpCache.getOrPut("${lib.id}|${lib.fingerprint}") {
            val id = lib.id
            Pinning.clientFor(
                OkHttpClient.Builder().addInterceptor { chain ->
                    val token = prefs.library(id)?.token ?: lib.token
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build(),
                    )
                },
                lib.fingerprint,
            )
        }

    /** The stack for the library on screen: what Coil and ExoPlayer load through. */
    val http: OkHttpClient
        get() = httpFor(prefs.activeLibrary())

    /** Drops cached stacks so the next request picks up a new pin. */
    fun invalidateHttp() {
        httpCache.clear()
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            // Delegating rather than passing `http` directly: Coil memoizes the factory, so a
            // direct reference would pin the stack that existed at first image load and ignore
            // any later change of certificate pin.
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okhttp3.Call.Factory { request -> http.newCall(request) } },
                        // Coil's default caches any 2xx forever, including a server's "thumbnail
                        // not ready" JSON; see ImageOnlyCacheStrategy.
                        cacheStrategy = { ImageOnlyCacheStrategy },
                    ),
                )
                // This phone's own photos and videos, for the backup picker; see DeviceThumbs.kt.
                add(io.github.akash904.photohost.media.DeviceThumbFetcher.Factory())
                add(io.github.akash904.photohost.media.DeviceThumbKeyer())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.25).build() }
            // Thumbnails are immutable and named by content hash, so caching them on disk is free
            // correctness-wise and saves re-fetching the whole grid on every cold start.
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("thumb_http"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    /** Built fresh each time: the backend can change, and a SAF grant can be re-picked. */
    fun buildStore(): LibraryStore {
        val uri = prefs.treeUri
        return if (prefs.backend == StoreKind.SAF.name && uri != null) SafStore(app, uri) else InternalStore(app)
    }

    /**
     * Registers the store as a volume and returns its row id, which `asset_files` points at.
     *
     * The key is what must survive everything: "internal" for the phone, or the exFAT serial for a
     * drive. Never the SAF tree URI -- that is a cache which can change on any replug.
     */
    suspend fun ensureVolume(store: LibraryStore): Long {
        val key = when (store) {
            is SafStore -> store.volumeKey
            else -> "internal"
        }
        val cap = runCatching { store.capacity() }.getOrNull()
        val now = System.currentTimeMillis()
        val existing = db.volumes().byKey(key)
        return db.volumes().upsert(
            VolumeEntity(
                id = existing?.id ?: 0,
                volumeKey = key,
                kind = store.kind.name,
                label = store.label,
                treeUri = (store as? SafStore)?.treeUri?.toString(),
                totalBytes = cap?.totalBytes,
                freeBytes = cap?.availableBytes,
                mounted = runCatching { store.isMounted }.getOrDefault(false),
                lastSeenAt = now,
            ),
        ).let { inserted -> if (inserted > 0) inserted else (db.volumes().byKey(key)?.id ?: -1L) }
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context).also { instance = it } }
    }
}
