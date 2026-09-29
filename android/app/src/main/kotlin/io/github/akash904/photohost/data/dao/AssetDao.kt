package io.github.akash904.photohost.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import io.github.akash904.photohost.data.entity.AssetEntity
import io.github.akash904.photohost.data.entity.AssetFileEntity
import io.github.akash904.photohost.data.entity.SourceFingerprintEntity

/** Projection for the timeline. Deliberately terse: 200 of these must fit one HTTP response. */
data class TimelineRow(
    val id: Long,
    val contentHash: String,
    val mime: String,
    val mediaType: Int,
    val width: Int?,
    val height: Int?,
    val orientation: Int,
    val capturedAt: Long,
    val tzOffsetMinutes: Int?,
    val durationMs: Long?,
    val favorite: Boolean,
    val blurhash: String?,
)

/**
 * A month in the scrubber. [newestCapturedAt] is what lets a click actually jump there: the client
 * turns it into a keyset cursor, so seeking to 2019 is an index seek rather than paging through
 * everything in between.
 */
data class SourceAlbumRow(val album: String, val count: Int, val bytes: Long)

data class MonthBucket(val bucket: String, val count: Int, val newestCapturedAt: Long)

@Dao
interface AssetDao {

    /**
     * The timeline, paginated by keyset rather than OFFSET.
     *
     * OFFSET makes SQLite walk every skipped row, so page 200 costs 200x page 1 and deep scrolling
     * degrades visibly. Comparing against the last row's (captured_at, id) is served entirely by
     * INDEX(captured_at, id) and costs the same at any depth.
     *
     * The predicate is written out longhand instead of using a row-value comparison, because
     * row-value support varies across the SQLite builds shipped by different OEMs.
     */
    @Query(
        """
        SELECT id, content_hash AS contentHash, mime, media_type AS mediaType, width, height,
               orientation, captured_at AS capturedAt, tz_offset_minutes AS tzOffsetMinutes,
               duration_ms AS durationMs, favorite, blurhash
        FROM assets
        WHERE deleted_at IS NULL
          AND (:cursorCapturedAt IS NULL
               OR captured_at < :cursorCapturedAt
               OR (captured_at = :cursorCapturedAt AND id < :cursorId))
        ORDER BY captured_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun timeline(cursorCapturedAt: Long?, cursorId: Long, limit: Int): List<TimelineRow>

    /**
     * Counts per calendar month, for the drag scrubber. Grouped in the photo's *local* time, not
     * UTC, so a picture taken at 9pm does not land in the next month for anyone east of Greenwich.
     */
    @Query(
        """
        SELECT strftime('%Y-%m',
                 (captured_at + COALESCE(tz_offset_minutes, 0) * 60000) / 1000,
                 'unixepoch') AS bucket,
               COUNT(*) AS count,
               MAX(captured_at) AS newestCapturedAt
        FROM assets
        WHERE deleted_at IS NULL
        GROUP BY bucket
        ORDER BY bucket DESC
        """,
    )
    suspend fun monthBuckets(): List<MonthBucket>

    @Query(
        """
        SELECT source_album AS album, COUNT(*) AS count, COALESCE(SUM(byte_size), 0) AS bytes
        FROM assets
        WHERE deleted_at IS NULL AND source_album IS NOT NULL
        GROUP BY source_album
        ORDER BY count DESC
        """,
    )
    suspend fun sourceAlbums(): List<SourceAlbumRow>

    @Query("SELECT id FROM assets WHERE deleted_at IS NULL AND source_album = :album")
    suspend fun idsFromSource(album: String): List<Long>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun byId(id: Long): AssetEntity?

    @Query("SELECT * FROM assets WHERE content_hash = :hash LIMIT 1")
    suspend fun byHash(hash: String): AssetEntity?

    @Query("SELECT id FROM assets WHERE content_hash = :hash LIMIT 1")
    suspend fun idByHash(hash: String): Long?

    /** The 64 KiB pre-filter: narrows "have I seen these bytes" without reading the whole file. */
    @Query("SELECT * FROM assets WHERE head_hash = :headHash AND byte_size = :size")
    suspend fun byHeadHash(headHash: String, size: Long): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM assets WHERE deleted_at IS NULL")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM assets WHERE deleted_at IS NOT NULL")
    suspend fun trashCount(): Int

    @Query("SELECT COALESCE(SUM(byte_size), 0) FROM assets WHERE deleted_at IS NULL")
    suspend fun totalBytes(): Long

    /**
     * IGNORE rather than REPLACE: the unique index on content_hash is the deduplication mechanism,
     * so a concurrent or repeated insert of the same bytes must be a no-op, not an overwrite that
     * would discard an existing asset's albums and favourite state. Returns -1 when ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(asset: AssetEntity): Long

    @Update
    suspend fun update(asset: AssetEntity)

    @Query("UPDATE assets SET favorite = :favorite, updated_at = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("UPDATE assets SET favorite = :favorite, updated_at = :now WHERE id IN (:ids)")
    suspend fun setFavoriteAll(ids: List<Long>, favorite: Boolean, now: Long)

    /**
     * Permanent removal. Cascades to asset_files and thumbnails through the foreign keys, so the
     * only things left to clean up by hand are the bytes on the store and the thumbnail cache.
     */
    @Query("DELETE FROM assets WHERE id IN (:ids)")
    suspend fun purge(ids: List<Long>)

    @Query("SELECT * FROM assets WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT :limit")
    suspend fun trashed(limit: Int): List<AssetEntity>

    @Query("UPDATE assets SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun trash(ids: List<Long>, now: Long)

    @Query("UPDATE assets SET deleted_at = NULL, updated_at = :now WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>, now: Long)

    /**
     * Upgrades a capture date, and only ever upgrades.
     *
     * The guard on `captured_at_source` is the whole point: sources are ordered best-first, so this
     * replaces a date derived from a file's mtime with one the camera or MediaStore actually
     * recorded, and can never do the reverse. That makes re-running a backup a repair operation
     * rather than a risk.
     */
    @Query(
        """
        UPDATE assets
        SET captured_at = :capturedAt,
            captured_at_source = :source,
            tz_offset_minutes = COALESCE(:tzOffsetMinutes, tz_offset_minutes),
            updated_at = :now
        WHERE id = :id AND captured_at_source > :source
        """,
    )
    suspend fun upgradeCapturedAt(
        id: Long,
        capturedAt: Long,
        source: Int,
        tzOffsetMinutes: Int?,
        now: Long,
    ): Int

    @Query("UPDATE assets SET blurhash = :blurhash, updated_at = :now WHERE id = :id")
    suspend fun setBlurhash(id: Long, blurhash: String?, now: Long)

    /** Assets whose date came from something that did not actually know it. */
    @Query(
        """
        SELECT * FROM assets
        WHERE deleted_at IS NULL AND captured_at_source > :maxSource
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun withWeakDates(maxSource: Int, limit: Int): List<AssetEntity>

    /** Backfill order: newest first, because that is what anyone looks at first. */
    @Query(
        """
        SELECT a.id FROM assets a
        LEFT JOIN thumbnails t ON t.asset_id = a.id AND t.size_class = :sizeClass
        WHERE a.deleted_at IS NULL AND (t.state IS NULL OR t.state IN (0, 5))
        ORDER BY a.captured_at DESC
        LIMIT :limit
        """,
    )
    suspend fun needingThumbnails(sizeClass: Int, limit: Int): List<Long>
}

@Dao
interface AssetFileDao {

    @Upsert
    suspend fun upsert(file: AssetFileEntity): Long

    @Query("SELECT * FROM asset_files WHERE asset_id = :assetId")
    suspend fun forAsset(assetId: Long): List<AssetFileEntity>

    /** The canonical copy: what byte-serving routes resolve and stream. */
    @Query("SELECT * FROM asset_files WHERE asset_id = :assetId AND role = 0 AND missing_since IS NULL LIMIT 1")
    suspend fun canonical(assetId: Long): AssetFileEntity?

    @Query("SELECT * FROM asset_files WHERE volume_id = :volumeId AND rel_path = :relPath LIMIT 1")
    suspend fun byPath(volumeId: Long, relPath: String): AssetFileEntity?

    @Query("UPDATE asset_files SET last_document_id = :docId WHERE id = :id")
    suspend fun cacheDocumentId(id: Long, docId: String?)

    @Query("UPDATE asset_files SET missing_since = :now WHERE id = :id")
    suspend fun markMissing(id: Long, now: Long)

    @Query("UPDATE asset_files SET missing_since = NULL, verified_at = :now WHERE id = :id")
    suspend fun markPresent(id: Long, now: Long)

    @Query("SELECT COUNT(*) FROM asset_files WHERE missing_since IS NOT NULL")
    suspend fun missingCount(): Int

    @Query("SELECT * FROM asset_files WHERE volume_id = :volumeId ORDER BY id")
    suspend fun onVolume(volumeId: Long): List<AssetFileEntity>

    @Query("SELECT COUNT(*) FROM asset_files")
    suspend fun count(): Int

    @Query("DELETE FROM asset_files WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FingerprintDao {

    @Query("SELECT * FROM source_fingerprints WHERE source_kind = :kind AND source_key = :key LIMIT 1")
    suspend fun find(kind: Int, key: String): SourceFingerprintEntity?

    @Upsert
    suspend fun upsert(fingerprint: SourceFingerprintEntity)

    @Query("DELETE FROM source_fingerprints WHERE source_kind = :kind AND source_key = :key")
    suspend fun forget(kind: Int, key: String)

    /** Forgetting the fingerprint lets a rescan re-import the file if it ever reappears. */
    @Query("DELETE FROM source_fingerprints WHERE content_hash = :hash")
    suspend fun forgetByHash(hash: String)

    @Query("SELECT COUNT(*) FROM source_fingerprints")
    suspend fun count(): Int
}
