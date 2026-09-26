package dev.gpicalter.media

import dev.gpicalter.storage.LibraryStore
import dev.gpicalter.storage.StoreEntry

data class MediaMetadata(
    val mediaType: Int,
    val width: Int? = null,
    val height: Int? = null,
    /** Detected rotation in degrees. Browsers rotate JPEGs from EXIF themselves. */
    val orientation: Int = 0,
    val durationMs: Long? = null,
    val capturedAt: Long,
    val capturedAtSource: Int,
    val tzOffsetMinutes: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
)

/**
 * The seam between the indexer, which is shared logic, and the decoders, which are not.
 *
 * DIVERGES FROM gpicAlter, where LibraryIndexer calls MetadataExtractor with a
 * ParcelFileDescriptor and decodes the blurhash with BitmapFactory itself. Pulling both behind this
 * interface is what lets the indexer be one file on both platforms: Android implements it with
 * BitmapFactory/ExifInterface/MediaMetadataRetriever, the desktop with ImageIO/metadata-extractor.
 */
interface MediaProbe {

    fun metadata(store: LibraryStore, relPath: String, mime: String, modifiedAt: Long, name: String): MediaMetadata

    /** Null when this platform cannot decode the file, which is never an indexing failure. */
    fun blurhash(store: LibraryStore, entry: StoreEntry, orientation: Int): String?
}
