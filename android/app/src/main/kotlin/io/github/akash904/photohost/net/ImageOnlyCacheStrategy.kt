package io.github.akash904.photohost.net

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * Coil's disk cache, restricted to things that are actually images.
 *
 * Coil 3's default strategy writes every 2xx response to disk -- and 404 -- whatever its headers
 * say, and then returns the cached copy forever without asking the server again
 * (coil-network-core 3.6.3, `DefaultCacheStrategy`). A library server answers a thumbnail that is
 * not rendered yet with a short JSON status, so a tile scrolled past too early cached that JSON as
 * its image and stayed blank permanently. Found on a real phone: seven cached `{"state": ...}`
 * bodies, one for every item viewed before its thumbnail existed.
 *
 * Servers now answer "not yet" with 503, which Coil never caches. This makes the client correct on
 * its own too, against any server:
 *
 *  - **write** only 2xx responses whose Content-Type is an image (plus 304 revalidations of an
 *    entry that is already an image, merged exactly as the default does);
 *  - **read** a cached entry only if it is an image. Anything else -- an entry poisoned before this
 *    existed -- is refetched, so an affected phone repairs itself without clearing its cache.
 *
 * One known cost of the repair path, from reading Coil's `NetworkFetcher`: when the refetch of a
 * poisoned entry fails again (thumbnail still not ready), Coil drops its handle on the old entry
 * without closing it, and until the app restarts that key cannot be rewritten -- the image still
 * displays once it exists, it just is not cached for that session. Harmless, and gone on restart.
 */
@OptIn(ExperimentalCoilApi::class)
object ImageOnlyCacheStrategy : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        if (isImage(cacheResponse)) CacheStrategy.ReadResult(cacheResponse) else CacheStrategy.ReadResult(networkRequest)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        // Revalidated: keep the stored body, refresh its headers -- as DefaultCacheStrategy does.
        if (networkResponse.code == HTTP_NOT_MODIFIED && cacheResponse != null && isImage(cacheResponse)) {
            val merged = cacheResponse.headers.newBuilder().apply {
                networkResponse.headers.asMap().forEach { (name, values) -> set(name, values) }
            }.build()
            return CacheStrategy.WriteResult(networkResponse.copy(headers = merged, body = null))
        }
        if (networkResponse.code in 200 until 300 && isImage(networkResponse)) {
            return CacheStrategy.WriteResult(networkResponse)
        }
        return CacheStrategy.WriteResult.DISABLED
    }

    private fun isImage(response: NetworkResponse): Boolean =
        response.headers["Content-Type"]?.trim()?.startsWith("image/", ignoreCase = true) == true

    private const val HTTP_NOT_MODIFIED = 304
}
