package dev.gpicalter.media

import dev.gpicalter.storage.LibraryStore
import dev.gpicalter.storage.StoreEntry

/** [MediaProbe] on ImageIO and metadata-extractor. */
class DesktopMediaProbe(
    private val extractor: MetadataExtractor = MetadataExtractor(),
) : MediaProbe {

    override fun metadata(store: LibraryStore, relPath: String, mime: String, modifiedAt: Long, name: String): MediaMetadata =
        store.openRead(relPath).use { extractor.extract(it, mime, modifiedAt, name) }

    /**
     * Decodes a deliberately tiny image for the blurhash, subsampled the way the phone does it so a
     * 50 MP photo costs milliseconds rather than a full decode.
     *
     * DIVERGES FROM gpicAlter on purpose: the image is rotated by [orientation] before encoding.
     * The phone encodes the stored, unrotated pixels while reporting rotated width and height, so a
     * portrait photo carrying EXIF orientation 6 or 8 gets a sideways placeholder there.
     */
    override fun blurhash(store: LibraryStore, entry: StoreEntry, orientation: Int): String? {
        if (!ImageDecoding.canDecode(entry.mime)) return null
        return store.openRead(entry.relPath).use { handle ->
            val bounds = ImageDecoding.bounds(handle.inputStream()) ?: return@use null
            val sample = ImageDecoding.sampleFor(bounds.width, 64)
            val img = ImageDecoding.decodeSampled(handle.inputStream(), sample) ?: return@use null
            val small = ImageDecoding.fitLongEdge(ImageDecoding.rotate(img, orientation), 32)
            BlurHash.encode(ImageDecoding.pixels(small), small.width, small.height)
        }
    }
}
