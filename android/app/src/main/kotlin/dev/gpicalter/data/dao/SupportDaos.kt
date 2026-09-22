package dev.gpicalter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.gpicalter.data.entity.AlbumAssetEntity
import dev.gpicalter.data.entity.AlbumEntity
import dev.gpicalter.data.entity.AuthTokenEntity
import dev.gpicalter.data.entity.ImportItemEntity
import dev.gpicalter.data.entity.ImportSessionEntity
import dev.gpicalter.data.entity.JobEntity
import dev.gpicalter.data.entity.JobState
import dev.gpicalter.data.entity.ThumbnailEntity
import dev.gpicalter.data.entity.VolumeEntity

@Dao
interface VolumeDao {
    @Upsert
    suspend fun upsert(volume: VolumeEntity): Long

    @Query("SELECT * FROM volumes WHERE volume_key = :key LIMIT 1")
    suspend fun byKey(key: String): VolumeEntity?

    @Query("SELECT * FROM volumes")
    suspend fun all(): List<VolumeEntity>

    @Query("UPDATE volumes SET mounted = :mounted, free_bytes = :free, total_bytes = :total, last_seen_at = :now WHERE id = :id")
    suspend fun updateState(id: Long, mounted: Boolean, free: Long?, total: Long?, now: Long)
}

@Dao
interface ThumbnailDao {
    @Upsert
    suspend fun upsert(thumb: ThumbnailEntity)

    @Query("SELECT * FROM thumbnails WHERE asset_id = :assetId AND size_class = :sizeClass")
    suspend fun find(assetId: Long, sizeClass: Int): ThumbnailEntity?

    @Query("SELECT COALESCE(SUM(byte_size), 0) FROM thumbnails WHERE state = 3")
    suspend fun cachedBytes(): Long

    @Query("SELECT COUNT(*) FROM thumbnails WHERE state = 3 AND size_class = :sizeClass")
    suspend fun readyCount(sizeClass: Int): Int

    /** Eviction order: PREVIEW before GRID, least recently touched first. */
    @Query(
        """
        SELECT * FROM thumbnails
        WHERE state = 3 AND size_class = :sizeClass
        ORDER BY COALESCE(last_access_at, generated_at, 0) ASC
        LIMIT :limit
        """,
    )
    suspend fun evictionCandidates(sizeClass: Int, limit: Int): List<ThumbnailEntity>

    /** Throttled by the caller: serving a thumbnail must not mean a database write every time. */
    @Query("UPDATE thumbnails SET last_access_at = :now WHERE asset_id = :assetId AND size_class = :sizeClass")
    suspend fun touch(assetId: Long, sizeClass: Int, now: Long)

    /**
     * How many *other* ready rows point at the same cache file.
     *
     * Thumbnails are named by content hash, so identical originals share one file. Eviction must ask
     * this before deleting bytes, or dropping one cold asset blanks every duplicate of it.
     */
    @Query("SELECT COUNT(*) FROM thumbnails WHERE cache_rel_path = :relPath AND state = 3 AND asset_id != :assetId")
    suspend fun sharersOf(relPath: String, assetId: Long): Int

    /** Every cache file still spoken for, for finding the ones that are not. */
    @Query("SELECT cache_rel_path FROM thumbnails WHERE state = 3 AND cache_rel_path IS NOT NULL")
    suspend fun readyCachePaths(): List<String>

    @Query("DELETE FROM thumbnails WHERE asset_id = :assetId AND size_class = :sizeClass")
    suspend fun delete(assetId: Long, sizeClass: Int)
}

@Dao
interface AlbumDao {
    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Upsert
    suspend fun upsert(album: AlbumEntity)

    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    suspend fun all(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM album_assets WHERE album_id = :albumId")
    suspend fun size(albumId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(rows: List<AlbumAssetEntity>)

    @Query("DELETE FROM album_assets WHERE album_id = :albumId AND asset_id IN (:assetIds)")
    suspend fun removeAll(albumId: Long, assetIds: List<Long>)

    @Query("SELECT COALESCE(MAX(position), 0.0) FROM album_assets WHERE album_id = :albumId")
    suspend fun maxPosition(albumId: Long): Double

    /** Album contents, keyset-paginated on the same index as the main timeline. */
    @Query(
        """
        SELECT a.id, a.content_hash AS contentHash, a.mime, a.media_type AS mediaType,
               a.width, a.height, a.orientation, a.captured_at AS capturedAt,
               a.tz_offset_minutes AS tzOffsetMinutes, a.duration_ms AS durationMs,
               a.favorite, a.blurhash
        FROM album_assets aa
        JOIN assets a ON a.id = aa.asset_id
        WHERE aa.album_id = :albumId AND a.deleted_at IS NULL
          AND (:cursorCapturedAt IS NULL
               OR a.captured_at < :cursorCapturedAt
               OR (a.captured_at = :cursorCapturedAt AND a.id < :cursorId))
        ORDER BY a.captured_at DESC, a.id DESC
        LIMIT :limit
        """,
    )
    suspend fun contents(
        albumId: Long,
        cursorCapturedAt: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TimelineRow>
}

/**
 * The work queue.
 *
 * Abstract class rather than an interface so [claim] can be a real `@Transaction` method: selecting
 * candidates and leasing them must be atomic, or two workers can pick up the same job.
 */
@Dao
abstract class JobDao {

    /** Unique dedupe_key makes this idempotent, so callers can enqueue freely without checking. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueue(job: JobEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueueAll(jobs: List<JobEntity>)

    @Query(
        """
        SELECT id FROM jobs
        WHERE state = ${JobState.PENDING} AND not_before <= :now
        ORDER BY priority ASC, not_before ASC, id ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun claimable(now: Long, limit: Int): List<Long>

    @Query(
        """
        UPDATE jobs SET state = ${JobState.LEASED}, lease_owner = :owner,
            lease_expires_at = :expiresAt, attempts = attempts + 1, updated_at = :now
        WHERE id IN (:ids)
        """,
    )
    protected abstract suspend fun lease(ids: List<Long>, owner: String, expiresAt: Long, now: Long)

    @Query("SELECT * FROM jobs WHERE id IN (:ids) ORDER BY priority ASC, id ASC")
    protected abstract suspend fun byIds(ids: List<Long>): List<JobEntity>

    @Transaction
    open suspend fun claim(now: Long, owner: String, leaseMs: Long, limit: Int): List<JobEntity> {
        val ids = claimable(now, limit)
        if (ids.isEmpty()) return emptyList()
        lease(ids, owner, now + leaseMs, now)
        return byIds(ids)
    }

    /**
     * Releases `dedupe_key` as well as marking the row done.
     *
     * The key exists to answer "is this work already queued". Once the job is finished that answer
     * is no, and leaving the key in place would make the unique index silently reject every future
     * request for the same work -- a scan that can only ever run once.
     */
    @Query(
        """
        UPDATE jobs SET state = ${JobState.DONE}, dedupe_key = NULL,
            finished_at = :now, updated_at = :now, lease_owner = NULL
        WHERE id = :id
        """,
    )
    abstract suspend fun complete(id: Long, now: Long)

    @Query(
        """
        UPDATE jobs SET state = ${JobState.PENDING}, not_before = :notBefore,
            last_error = :error, updated_at = :now, lease_owner = NULL
        WHERE id = :id
        """,
    )
    abstract suspend fun retryLater(id: Long, notBefore: Long, error: String?, now: Long)

    /** Also releases the dedupe key: a failed job must not block re-requesting the same work. */
    @Query(
        """
        UPDATE jobs SET state = ${JobState.FAILED}, dedupe_key = NULL, last_error = :error,
            finished_at = :now, updated_at = :now, lease_owner = NULL
        WHERE id = :id
        """,
    )
    abstract suspend fun fail(id: Long, error: String?, now: Long)

    /** Volume gone: parked, not failed, so a replug resumes rather than exhausting retries. */
    @Query("UPDATE jobs SET state = ${JobState.BLOCKED}, last_error = :error, updated_at = :now, lease_owner = NULL WHERE id = :id")
    abstract suspend fun block(id: Long, error: String?, now: Long)

    @Query("UPDATE jobs SET state = ${JobState.PENDING}, not_before = 0, updated_at = :now WHERE state = ${JobState.BLOCKED}")
    abstract suspend fun unblockAll(now: Long)

    /**
     * Startup recovery. Single-process, so any lease still marked LEASED belongs to a run that
     * died: a blanket reset is both correct and simpler than expiry arithmetic.
     */
    @Query("UPDATE jobs SET state = ${JobState.PENDING}, lease_owner = NULL, updated_at = :now WHERE state = ${JobState.LEASED}")
    abstract suspend fun resetOrphanedLeases(now: Long)

    @Query("SELECT COUNT(*) FROM jobs WHERE state = :state")
    abstract suspend fun countByState(state: Int): Int

    @Query("SELECT type, COUNT(*) AS count FROM jobs WHERE state = ${JobState.PENDING} GROUP BY type")
    abstract suspend fun pendingByType(): List<TypeCount>

    @Query("DELETE FROM jobs WHERE state IN (${JobState.DONE}, ${JobState.CANCELLED}) AND finished_at < :before")
    abstract suspend fun prune(before: Long)
}

data class TypeCount(val type: String, val count: Int)

@Dao
abstract class ImportDao {

    @Insert
    abstract suspend fun createSession(session: ImportSessionEntity): Long

    @Upsert
    abstract suspend fun upsertSession(session: ImportSessionEntity)

    @Query("SELECT * FROM import_sessions WHERE id = :id")
    abstract suspend fun session(id: Long): ImportSessionEntity?

    /** Resume rather than start: an existing unfinished session is always continued. */
    @Query("SELECT * FROM import_sessions WHERE state IN (0, 1) ORDER BY id LIMIT 1")
    abstract suspend fun activeSession(): ImportSessionEntity?

    @Query("SELECT * FROM import_sessions ORDER BY started_at DESC LIMIT :limit")
    abstract suspend fun recentSessions(limit: Int): List<ImportSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addItems(items: List<ImportItemEntity>)

    @Upsert
    abstract suspend fun upsertItem(item: ImportItemEntity)

    @Query("SELECT * FROM import_items WHERE session_id = :sessionId AND source_key = :key LIMIT 1")
    abstract suspend fun item(sessionId: Long, key: String): ImportItemEntity?

    /** Everything not in a terminal stage, oldest first. This single query *is* resume. */
    @Query(
        """
        SELECT * FROM import_items
        WHERE session_id = :sessionId AND stage NOT IN (1, 7, 8, 9)
        ORDER BY id ASC LIMIT :limit
        """,
    )
    abstract suspend fun resumable(sessionId: Long, limit: Int): List<ImportItemEntity>

    @Query("SELECT stage, COUNT(*) AS count FROM import_items WHERE session_id = :sessionId GROUP BY stage")
    abstract suspend fun stageCounts(sessionId: Long): List<StageCount>

    @Query("SELECT * FROM import_items WHERE session_id = :sessionId AND stage = 8 ORDER BY id DESC LIMIT :limit")
    abstract suspend fun failures(sessionId: Long, limit: Int): List<ImportItemEntity>
}

data class StageCount(val stage: Int, val count: Int)

@Dao
interface AuthTokenDao {
    @Insert
    suspend fun insert(token: AuthTokenEntity): Long

    @Query("SELECT * FROM auth_tokens WHERE token_hash = :hash AND revoked_at IS NULL LIMIT 1")
    suspend fun active(hash: String): AuthTokenEntity?

    @Query("SELECT * FROM auth_tokens ORDER BY created_at DESC")
    suspend fun all(): List<AuthTokenEntity>

    @Query("UPDATE auth_tokens SET last_used_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE auth_tokens SET revoked_at = :now WHERE id = :id")
    suspend fun revoke(id: Long, now: Long)
}
