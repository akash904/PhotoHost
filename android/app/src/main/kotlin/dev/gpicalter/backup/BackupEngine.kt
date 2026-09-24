package dev.gpicalter.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.gpicalter.core.dualHash
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.SourceFingerprintEntity
import dev.gpicalter.data.entity.SourceKind
import dev.gpicalter.net.LibraryApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "gpic"
private const val CHUNK = 1 shl 20
private const val CHECK_BATCH = 200

/** Progress, observed by the UI. Process-local; durable state lives in `source_fingerprints`. */
object BackupState {
    data class Snapshot(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val uploaded: Int = 0,
        val skipped: Int = 0,
        val alreadyOnServer: Int = 0,
        val failed: Int = 0,
        /**
         * Items whose contents have been read and hashed.
         *
         * Separate from [done], which only counts items the server has finished with. Hashing is
         * the slow half -- every candidate is read in full and digested, up to CHECK_BATCH of them
         * before a single upload starts -- and while it runs [done] cannot move. Without a counter
         * of its own the screen sits on "Backing up 0 of 250" for minutes and reads as a hang.
         */
        val checked: Int = 0,
        val currentName: String? = null,
        val lastError: String? = null,
        val finishedAt: Long = 0,
    ) {
        val remaining: Int get() = (total - done).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) = _state.update(transform)
}

/**
 * Backs this phone's camera roll up to the configured library.
 *
 * ### Three gates, cheapest first
 * 1. **Local fingerprint.** A `source_fingerprints` row keyed on the MediaStore id, matching on
 *    size, date-modified and the generation token, means skip without opening the file. A rescan of
 *    an unchanged camera roll is one cursor plus index lookups.
 * 2. **Server hash check, batched.** Anything new gets hashed once, then whole batches are offered
 *    to the server at a time. Items it already holds cost zero bytes.
 * 3. **Upload,** resumable in chunks.
 *
 * The first run is unavoidably expensive: every photo has to be read once to be hashed, exactly as
 * Immich does. Every run after that is nearly free, which is the property that makes leaving backup
 * switched on tolerable.
 *
 * A fingerprint is recorded even when the server already had the file, so the next run does not
 * re-hash it just to be told the same thing again.
 */
class BackupEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val api: LibraryApi,
    private val prefs: dev.gpicalter.core.Prefs? = null,
) {
    /**
     * @param onlyIds when set, exactly these MediaStore ids are considered and the folder
     *   selection is ignored. An explicit "back up these" must not be silently filtered by a
     *   folder preference the user is not looking at.
     */
    suspend fun run(onlyIds: Set<Long>? = null): Result {
        BackupState.update {
            Snapshot0.reset(it)
        }
        val items = try {
            if (onlyIds != null) {
                DeviceMedia.list(context).filter { it.mediaStoreId in onlyIds }
            } else {
                // "Never chosen" must not mean "everything". Defaulting to the whole device turns
                // switching backup on into an immediate upload of every cached meme and download,
                // which is exactly what it did before this. Unchosen falls back to camera-like
                // folders; an explicitly empty set still means nothing.
                val chosen = prefs?.backupBuckets
                    ?: MediaBuckets.defaultSelection(MediaBuckets.enumerate(context))
                DeviceMedia.list(context, chosen)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "media query failed", t)
            BackupState.update { it.copy(running = false, lastError = "media query: ${t.message}") }
            return Result(failed = 1, error = t.message)
        }

        BackupState.update { it.copy(running = true, total = items.size, done = 0) }

        return try {
            backup(items)
        } finally {
            // Cancellation unwinds through ensureActive(), so without this the card would sit on
            // "Backing up 31 of 402" forever after the user stopped it.
            BackupState.update {
                it.copy(running = false, currentName = null, finishedAt = System.currentTimeMillis())
            }
        }
    }

    private suspend fun backup(items: List<DeviceItem>): Result {

        // Gate 1 runs over everything first, so `total` reflects real work rather than counting
        // down through thousands of instant skips.
        val candidates = ArrayList<DeviceItem>()
        val alreadySent = ArrayList<Pair<DeviceItem, String>>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val fp = db.fingerprints().find(SourceKind.MEDIASTORE, item.sourceKey)
            if (fp != null && isUnchanged(item, fp)) {
                alreadySent.add(item to fp.contentHash)
                BackupState.update { it.copy(done = it.done + 1, skipped = it.skipped + 1) }
            } else {
                candidates.add(item)
            }
        }

        if (prefs?.dateRepairDone == false) repairDates(alreadySent)

        var uploaded = 0
        var duplicates = 0
        var failed = 0

        for (batch in candidates.chunked(CHECK_BATCH)) {
            currentCoroutineContext().ensureActive()

            val hashed = HashMap<String, MutableList<DeviceItem>>()
            for (item in batch) {
                currentCoroutineContext().ensureActive()
                BackupState.update { it.copy(currentName = item.name) }
                val hash = hashOf(item)
                if (hash == null) {
                    failed++
                    BackupState.update {
                        it.copy(done = it.done + 1, checked = it.checked + 1, failed = it.failed + 1)
                    }
                    continue
                }
                BackupState.update { it.copy(checked = it.checked + 1) }
                hashed.getOrPut(hash) { ArrayList() }.add(item)
            }
            if (hashed.isEmpty()) continue

            val known = api.knownHashes(hashed.keys.toList())

            for ((hash, group) in hashed) {
                currentCoroutineContext().ensureActive()
                val item = group.first()
                BackupState.update { it.copy(currentName = item.name) }

                val knownId = known[hash]
                val ok = if (knownId != null) {
                    duplicates++
                    // Self-healing: offer the date MediaStore recorded. The server takes it only if
                    // what it stored is weaker, so a library whose dates were derived from upload
                    // time repairs itself on the next run instead of needing a rebuild.
                    item.takenAt?.let { api.offerCapturedAt(knownId, it) }
                    BackupState.update { it.copy(alreadyOnServer = it.alreadyOnServer + 1) }
                    true
                } else {
                    val sent = upload(item, hash)
                    if (sent) uploaded++ else failed++
                    BackupState.update {
                        if (sent) it.copy(uploaded = it.uploaded + 1) else it.copy(failed = it.failed + 1)
                    }
                    sent
                }

                // Recorded for every item that reached the server, uploaded or not: otherwise the
                // next run re-hashes it only to be told again that it is already there.
                if (ok) group.forEach { remember(it, hash) }
                BackupState.update { it.copy(done = it.done + group.size) }
            }
        }

        Log.i(TAG, "backup done: uploaded=$uploaded already=$duplicates failed=$failed")
        return Result(uploaded, duplicates, failed, api.lastError.takeIf { failed > 0 })
    }

    data class Result(
        val uploaded: Int = 0,
        val alreadyOnServer: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    )

    // ------------------------------------------------------------------ gates

    /**
     * One-time pass over items already on the server, offering the capture date MediaStore knows.
     * The server upgrades only from a weaker source, so this cannot damage a correct date.
     */
    private suspend fun repairDates(sent: List<Pair<DeviceItem, String>>) {
        val withDates = sent.filter { it.first.takenAt != null }
        if (withDates.isEmpty()) {
            prefs?.dateRepairDone = true
            return
        }
        var fixed = 0
        for (chunk in withDates.chunked(CHECK_BATCH)) {
            currentCoroutineContext().ensureActive()
            val ids = api.knownHashes(chunk.map { it.second })
            for ((item, hash) in chunk) {
                val assetId = ids[hash] ?: continue
                val taken = item.takenAt ?: continue
                if (api.offerCapturedAt(assetId, taken)) fixed++
            }
        }
        Log.i(TAG, "date repair offered for $fixed assets")
        prefs?.dateRepairDone = true
    }

    private fun isUnchanged(item: DeviceItem, fp: SourceFingerprintEntity): Boolean {
        if (fp.sizeBytes != item.size) return false
        // GENERATION_MODIFIED is an authoritative change token when the platform provides it;
        // mtime alone is not, because some apps rewrite a file without touching it.
        if (item.generation != null && fp.generation != null) return fp.generation == item.generation
        return kotlin.math.abs(fp.modifiedAt - item.modifiedAt) <= 2_000L
    }

    private fun hashOf(item: DeviceItem): String? = try {
        context.contentResolver.openInputStream(item.uri)?.use { it.dualHash().contentHash }
    } catch (t: Throwable) {
        Log.w(TAG, "hash failed for ${item.name}: ${t.message}")
        null
    }

    private suspend fun remember(item: DeviceItem, hash: String) {
        db.fingerprints().upsert(
            SourceFingerprintEntity(
                id = db.fingerprints().find(SourceKind.MEDIASTORE, item.sourceKey)?.id ?: 0,
                sourceKind = SourceKind.MEDIASTORE,
                sourceKey = item.sourceKey,
                sizeBytes = item.size,
                modifiedAt = item.modifiedAt,
                generation = item.generation,
                contentHash = hash,
                hashedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------------------------ transfer

    private suspend fun upload(item: DeviceItem, hash: String): Boolean {
        val init = api.uploadInit(item.name, item.size, hash, item.takenAt, item.bucketName)
            ?: return false
        if (init.duplicate) return true
        val uploadId = init.uploadId ?: return false

        var offset = init.offset
        var attempts = 0
        while (offset < item.size) {
            currentCoroutineContext().ensureActive()
            val chunk = readAt(item.uri, offset, CHUNK) ?: return false
            if (chunk.second <= 0) break

            val next = api.uploadChunk(uploadId, offset, chunk.first, chunk.second)
            if (next == null) {
                // One re-sync against the server's own offset before giving up, which covers the
                // common case of a chunk that landed but whose response was lost.
                if (++attempts > 3) return false
                offset = api.uploadOffset(uploadId) ?: return false
                continue
            }
            if (next <= offset) return false
            offset = next
        }

        val result = api.uploadFinish(uploadId)
        if (result == null) {
            Log.w(TAG, "finish failed for ${item.name}: ${api.lastError}")
            return false
        }
        return true
    }

    /**
     * Reads one chunk. MediaStore streams are not seekable in general, so the stream is reopened
     * and skipped rather than held across chunks -- which also means a resumed upload does not
     * depend on a connection that may have been dead for hours.
     */
    private fun readAt(uri: Uri, offset: Long, length: Int): Pair<ByteArray, Int>? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            if (skipped != offset) return null
            val buf = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = input.read(buf, filled, length - filled)
                if (n < 0) break
                filled += n
            }
            buf to filled
        }
    } catch (t: Throwable) {
        Log.w(TAG, "read failed at $offset: ${t.message}")
        null
    }

}

/** Kept separate so a fresh run does not inherit the previous run's counters. */
private object Snapshot0 {
    fun reset(previous: BackupState.Snapshot) = BackupState.Snapshot(
        running = true,
        lastError = previous.lastError,
    )
}
