package io.github.akash904.photohost.media

import io.github.akash904.photohost.core.Log
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.data.entity.ThumbSize
import java.io.File

private const val TAG = "gpic"

/**
 * Keeps the thumbnail cache inside a budget.
 *
 * [ThumbnailGenerator] deliberately writes to `filesDir` rather than `cacheDir`, so the OS will
 * never reclaim this space on our behalf -- regenerating tens of thousands of thumbnails off a USB
 * disk is hours of work, far too expensive to let the system throw away silently. The price of that
 * choice is that the cache grows without limit unless something here spends the budget. This is
 * that something.
 *
 * ### The budget is a fraction of the space the cache *could* have
 *
 * Not a fraction of free space. Free space shrinks as the cache grows, so a budget keyed to it
 * chases its own tail: every thumbnail written lowers the ceiling, which evicts a thumbnail, which
 * raises the ceiling again. Keying it to `free + whatever the cache already occupies` gives a
 * number that does not move when bytes cross the boundary, which is the only version that settles.
 *
 * It is measured against **`filesDir`** (the data directory on a PC), not the library store. Originals may live on a USB volume;
 * thumbnails never do. Sizing this against the drive would be sizing it against the wrong disk.
 *
 * ### Eviction order
 *
 * PREVIEW before GRID, least recently touched first. PREVIEW is ~250 KB against GRID's ~18 KB, so
 * it is where the bytes actually are, and losing one costs a single fullscreen re-render whereas
 * losing GRID entries pocks the timeline everyone scrolls.
 */
class ThumbnailCache(
    private val db: AppDatabase,
    private val generator: ThumbnailGenerator,
) {
    private val root: File get() = generator.root

    data class Budget(
        /** Sweep above this. */
        val limitBytes: Long,
        /** Sweep down to this, so writing one thumbnail does not immediately trigger the next sweep. */
        val lowWaterBytes: Long,
        val freeBytes: Long,
    )

    data class Sweep(
        val beforeBytes: Long,
        val afterBytes: Long,
        val limitBytes: Long,
        val evicted: Int,
        val orphansReaped: Int,
        /** Rows dropped whose bytes stayed, because another asset's row still points at that file. */
        val sharedKept: Int,
    ) {
        val freedBytes: Long get() = beforeBytes - afterBytes
    }

    /** What the cache physically occupies, orphans included. The truth, as opposed to what rows claim. */
    fun diskBytes(): Long = generator.totalBytes()

    fun budget(currentBytes: Long): Budget {
        val free = try {
            // StatFs.availableBytes on the phone. Zero also means "no answer" on the JVM.
            root.usableSpace.takeIf { it > 0 } ?: error("usableSpace reported 0")
        } catch (t: Throwable) {
            // No answer from the filesystem is not a reason to evict the whole cache.
            Log.w(TAG, "free-space query failed on ${root.absolutePath}", t)
            return Budget(CEILING_BYTES, (CEILING_BYTES * LOW_WATER).toLong(), -1L)
        }
        val headroom = free + currentBytes
        val limit = (headroom * FRACTION_OF_FREE).toLong()
            .coerceIn(FLOOR_BYTES, CEILING_BYTES)
            // A floor above what the disk actually holds would be a promise the disk cannot keep.
            .coerceAtMost(headroom)
        return Budget(limit, (limit * LOW_WATER).toLong(), free)
    }

    /**
     * Reaps orphans, then evicts LRU until under the low-water mark.
     *
     * Orphans go first because they are free: nothing references them, so nothing has to be
     * regenerated later. Only if that is not enough do we start spending thumbnails people may
     * still want.
     */
    suspend fun sweep(now: Long = System.currentTimeMillis()): Sweep {
        val orphans = reapOrphans(now)
        val before = diskBytes()
        val budget = budget(before)

        if (before <= budget.limitBytes) {
            return Sweep(before, before, budget.limitBytes, 0, orphans, 0)
        }

        var bytes = before
        var evicted = 0
        var sharedKept = 0

        for (sizeClass in intArrayOf(ThumbSize.PREVIEW, ThumbSize.GRID)) {
            while (bytes > budget.lowWaterBytes) {
                val batch = db.thumbnails().evictionCandidates(sizeClass, BATCH)
                // The query re-runs against a table we are deleting from, so the LRU head advances
                // every pass and an empty batch is the only termination condition needed -- even if
                // a whole batch turns out to be shared and frees nothing.
                if (batch.isEmpty()) break

                for (row in batch) {
                    if (bytes <= budget.lowWaterBytes) break
                    val rel = row.cacheRelPath ?: continue

                    // Thumbnails are named by content hash, so two assets with identical bytes share
                    // one file. Deleting it because *this* row is cold would blank a thumbnail some
                    // other asset's row still claims is READY.
                    val sharers = db.thumbnails().sharersOf(rel, row.assetId)
                    db.thumbnails().delete(row.assetId, row.sizeClass)
                    if (sharers > 0) {
                        sharedKept++
                        continue
                    }
                    val file = File(root, rel)
                    val len = file.length()
                    if (file.delete()) {
                        bytes -= len
                        evicted++
                    }
                }
            }
            if (bytes <= budget.lowWaterBytes) break
        }

        Log.i(
            TAG,
            "thumb sweep: ${before / MIB} -> ${bytes / MIB} MiB (limit ${budget.limitBytes / MIB} MiB), " +
                "evicted=$evicted orphans=$orphans shared=$sharedKept",
        )
        return Sweep(before, bytes, budget.limitBytes, evicted, orphans, sharedKept)
    }

    /**
     * Deletes cache files no READY row points at.
     *
     * They accumulate from the gap between writing the JPEG and committing the row: a process death
     * in between leaves bytes nobody will ever ask for, and nothing else would ever notice them.
     * Files younger than the grace period are left alone, because a render in flight right now looks
     * exactly like an orphan.
     */
    private suspend fun reapOrphans(now: Long): Int {
        if (!root.isDirectory) return 0
        val known = db.thumbnails().readyCachePaths().toHashSet()
        var reaped = 0
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (rel in known) return@forEach
            if (now - file.lastModified() < ORPHAN_GRACE_MS) return@forEach
            if (file.delete()) reaped++
        }
        return reaped
    }

    private companion object {
        /** Share of the space the cache could occupy that it is allowed to occupy. */
        const val FRACTION_OF_FREE = 0.10

        /**
         * Below this a cache is not worth managing: a few thousand GRID thumbnails at ~18 KB fit
         * inside it, and evicting them only to regenerate them costs more than the space is worth.
         */
        const val FLOOR_BYTES = 128L * 1024 * 1024

        /** ~16k previews or ~230k grid thumbnails. Past this, more cache buys nothing noticeable. */
        const val CEILING_BYTES = 4L * 1024 * 1024 * 1024

        /** Evict to 90% of the limit, so the next thumbnail written does not trigger another sweep. */
        const val LOW_WATER = 0.90

        const val BATCH = 256

        /** A render in flight looks like an orphan; ten minutes is far longer than one can take. */
        const val ORPHAN_GRACE_MS = 10 * 60_000L

        const val MIB = 1024 * 1024
    }
}
