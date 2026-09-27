package io.github.akash904.photohost.media

import io.github.akash904.photohost.data.entity.ThumbSize
import io.github.akash904.photohost.storage.LibraryStore
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the two thumbnail sizes.
 *
 * DESKTOP REIMPLEMENTATION of the phone app's ThumbnailGenerator, which uses BitmapFactory, Matrix and
 * MediaMetadataRetriever. The contract is kept exactly -- same two sizes, same edge rules, same JPEG
 * quality, same cache layout -- because HttpServer and ThumbnailCache are shared code that assume
 * it.
 *
 * Decoders, first that works: ImageIO (JPEG, PNG, GIF, BMP, WebP); then Windows' own codecs for
 * HEIC, AVIF and RAW ([WindowsCodecs]). Video frames: Windows' thumbnailer, then ffmpeg
 * ([VideoFrames]). When a decoder is missing the thumbnail job parks itself as BLOCKED rather than
 * failing, so it runs once the codec is installed.
 *
 * Files are named by content hash, so identical bytes share one thumbnail, a file that moves keeps
 * its thumbnail, and the cache can never go stale relative to its source.
 */
class ThumbnailGenerator(
    /** Where thumbnails live. On a PC that is the data directory, never the library folder. */
    val root: File,
    private val store: LibraryStore,
    private val video: VideoFrames = VideoFrames(null),
    /**
     * A real filesystem path for a stored file, or null. ffmpeg has to open videos itself: an MP4
     * with its index at the end cannot be decoded from a pipe, because the decoder must seek.
     */
    private val localPath: (String) -> java.nio.file.Path? = { null },
) {
    init {
        root.mkdirs()
    }

    data class Rendered(val relPath: String, val bytes: Long, val width: Int, val height: Int)

    /**
     * Whether any decoder on this PC might handle the type. "Might": Windows' decoders depend on
     * which Store extensions are installed, which is only known by trying, and a missing one ends the
     * job as BLOCKED rather than FAILED (see [DecoderUnavailableException]).
     */
    fun canRender(mime: String, isVideo: Boolean): Boolean = when {
        isVideo -> WindowsCodecs.available || video.available
        ImageDecoding.canDecode(mime) -> true
        else -> WindowsCodecs.available && mime.startsWith("image/")
    }

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
    fun render(
        contentHash: String,
        relPath: String,
        isVideo: Boolean,
        orientation: Int,
        sizeClass: Int,
        durationMs: Long?,
    ): Rendered {
        val target = if (sizeClass == ThumbSize.GRID) GRID_SHORT_EDGE else PREVIEW_LONG_EDGE
        val quality = if (sizeClass == ThumbSize.GRID) 75 else 82

        val frame = if (isVideo) videoFrame(relPath, durationMs) else imageFrame(relPath, sizeClass, target)

        // Windows' video thumbnailer hands back an upright frame; everything else arrives as stored
        // and is turned by the orientation the index holds.
        val decoded = frame.image
        val oriented = if (orientation != 0 && !frame.upright) ImageDecoding.rotate(decoded, orientation) else decoded

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

    private class Frame(val image: java.awt.image.BufferedImage, val upright: Boolean)

    /** Windows' thumbnailer first; ffmpeg when Windows cannot decode it or is not there. */
    private fun videoFrame(relPath: String, durationMs: Long?): Frame {
        val path = localPath(relPath) ?: throw IllegalStateException("no local path for $relPath")
        var windowsFailure: DecoderUnavailableException? = null
        if (WindowsCodecs.available) {
            try {
                return Frame(WindowsCodecs.videoThumbnail(path.toFile(), PREVIEW_LONG_EDGE), upright = true)
            } catch (e: DecoderUnavailableException) {
                windowsFailure = e
            }
        }
        if (video.available) {
            video.frame(path, durationMs)?.let { return Frame(it, upright = false) }
        }
        throw windowsFailure ?: IllegalStateException("no video decoder produced a frame")
    }

    private fun imageFrame(relPath: String, sizeClass: Int, target: Int): Frame {
        // Two passes, as on the phone: header for the dimensions, then a subsampled decode. Each
        // pass gets a fresh stream from position zero, so there is no rewind to forget.
        val viaJava = store.openRead(relPath).use { handle ->
            val bounds = ImageDecoding.bounds(handle.inputStream()) ?: return@use null
            val relevant = if (sizeClass == ThumbSize.GRID) {
                minOf(bounds.width, bounds.height)
            } else {
                maxOf(bounds.width, bounds.height)
            }
            ImageDecoding.decodeSampled(handle.inputStream(), ImageDecoding.sampleFor(relevant, target))
        }
        if (viaJava != null) return Frame(viaJava, upright = false)

        // HEIC, AVIF, RAW: Windows' decoders, asked directly for roughly the size needed so a 50 MP
        // photo is never materialised whole. For GRID the short edge must reach [target], so the long
        // edge requested is scaled up by the aspect ratio.
        if (!WindowsCodecs.available) throw IllegalStateException("no decoder for $relPath")
        val file = localPath(relPath)?.toFile() ?: throw IllegalStateException("no local path for $relPath")
        val maxEdge = if (sizeClass == ThumbSize.GRID) {
            val (w, h) = WindowsCodecs.imageSize(file) ?: (target to target)
            val long = maxOf(w, h)
            val short = maxOf(1, minOf(w, h))
            minOf(long, (target.toLong() * long / short).toInt() + 1)
        } else {
            target
        }
        return Frame(WindowsCodecs.decodeImage(file, maxEdge), upright = false)
    }

    companion object {
        /** 256px short edge: retina-adequate for a 128px grid cell, roughly 18 KB. */
        const val GRID_SHORT_EDGE = 256
        /** 1440px long edge: good enough fullscreen on a laptop, roughly 250 KB. */
        const val PREVIEW_LONG_EDGE = 1440
    }
}
