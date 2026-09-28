package io.github.akash904.photohost.media

import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse

/**
 * A photo or video in this phone's own gallery, drawn from the thumbnail Android already keeps.
 *
 * Handing Coil the MediaStore URI directly worked for photos only: Coil has no video decoder, so
 * every video tile in the picker stayed blank. It was also wasteful for photos, decoding a 12 MP
 * original, even subsampled, to fill a 96 dp square. ContentResolver.loadThumbnail returns the
 * system's own thumbnail for both, the one the Gallery app shows, usually straight from its cache.
 */
data class DeviceThumb(val uri: Uri)

class DeviceThumbFetcher(private val data: DeviceThumb, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // The tile's size as Coil measured it; a sensible grid size when it is not known.
        val width = options.size.width.pxOrElse { DEFAULT_PX }
        val height = options.size.height.pxOrElse { DEFAULT_PX }
        val bitmap = options.context.contentResolver.loadThumbnail(
            data.uri,
            android.util.Size(width, height),
            null,
        )
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<DeviceThumb> {
        override fun create(data: DeviceThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            DeviceThumbFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}

/** Lets scrolled-past tiles come back from Coil's memory cache instead of asking Android again. */
class DeviceThumbKeyer : Keyer<DeviceThumb> {
    override fun key(data: DeviceThumb, options: Options): String = "device-thumb:${data.uri}"
}
