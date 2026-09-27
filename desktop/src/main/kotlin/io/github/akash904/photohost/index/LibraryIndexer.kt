package io.github.akash904.photohost.index

import io.github.akash904.photohost.core.Log
import io.github.akash904.photohost.core.DualHash
import io.github.akash904.photohost.core.dualHash
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.data.entity.AssetEntity
import io.github.akash904.photohost.data.entity.AssetFileEntity
import io.github.akash904.photohost.data.entity.CaptureSource
import io.github.akash904.photohost.data.entity.FileRole
import io.github.akash904.photohost.data.entity.JobEntity
import io.github.akash904.photohost.data.entity.JobType
import io.github.akash904.photohost.data.entity.SourceFingerprintEntity
import io.github.akash904.photohost.data.entity.SourceKind
import io.github.akash904.photohost.data.entity.ThumbSize
import io.github.akash904.photohost.data.entity.ThumbState
import io.github.akash904.photohost.media.MediaProbe
import io.github.akash904.photohost.storage.LibraryStore
import io.github.akash904.photohost.storage.StoreEntry

private const val TAG = "gpic"

/** Timestamp comparisons allow for exFAT's two-second granularity. */
private const val MTIME_TOLERANCE_MS = 2_000L

sealed interface IndexResult {
    /** Fingerprint matched: the file was not even opened. */
    data object Skipped : IndexResult
    data class Indexed(val assetId: Long) : IndexResult
    /** These bytes already exist as an asset; this path was linked to it. */
    data class Duplicate(val assetId: Long) : IndexResult
    data class Failed(val reason: String) : IndexResult
}

/**
 * Turns a file on a store into a row in the library.
 *
 * The sequence matters. Hash first, and only then decide whether any further work is needed: if
 * those bytes are already known, metadata extraction, decoding and thumbnailing are all skipped and
 * the existing asset simply gains another path. That is what makes an "upload everything
 * continuously" client cheap to point at this server -- re-sending a photo costs one read and one
 * index lookup, not a re-import.
 */
class LibraryIndexer(
    private val db: AppDatabase,
    private val store: LibraryStore,
    private val volumeId: Long,
    // DIVERGES FROM the phone app, which takes a MetadataExtractor and decodes the blurhash itself with
    // BitmapFactory. Behind this seam the indexer has no platform code left in it.
    private val probe: MediaProbe,
) {

    /**
     * @param capturedAtHint a capture time the *source* knew but the stored bytes do not carry --
     *   MediaStore's DATE_TAKEN, say. Used only when extraction produced something weak, because a
     *   freshly written file's mtime is the moment it was written, which would silently re-date
     *   every uploaded screenshot to "now".
     * @param knownHash the hash of exactly these bytes, when the caller has just computed it by
     *   reading the stored file back. DIVERGES FROM the phone app: the folder importer verifies every
     *   copy that way, and hashing it again here would be a third full read of each file -- a real
     *   cost across a multi-terabyte archive. Never pass a hash of anything but the stored file.
     */
    suspend fun index(
        entry: StoreEntry,
        force: Boolean = false,
        capturedAtHint: Long? = null,
        sourceAlbum: String? = null,
        knownHash: DualHash? = null,
    ): IndexResult {
        val sourceKey = "vol:$volumeId:${entry.relPath}"

        if (!force) {
            val fp = db.fingerprints().find(SourceKind.STORE_PATH, sourceKey)
            if (fp != null &&
                fp.sizeBytes == entry.size &&
                kotlin.math.abs(fp.modifiedAt - entry.lastModified) <= MTIME_TOLERANCE_MS
            ) {
                return IndexResult.Skipped
            }
        }

        val hash = knownHash ?: try {
            store.openRead(entry.relPath).use { it.inputStream().dualHash() }
        } catch (t: Throwable) {
            return IndexResult.Failed("hash: ${t.javaClass.simpleName}: ${t.message}")
        }

        val now = System.currentTimeMillis()
        val existing = db.assets().byHash(hash.contentHash)

        val assetId: Long
        val isDuplicate: Boolean
        if (existing != null) {
            assetId = existing.id
            isDuplicate = true
        } else {
            val meta = try {
                probe.metadata(store, entry.relPath, entry.mime, entry.lastModified, entry.name)
            } catch (t: Throwable) {
                return IndexResult.Failed("metadata: ${t.javaClass.simpleName}: ${t.message}")
            }

            // Prefer the hint whenever the file itself yielded nothing better than its mtime.
            val weak = meta.capturedAtSource >= CaptureSource.MTIME
            val capturedAt = if (weak && capturedAtHint != null) capturedAtHint else meta.capturedAt
            val capturedSource =
                if (weak && capturedAtHint != null) CaptureSource.MEDIASTORE else meta.capturedAtSource

            val blurhash = runCatching { probe.blurhash(store, entry, meta.orientation) }.getOrNull()

            val inserted = db.assets().insertIgnore(
                AssetEntity(
                    contentHash = hash.contentHash,
                    headHash = hash.headHash,
                    byteSize = hash.bytes,
                    mime = entry.mime,
                    mediaType = meta.mediaType,
                    width = meta.width,
                    height = meta.height,
                    orientation = meta.orientation,
                    durationMs = meta.durationMs,
                    capturedAt = capturedAt,
                    capturedAtSource = capturedSource,
                    tzOffsetMinutes = meta.tzOffsetMinutes,
                    latitude = meta.latitude,
                    longitude = meta.longitude,
                    cameraMake = meta.cameraMake,
                    cameraModel = meta.cameraModel,
                    blurhash = blurhash,
                    sourceAlbum = sourceAlbum,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // -1 means the unique index on content_hash rejected it: another worker won the race.
            // The database is the arbiter of identity, so just adopt whatever is there now.
            assetId = if (inserted > 0) inserted else (db.assets().idByHash(hash.contentHash) ?: -1L)
            if (assetId <= 0) return IndexResult.Failed("insert lost the race and could not re-read")
            isDuplicate = inserted <= 0
        }

        db.assetFiles().upsert(
            AssetFileEntity(
                id = db.assetFiles().byPath(volumeId, entry.relPath)?.id ?: 0,
                assetId = assetId,
                volumeId = volumeId,
                relPath = entry.relPath,
                displayName = entry.name,
                sizeBytes = entry.size,
                modifiedAt = entry.lastModified,
                role = FileRole.CANONICAL,
                verifiedAt = now,
            ),
        )

        db.fingerprints().upsert(
            SourceFingerprintEntity(
                id = db.fingerprints().find(SourceKind.STORE_PATH, sourceKey)?.id ?: 0,
                sourceKind = SourceKind.STORE_PATH,
                sourceKey = sourceKey,
                sizeBytes = entry.size,
                modifiedAt = entry.lastModified,
                contentHash = hash.contentHash,
                hashedAt = now,
            ),
        )

        // Grid before PREVIEW: it is what the timeline needs, and PREVIEW can lag behind.
        //
        // Only queue what is actually missing. A duplicate -- the same bytes arriving at a second
        // path, which is the normal case for a backup client -- already has its thumbnails, and
        // re-decoding them would make re-uploads expensive precisely where they should be free.
        val wanted = listOf(ThumbSize.GRID, ThumbSize.PREVIEW).filter { size ->
            db.thumbnails().find(assetId, size)?.state != ThumbState.READY
        }
        if (wanted.isNotEmpty()) {
            db.jobs().enqueueAll(wanted.map { thumbJob(assetId, it, now) })
        }

        return if (isDuplicate) IndexResult.Duplicate(assetId) else IndexResult.Indexed(assetId)
    }

    private fun thumbJob(assetId: Long, sizeClass: Int, now: Long) = JobEntity(
        type = JobType.THUMBNAIL,
        payload = """{"assetId":$assetId,"sizeClass":$sizeClass}""",
        dedupeKey = "${JobType.THUMBNAIL}:$assetId:$sizeClass",
        priority = if (sizeClass == ThumbSize.GRID) 50 else 70,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * Walks a store and indexes everything on it.
 *
 * Resumability comes for free from the fingerprint gate rather than from any checkpointing: a scan
 * killed halfway through re-runs and skips what it already did, at the cost of one index lookup per
 * file. That is a far simpler guarantee than tracking progress, and it means a rescan is also the
 * repair mechanism after any inconsistency.
 */
class StoreScanner(
    private val store: LibraryStore,
    private val indexer: LibraryIndexer,
) {
    data class Progress(
        val scanned: Int = 0,
        val indexed: Int = 0,
        val duplicates: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    )

    suspend fun scan(onProgress: (Progress) -> Unit = {}): Progress {
        var p = Progress()
        val queue = ArrayDeque<String>()
        queue.add("")

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = try {
                store.list(dir)
            } catch (t: Throwable) {
                Log.w(TAG, "scan: cannot list '$dir': ${t.message}")
                continue
            }
            for (child in children) {
                if (child.isDirectory) {
                    // Our own scratch directories are not library content.
                    if (child.name.startsWith(".gpic") || child.name == "gpic-probe") continue
                    queue.addLast(child.relPath)
                    continue
                }
                if (!isMedia(child)) continue

                p = when (val r = indexer.index(child)) {
                    is IndexResult.Indexed -> p.copy(scanned = p.scanned + 1, indexed = p.indexed + 1)
                    is IndexResult.Duplicate -> p.copy(scanned = p.scanned + 1, duplicates = p.duplicates + 1)
                    IndexResult.Skipped -> p.copy(scanned = p.scanned + 1, skipped = p.skipped + 1)
                    is IndexResult.Failed -> {
                        Log.w(TAG, "scan: ${child.relPath}: ${r.reason}")
                        p.copy(scanned = p.scanned + 1, failed = p.failed + 1)
                    }
                }
                if (p.scanned % 25 == 0) onProgress(p)
            }
        }
        onProgress(p)
        return p
    }

    private fun isMedia(entry: StoreEntry): Boolean {
        if (entry.mime.startsWith("image/") || entry.mime.startsWith("video/")) return true
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    private companion object {
        val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp",
            "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw",
            "mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi",
        )
    }
}
