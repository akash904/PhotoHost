package dev.gpicalter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video, identified by the SHA-256 of its bytes.
 *
 * Content hash as identity is the decision everything else leans on: the same image uploaded from
 * two phones is one asset, re-uploading is free, a file that moves on disk is still the same asset,
 * and a library can be rebuilt from the bytes alone after a total loss of this database.
 *
 * Capture time is stored as UTC millis **plus** the original offset **plus** where it came from.
 * EXIF `DateTimeOriginal` carries no timezone, so without that provenance a timeline silently
 * scrambles itself and there is no way to tell good timestamps from guesses after the fact.
 */
@Entity(
    tableName = "assets",
    indices = [
        Index(value = ["content_hash"], unique = true),
        // The timeline index. Keyset pagination reads this and nothing else.
        Index(value = ["captured_at", "id"]),
        Index(value = ["deleted_at"]),
        // Cheap rescan gate: decides "probably already known" from 64 KiB instead of the whole file.
        Index(value = ["head_hash", "byte_size"]),
        Index(value = ["favorite"]),
        Index(value = ["source_album"]),
    ],
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "head_hash") val headHash: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,

    val mime: String,
    /** 0 image, 1 video, 2 motion photo, 3 raw. */
    @ColumnInfo(name = "media_type") val mediaType: Int,

    val width: Int? = null,
    val height: Int? = null,
    /** Degrees, already normalised so clients never rotate anything themselves. */
    val orientation: Int = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,

    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    /** [CaptureSource]. Surfaced in the UI so low-confidence dates are visible, not hidden. */
    @ColumnInfo(name = "captured_at_source") val capturedAtSource: Int,
    @ColumnInfo(name = "tz_offset_minutes") val tzOffsetMinutes: Int? = null,

    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "camera_make") val cameraMake: String? = null,
    @ColumnInfo(name = "camera_model") val cameraModel: String? = null,

    /** Tiny inline gradient so the grid paints instantly with no layout shift or second request. */
    val blurhash: String? = null,
    @ColumnInfo(name = "dominant_color") val dominantColor: Int? = null,

    val favorite: Boolean = false,

    /**
     * The folder this came from on the device that sent it -- "Camera", "WhatsApp Images",
     * "Screenshots".
     *
     * Recorded at upload because it is the client's knowledge and cannot be recovered afterwards:
     * once the bytes are stored, nothing about them says where they came from. It is what makes
     * "remove everything WhatsApp ever sent" expressible at all.
     */
    @ColumnInfo(name = "source_album") val sourceAlbum: String? = null,

    /** Reserved: links RAW+JPEG pairs and burst groups without needing a migration later. */
    @ColumnInfo(name = "capture_group_id") val captureGroupId: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Trash. Set rather than deleting, so a purge job can honour a retention window. */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

object MediaType {
    const val IMAGE = 0
    const val VIDEO = 1
    const val MOTION_PHOTO = 2
    const val RAW = 3
}

/**
 * Where `captured_at` came from, best first. Order matters: a date is only ever replaced by one
 * from a strictly better source, so this list encodes what "better" means.
 *
 * FILENAME outranks MTIME deliberately. A name like `IMG-20260901-WA0000` carries a date the
 * capturing app chose to write, whereas an mtime is an accident of however the file was last
 * copied, downloaded or restored -- and for anything that arrived over WhatsApp or a browser, the
 * mtime is simply when it landed on the device.
 */
object CaptureSource {
    const val EXIF_WITH_OFFSET = 0
    const val EXIF_NAIVE = 1
    const val MEDIASTORE = 2
    const val FILENAME = 3
    const val MTIME = 4
    const val UNKNOWN = 5

    /** Anything at or below this was recorded by something that actually knew the date. */
    const val TRUSTWORTHY = MEDIASTORE
}

/**
 * Where an asset's bytes actually live. One asset can have several: the canonical copy, a mirror on
 * a second volume, a sidecar.
 *
 * `(volume_id, rel_path)` is the durable key and `last_document_id` is only ever a cache. That
 * ordering is the whole reason a USB drive can be unplugged, replugged, or re-granted with a brand
 * new tree URI without the library losing track of a single file.
 */
@Entity(
    tableName = "asset_files",
    indices = [
        Index(value = ["volume_id", "rel_path"], unique = true),
        Index(value = ["asset_id"]),
        Index(value = ["missing_since"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AssetFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "asset_id") val assetId: Long,
    @ColumnInfo(name = "volume_id") val volumeId: Long,

    /** e.g. "2026/09/IMG_0001.jpg", relative to the store root. */
    @ColumnInfo(name = "rel_path") val relPath: String,
    @ColumnInfo(name = "display_name") val displayName: String,

    /** SAF fast path. May go stale at any moment; never treated as identity. */
    @ColumnInfo(name = "last_document_id") val lastDocumentId: String? = null,

    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    /** Compared with a +/-2s tolerance: exFAT timestamps have two-second granularity. */
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,

    /** [FileRole]. */
    val role: Int = FileRole.CANONICAL,
    /** Last time a full re-hash confirmed these bytes. Drives the bit-rot sweep. */
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,
    /** Set, never deleted: the asset and its hash outlive a file we temporarily cannot resolve. */
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
)

object FileRole {
    const val CANONICAL = 0
    const val SOURCE = 1
    const val MIRROR = 2
    const val SIDECAR = 3
}

/**
 * The no-rehash gate.
 *
 * A rescan of an unchanged source must be effectively free, or it will not get run often enough to
 * be useful. A row here matching on size, mtime and the MediaStore generation token means skip
 * without ever opening the file, turning a 20,000-photo rescan into one cursor plus index lookups.
 */
@Entity(
    tableName = "source_fingerprints",
    indices = [
        Index(value = ["source_kind", "source_key"], unique = true),
        Index(value = ["content_hash"]),
    ],
)
data class SourceFingerprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [SourceKind]. */
    @ColumnInfo(name = "source_kind") val sourceKind: Int,
    /** "ms:12345" for MediaStore, "vol:3:2026/09/IMG_0001.jpg" for a store path. */
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    /** MediaStore GENERATION_MODIFIED: an authoritative change token when available. */
    val generation: Long? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "hashed_at") val hashedAt: Long,
)

object SourceKind {
    const val MEDIASTORE = 0
    const val STORE_PATH = 1
    const val UPLOAD = 2

    /**
     * "import:<absolute path>" for a file copied in from a folder on this PC. DIVERGES FROM
     * gpicAlter (a new value only; the column and schema are unchanged): it is what makes importing
     * the same folder a second time nearly free.
     */
    const val IMPORT_PATH = 3
}
