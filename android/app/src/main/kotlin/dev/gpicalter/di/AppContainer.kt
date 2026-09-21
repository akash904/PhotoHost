package dev.gpicalter.di

import android.content.Context
import dev.gpicalter.core.AppDispatchers
import dev.gpicalter.core.Prefs
import dev.gpicalter.data.db.AppDatabase
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.gpicalter.data.entity.VolumeEntity
import dev.gpicalter.net.LibraryApi
import okhttp3.OkHttpClient
import dev.gpicalter.storage.InternalStore
import dev.gpicalter.storage.LibraryStore
import dev.gpicalter.storage.SafStore
import dev.gpicalter.storage.StoreKind

/**
 * Hand-rolled dependency graph. One process, one graph, a handful of singletons -- a DI framework's
 * annotation-processing round would cost more build time than it saves here.
 */
class AppContainer private constructor(context: Context) {

    private val app = context.applicationContext

    val prefs = Prefs(app)
    val dispatchers = AppDispatchers()
    val db = AppDatabase.get(app)

    /** How this app reads a library -- loopback when serving locally, a URL when remote. */
    val api: LibraryApi by lazy { LibraryApi(prefs) }

    /**
     * Thumbnails are authenticated like every other route, so the image loader has to carry the
     * token. The header is read per request rather than captured once, because the endpoint can be
     * repointed at a different server without restarting the app.
     */
    val imageLoader: ImageLoader by lazy {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", api.authHeader())
                        .build(),
                )
            }
            .build()
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
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
