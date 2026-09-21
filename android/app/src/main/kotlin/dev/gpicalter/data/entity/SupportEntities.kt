package dev.gpicalter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A storage backend the library has used. Internal storage is one; each USB drive is another. */
@Entity(tableName = "volumes", indices = [Index(value = ["volume_key"], unique = true)])
data class VolumeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "internal", or the exFAT serial such as "1234-5678". Stable across replug. */
    @ColumnInfo(name = "volume_key") val volumeKey: String,
    /** INTERNAL or SAF. */
    val kind: String,
    val label: String,
    /** Last persisted SAF tree URI. A cache, not identity -- see AssetFileEntity. */
    @ColumnInfo(name = "tree_uri") val treeUri: String? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "free_bytes") val freeBytes: Long? = null,
    val mounted: Boolean = false,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

/**
 * Two size classes only. Every extra size multiplies backfill hours across a whole library, and a
 * "view original" path already exists for anyone who wants real pixels.
 */
@Entity(
    tableName = "thumbnails",
    primaryKeys = ["asset_id", "size_class"],
    indices = [Index(value = ["state"]), Index(value = ["last_access_at"])],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ThumbnailEntity(
    @ColumnInfo(name = "asset_id") val assetId: Long,
    /** [ThumbSize]. */
    @ColumnInfo(name = "size_class") val sizeClass: Int,
    /** [ThumbState]. */
    val state: Int,
    /** Named by content hash, so dedupe is free and the cache can never go stale. */
    @ColumnInfo(name = "cache_rel_path") val cacheRelPath: String? = null,
    @ColumnInfo(name = "byte_size") val byteSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "generated_at") val generatedAt: Long? = null,
    /** Written back throttled, so serving thumbnails does not hammer the database. */
    @ColumnInfo(name = "last_access_at") val lastAccessAt: Long? = null,
)

object ThumbSize {
    /** 256px short edge, ~18 KB. Retina-adequate for a grid cell. */
    const val GRID = 0
    /** 1440px long edge, ~250 KB. Good enough fullscreen on a laptop. */
    const val PREVIEW = 1
}

object ThumbState {
    const val ABSENT = 0
    const val QUEUED = 1
    const val RUNNING = 2
    const val READY = 3
    const val FAILED_PERMANENT = 4
    const val FAILED_RETRY = 5
}

@Entity(tableName = "albums", indices = [Index(value = ["share_slug"], unique = true)])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "cover_asset_id") val coverAssetId: Long? = null,
    @ColumnInfo(name = "is_smart") val isSmart: Boolean = false,
    @ColumnInfo(name = "smart_query") val smartQuery: String? = null,
    @ColumnInfo(name = "share_slug") val shareSlug: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "album_assets",
    primaryKeys = ["album_id", "asset_id"],
    indices = [Index(value = ["asset_id"]), Index(value = ["album_id", "position"])],
    foreignKeys = [
        ForeignKey(AlbumEntity::class, ["id"], ["album_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AssetEntity::class, ["id"], ["asset_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class AlbumAssetEntity(
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "asset_id") val assetId: Long,
    /** Sparse doubles, so reordering one item never renumbers the whole album. */
    val position: Double,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * The durable work queue. Bulk work is thousands of items long and must survive the process being
 * killed mid-run, so the queue is a table rather than an in-memory channel. Unbounded queueing is
 * fine precisely because it is on disk.
 */
@Entity(
    tableName = "jobs",
    indices = [
        // The claim index: exactly the columns the claim query filters and orders by.
        Index(value = ["state", "priority", "not_before", "id"]),
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["type"]),
    ],
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    /** JSON payload, interpreted by the handler for [type]. */
    val payload: String,
    /** e.g. "THUMBNAIL:1234:0". A unique index makes enqueue naturally idempotent. */
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String? = null,
    /** [JobState]. */
    val state: Int = JobState.PENDING,
    /** 0 interactive, 10 user-visible, 50 bulk backfill, 90 housekeeping. */
    val priority: Int = 50,
    val attempts: Int = 0,
    @ColumnInfo(name = "max_attempts") val maxAttempts: Int = 5,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String? = null,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long? = null,
    /** Exponential backoff. */
    @ColumnInfo(name = "not_before") val notBefore: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

object JobState {
    const val PENDING = 0
    const val LEASED = 1
    const val DONE = 2
    const val FAILED = 3
    const val CANCELLED = 4
    /** Volume gone. Distinct from FAILED so a replug resumes instead of burning retries. */
    const val BLOCKED = 5
}

object JobType {
    const val HASH_FILE = "HASH_FILE"
    const val EXTRACT_METADATA = "EXTRACT_METADATA"
    const val THUMBNAIL = "THUMBNAIL"
    const val SCAN_VOLUME = "SCAN_VOLUME"
    const val IMPORT_ASSET = "IMPORT_ASSET"
    const val VERIFY_ASSET = "VERIFY_ASSET"
    const val RECONCILE_VOLUME = "RECONCILE_VOLUME"
    const val EVICT_CACHE = "EVICT_CACHE"
    const val PURGE_TRASH = "PURGE_TRASH"
}

/** A long import. Progress lives in rows, not in a coroutine, which is what makes it resumable. */
@Entity(tableName = "import_sessions")
data class ImportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [ImportSource]. */
    val source: Int,
    @ColumnInfo(name = "target_volume_id") val targetVolumeId: Long,
    /** [ImportState]. */
    val state: Int,
    @ColumnInfo(name = "total_items") val totalItems: Int = 0,
    @ColumnInfo(name = "done_items") val doneItems: Int = 0,
    @ColumnInfo(name = "failed_items") val failedItems: Int = 0,
    @ColumnInfo(name = "skipped_items") val skippedItems: Int = 0,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long = 0,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

object ImportSource {
    const val DCIM = 0
    const val HTTP_UPLOAD = 1
    const val STORE_FOLDER = 2
}

object ImportState {
    const val DISCOVERING = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
    const val CANCELLED = 5
}

/**
 * One item in an import, as a stage machine. Every stage is idempotent, so resuming is just
 * re-entering the handler for whatever stage the row is parked at.
 *
 * A truncated write can never be committed: bytes are written to a temp name in the destination
 * directory, re-read and re-hashed, and only then renamed into place.
 */
@Entity(
    tableName = "import_items",
    indices = [
        Index(value = ["session_id", "source_key"], unique = true),
        Index(value = ["session_id", "stage"]),
        Index(value = ["content_hash"]),
    ],
    foreignKeys = [
        ForeignKey(ImportSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** "ms:12345" or "upload:<uuid>". */
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    /** [ImportStage]. */
    val stage: Int,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    /** ".gpic-tmp/<uuid>.part", inside the destination directory so the rename is atomic. */
    @ColumnInfo(name = "temp_rel_path") val tempRelPath: String? = null,
    @ColumnInfo(name = "final_rel_path") val finalRelPath: String? = null,
    @ColumnInfo(name = "asset_id") val assetId: Long? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

object ImportStage {
    const val DISCOVERED = 0
    const val PREFILTER_SKIPPED = 1
    const val COPYING = 2
    const val COPIED = 3
    const val VERIFIED = 4
    const val COMMITTED = 5
    const val INDEXED = 6
    const val DONE = 7
    const val FAILED = 8
    const val DUPLICATE = 9

    /** Stages that need no further work. Resume selects everything else. */
    val TERMINAL = intArrayOf(PREFILTER_SKIPPED, DONE, FAILED, DUPLICATE)
}

/** Per-device access token. Only the SHA-256 is kept, so a leak of this table reveals nothing. */
@Entity(tableName = "auth_tokens", indices = [Index(value = ["token_hash"], unique = true)])
data class AuthTokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "token_hash") val tokenHash: String,
    /** e.g. "Pixel 9 . 192.168.1.40". Shown in the UI so a device can be recognised and revoked. */
    val label: String,
    val scopes: String = "read,write",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long? = null,
)
