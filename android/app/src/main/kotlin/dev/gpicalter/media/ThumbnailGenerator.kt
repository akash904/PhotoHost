package dev.gpicalter.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.system.Os
import android.system.OsConstants
import dev.gpicalter.data.entity.ThumbSize
import dev.gpicalter.storage.LibraryStore
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the two thumbnail sizes from a descriptor.
 *
 * Only two sizes exist, and that is a deliberate limit: every additional size multiplies the
 * backfill cost across the whole library, and "view original" already covers anyone who wants real
 * pixels.
 *
 * Thumbnails are cached in `filesDir`, **not** `cacheDir`. The OS may clear `cacheDir` whenever it
 * wants space, and regenerating tens of thousands of thumbnails off a USB disk is hours of work --
 * far too expensive to let the system throw away silently. Eviction is managed here instead.
 *
 * Files are named by content hash, so identical bytes share one thumbnail, a file that moves keeps
 * its thumbnail, and the cache can never go stale relative to its source.
 */
class ThumbnailGenerator(
    context: Context,
    private val store: LibraryStore,
) {
    /** Exposed so [ThumbnailCache] measures and sweeps the same directory this writes to. */
    val root: File = File(context.filesDir, "thumbs").apply { mkdirs() }

    data class Rendered(val relPath: String, val bytes: Long, val width: Int, val height: Int)

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

        val bitmap = if (isVideo) {
            videoFrame(relPath, target, durationMs)
        } else {
            imageBitmap(relPath, sizeClass, target)
        } ?: throw IllegalStateException("decoder produced no bitmap")

        try {
            val oriented = if (orientation != 0) rotate(bitmap, orientation) else bitmap
            try {
                val scaled = scale(oriented, sizeClass, target)
                try {
                    val out = cacheFile(contentHash, sizeClass)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    return Rendered(relPathFor(contentHash, sizeClass), out.length(), scaled.width, scaled.height)
                } finally {
                    if (scaled !== oriented) scaled.recycle()
                }
            } finally {
                if (oriented !== bitmap) oriented.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Two-pass decode. The first pass reads only the header for dimensions; the second decodes at
     * `inSampleSize`, so the decoder skips most of the file instead of materialising a full-size
     * bitmap. On a 50 MP photo that is the difference between a few milliseconds and an OOM.
     *
     * The descriptor must be rewound between passes -- `decodeFileDescriptor` consumes it.
     */
    private fun imageBitmap(relPath: String, sizeClass: Int, target: Int): Bitmap? =
        store.openRead(relPath).use { pfd ->
            val fd = pfd.fileDescriptor
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null

            val relevant = if (sizeClass == ThumbSize.GRID) {
                minOf(bounds.outWidth, bounds.outHeight)
            } else {
                maxOf(bounds.outWidth, bounds.outHeight)
            }
            var sample = 1
            while (relevant / (sample * 2) >= target) sample *= 2

            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFileDescriptor(fd, null, opts)
        }

    private fun videoFrame(relPath: String, target: Int, durationMs: Long?): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            store.openRead(relPath).use { pfd ->
                Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                mmr.setDataSource(pfd.fileDescriptor)
                // 10% in, capped at one second: frame zero is often a black fade-in.
                val atUs = minOf(1_000_000L, ((durationMs ?: 0L) * 100))
                mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target)
                    ?: mmr.frameAtTime
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scale(src: Bitmap, sizeClass: Int, target: Int): Bitmap {
        val w = src.width
        val h = src.height
        val current = if (sizeClass == ThumbSize.GRID) minOf(w, h) else maxOf(w, h)
        if (current <= target) return src
        val ratio = target.toFloat() / current
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, Math.round(w * ratio)),
            maxOf(1, Math.round(h * ratio)),
            true,
        )
    }

    companion object {
        /** 256px short edge: retina-adequate for a 128px grid cell, roughly 18 KB. */
        const val GRID_SHORT_EDGE = 256
        /** 1440px long edge: good enough fullscreen on a laptop, roughly 250 KB. */
        const val PREVIEW_LONG_EDGE = 1440
    }
}
