package dev.gpicalter.media

import dev.gpicalter.data.entity.ThumbSize
import dev.gpicalter.storage.LibraryStore
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the two thumbnail sizes.
 *
 * DESKTOP REIMPLEMENTATION of gpicAlter's ThumbnailGenerator, which uses BitmapFactory, Matrix and
 * MediaMetadataRetriever. The contract is kept exactly -- same two sizes, same edge rules, same JPEG
 * quality, same cache layout -- because HttpServer and ThumbnailCache are shared code that assume
 * it.
 *
 * Videos and HEIC are not rendered yet: the phone uses platform codecs the JVM does not have.
 * [canRender] says so up front, and the thumbnail job parks itself as BLOCKED rather than failing.
 *
 * Files are named by content hash, so identical bytes share one thumbnail, a file that moves keeps
 * its thumbnail, and the cache can never go stale relative to its source.
 */
class ThumbnailGenerator(
    /** Where thumbnails live. On a PC that is the data directory, never the library folder. */
    val root: File,
    private val store: LibraryStore,
) {
    init {
        root.mkdirs()
    }

    data class Rendered(val relPath: String, val bytes: Long, val width: Int, val height: Int)

    fun canRender(mime: String, isVideo: Boolean): Boolean = !isVideo && ImageDecoding.canDecode(mime)

    fun cacheFile(contentHash: String, sizeClass: Int): File =
        File(root, relPathFor(contentHash, sizeClass))

    fun relPathFor(contentHash: String, sizeClass: Int): String {
        val shard = contentHash.take(2)
        val suffix = if (sizeClass == ThumbSize.GRID) "g" else "p"
        return "$shard/${contentHash}_$suffix.jpg"
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(contentHash: String, sizeClass: Int): Boolean = cacheFile(contentHash, sizeClass).delete()

    /**
     * @param relPath where the original lives on the store
     * @param isVideo whether to pull a frame instead of decoding an image
     * @param orientation degrees of rotation to bake in, so no client ever has to rotate anything
     */
    @Suppress("UNUSED_PARAMETER")
    fun render(
        contentHash: String,
        relPath: String,
        isVideo: Boolean,
        orientation: Int,
        sizeClass: Int,
        durationMs: Long?,
    ): Rendered {
        if (isVideo) throw UnsupportedOperationException("video frames need ffmpeg, not shipped yet")
        val target = if (sizeClass == ThumbSize.GRID) GRID_SHORT_EDGE else PREVIEW_LONG_EDGE
        val quality = if (sizeClass == ThumbSize.GRID) 75 else 82

        // Two passes, as on the phone: header for the dimensions, then a subsampled decode. Each
        // pass gets a fresh stream from position zero, so there is no rewind to forget.
        val decoded = store.openRead(relPath).use { handle ->
            val bounds = ImageDecoding.bounds(handle.inputStream())
                ?: throw IllegalStateException("decoder could not read dimensions")
            val relevant = if (sizeClass == ThumbSize.GRID) {
                minOf(bounds.width, bounds.height)
            } else {
                maxOf(bounds.width, bounds.height)
            }
            ImageDecoding.decodeSampled(handle.inputStream(), ImageDecoding.sampleFor(relevant, target))
        } ?: throw IllegalStateException("decoder produced no image")

        val oriented = if (orientation != 0) ImageDecoding.rotate(decoded, orientation) else decoded

        val w = oriented.width
        val h = oriented.height
        val current = if (sizeClass == ThumbSize.GRID) minOf(w, h) else maxOf(w, h)
        val scaled = if (current <= target) {
            oriented
        } else {
            val ratio = target.toFloat() / current
            ImageDecoding.scale(oriented, maxOf(1, (w * ratio).roundToInt()), maxOf(1, (h * ratio).roundToInt()))
        }

        val out = cacheFile(contentHash, sizeClass)
        ImageDecoding.writeJpeg(scaled, out, quality)
        return Rendered(relPathFor(contentHash, sizeClass), out.length(), scaled.width, scaled.height)
    }

    companion object {
        /** 256px short edge: retina-adequate for a 128px grid cell, roughly 18 KB. */
        const val GRID_SHORT_EDGE = 256
        /** 1440px long edge: good enough fullscreen on a laptop, roughly 250 KB. */
        const val PREVIEW_LONG_EDGE = 1440
    }
}
