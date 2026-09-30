package io.github.akash904.photohost.net

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.delay

/**
 * Retries an image the server has said is not ready yet.
 *
 * A server makes each photo's thumbnails after it arrives, and until then answers 503 with a
 * two-second Retry-After. Nothing here asked again: the viewer showed a black screen for a photo
 * uploaded moments before, and stayed black until you swiped away and back, which read as the
 * upload having failed. Retrying in the image loader fixes the grid, the viewer and the trash at
 * once, and while it waits the request is still loading, so the blurhash placeholder stays up.
 *
 * Only 503 is retried -- it is the server's explicit "not yet". Anything else is a real failure
 * and returns at once. A request whose screen has gone away is cancelled, which ends the wait.
 */
class NotReadyRetry(
    private val attempts: Int = 15,
    private val waitMillis: Long = 2_000,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var result = chain.proceed()
        repeat(attempts) {
            val code = ((result as? ErrorResult)?.throwable as? HttpException)?.response?.code
            if (code != 503) return result
            delay(waitMillis)
            result = chain.proceed()
        }
        return result
    }
}
