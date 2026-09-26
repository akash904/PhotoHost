package dev.gpicalter.jobs

import dev.gpicalter.core.Log
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.JobEntity
import dev.gpicalter.data.entity.MediaType
import dev.gpicalter.data.entity.ThumbState
import dev.gpicalter.data.entity.ThumbnailEntity
import dev.gpicalter.index.StoreScanner
import dev.gpicalter.media.DecoderUnavailableException
import dev.gpicalter.media.ThumbnailCache
import dev.gpicalter.media.ThumbnailGenerator
import dev.gpicalter.storage.LibraryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

private const val TAG = "gpic"

/**
 * Renders one thumbnail.
 *
 * Failure is classified rather than lumped together, because the three cases want opposite
 * treatment: a missing volume should park the job until the drive returns, a codec that cannot
 * decode this file will never succeed and should stop consuming retries, and anything else is
 * probably transient.
 */
class ThumbnailHandler(
    private val db: AppDatabase,
    private val store: LibraryStore,
    private val generator: ThumbnailGenerator,
) : JobHandler {

    override suspend fun run(job: JobEntity): Outcome {
        val payload = Json.parseToJsonElement(job.payload).jsonObject
        val assetId = payload["assetId"]?.jsonPrimitive?.long ?: return Outcome.Fail("no assetId")
        val sizeClass = payload["sizeClass"]?.jsonPrimitive?.int ?: return Outcome.Fail("no sizeClass")

        val asset = db.assets().byId(assetId) ?: return Outcome.Fail("asset $assetId is gone")
        val file = db.assetFiles().canonical(assetId)
            ?: return Outcome.Blocked("no resolvable file for asset $assetId")

        if (!store.isMounted) return Outcome.Blocked("store not mounted")

        // Desktop only. Android decodes HEIC and pulls video frames with platform codecs; the
        // desktop has neither until its decoders ship. That is a missing capability, not a bad file,
        // so the job is parked rather than burning its retries into FAILED_PERMANENT -- a later
        // version with the decoder then picks it up at start-up, when BLOCKED jobs are released.
        val isVideo = asset.mediaType == MediaType.VIDEO
        if (!generator.canRender(asset.mime, isVideo)) {
            return Outcome.Blocked("no decoder yet for ${asset.mime}")
        }

        val now = System.currentTimeMillis()
        db.thumbnails().upsert(
            ThumbnailEntity(
                assetId = assetId,
                sizeClass = sizeClass,
                state = ThumbState.RUNNING,
                attemptCount = job.attempts,
            ),
        )

        return try {
            val rendered = generator.render(
                contentHash = asset.contentHash,
                relPath = file.relPath,
                isVideo = isVideo,
                orientation = asset.orientation,
                sizeClass = sizeClass,
                durationMs = asset.durationMs,
            )
            db.thumbnails().upsert(
                ThumbnailEntity(
                    assetId = assetId,
                    sizeClass = sizeClass,
                    state = ThumbState.READY,
                    cacheRelPath = rendered.relPath,
                    byteSize = rendered.bytes,
                    width = rendered.width,
                    height = rendered.height,
                    attemptCount = job.attempts,
                    generatedAt = now,
                    lastAccessAt = now,
                ),
            )
            Outcome.Done
        } catch (e: DecoderUnavailableException) {
            // Desktop only: the Windows codec for this file is not installed. Not the file's fault and
            // not transient, so neither FAILED nor a retry: parked until an extension is installed,
            // and the row removed so the server answers "not ready" rather than "running" meanwhile.
            db.thumbnails().delete(assetId, sizeClass)
            Outcome.Blocked(e.message ?: "decoder not installed")
        } catch (t: OutOfMemoryError) {
            // Not retryable in any useful sense, and retrying risks taking the process with it.
            markFailed(assetId, sizeClass, job.attempts, "OOM", permanent = true)
            Outcome.Fail("OOM decoding ${file.relPath}")
        } catch (t: Throwable) {
            val permanent = job.attempts >= 3
            markFailed(assetId, sizeClass, job.attempts, t.message, permanent)
            if (permanent) {
                Outcome.Fail("${t.javaClass.simpleName}: ${t.message}")
            } else {
                Outcome.Retry("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private suspend fun markFailed(
        assetId: Long,
        sizeClass: Int,
        attempts: Int,
        error: String?,
        permanent: Boolean,
    ) {
        db.thumbnails().upsert(
            ThumbnailEntity(
                assetId = assetId,
                sizeClass = sizeClass,
                state = if (permanent) ThumbState.FAILED_PERMANENT else ThumbState.FAILED_RETRY,
                attemptCount = attempts,
                lastError = error?.take(300),
            ),
        )
    }
}

/**
 * Spends the thumbnail cache budget.
 *
 * A job rather than a timer inside the sweeper, so it inherits everything the queue already does:
 * it will not run while the phone is thermally throttled, it is deduped so a burst of triggers is
 * one sweep, and a process death mid-sweep leaves the cache merely un-swept rather than half
 * accounted for. Nothing here is retried -- the next sweep supersedes this one entirely.
 */
class EvictCacheHandler(
    private val cache: ThumbnailCache,
) : JobHandler {

    override suspend fun run(job: JobEntity): Outcome {
        return try {
            cache.sweep()
            Outcome.Done
        } catch (t: Throwable) {
            Outcome.Fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

/**
 * Walks the store and indexes what it finds.
 *
 * One long job rather than thousands of small ones. Resumability comes from the fingerprint gate, so
 * a scan killed at 60% re-runs and skips what it already did -- much simpler than checkpointing, and
 * it doubles as the repair path after any inconsistency.
 */
class ScanHandler(
    private val scanner: StoreScanner,
    private val onProgress: (StoreScanner.Progress) -> Unit,
) : JobHandler {

    override suspend fun run(job: JobEntity): Outcome {
        return try {
            val result = scanner.scan { onProgress(it) }
            Log.i(
                TAG,
                "scan done: scanned=${result.scanned} indexed=${result.indexed} " +
                    "dup=${result.duplicates} skipped=${result.skipped} failed=${result.failed}",
            )
            Outcome.Done
        } catch (t: Throwable) {
            Outcome.Retry("${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
