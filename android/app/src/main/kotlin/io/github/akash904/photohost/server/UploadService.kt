package io.github.akash904.photohost.server

import android.util.Log
import io.github.akash904.photohost.core.dualHash
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.index.IndexResult
import io.github.akash904.photohost.index.LibraryIndexer
import io.github.akash904.photohost.storage.LibraryStore
import io.github.akash904.photohost.storage.joinRel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val TAG = "photohost"

/**
 * Receives uploads from a backup client.
 *
 * ### Staged on internal storage, not on the library volume
 * A partial upload has to be appendable and restart-safe, and SAF offers no reliable append on a
 * USB volume. So bytes land in app-private storage first and are copied into the library only once
 * the hash checks out. That costs one extra write and buys two things: resume works identically on
 * every backend, and a truncated or corrupted transfer can never be committed to the library.
 *
 * ### Resume needs no database
 * The staging file's own length *is* the offset. A client that dies mid-upload asks where it got to
 * and continues. The only extra state is a small JSON sidecar holding the declared name, size and
 * hash, so a server restart does not lose the session either.
 */
class UploadService(
    private val db: AppDatabase,
    private val store: LibraryStore,
    private val indexer: LibraryIndexer,
    stagingRoot: File,
) {
    private val staging = stagingRoot.apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Session(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String?,
        val capturedAt: Long?,
        val sourceAlbum: String? = null,
    )

    // ------------------------------------------------------------------ hash pre-check

    /**
     * The call that makes continuous backup cheap.
     *
     * A client offers the hashes it is holding and learns which are already stored, so re-offering
     * a photo costs one small round trip and zero bytes. Without this, "back up everything" would
     * mean re-uploading the entire library every time the client lost its own state.
     */
    suspend fun knownIds(hashes: List<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (h in hashes.distinct()) {
            db.assets().byHash(h)?.let { out[h] = it.id }
        }
        return out
    }

    /**
     * Which of [hashes] this library holds safely right now: the question a phone asks before it
     * deletes its own copies to free space, so every clause errs towards "no".
     *
     * Safe means an asset with exactly these bytes, not in the trash (a trashed asset is on its way
     * to being purged), with a canonical file that is not marked missing, and that file present on
     * the store at this moment at the recorded size. The last check is live, not from the index: a
     * library on a USB drive that has been unplugged still has every row, and none of the files.
     *
     * [knownIds] answers a different question -- "is there any point uploading this?" -- and says yes
     * for trashed and unreachable assets alike, which is right for backup and wrong here.
     */
    suspend fun safeHashes(hashes: List<String>): List<String> {
        val out = ArrayList<String>()
        for (h in hashes.distinct()) {
            val asset = db.assets().byHash(h.lowercase()) ?: continue
            if (asset.deletedAt != null) continue
            val file = db.assetFiles().canonical(asset.id) ?: continue
            val onDisk = withContext(Dispatchers.IO) { runCatching { store.stat(file.relPath) }.getOrNull() }
                ?: continue
            if (onDisk.isDirectory || onDisk.size != asset.byteSize) continue
            out += h
        }
        return out
    }

    // ------------------------------------------------------------------ session lifecycle

    fun begin(
        name: String,
        size: Long,
        sha256: String?,
        capturedAt: Long?,
        sourceAlbum: String? = null,
    ): Session {
        val session = Session(
            UUID.randomUUID().toString(),
            sanitize(name),
            size,
            sha256?.lowercase(),
            capturedAt,
            sourceAlbum,
        )
        metaFile(session.id).writeText(json.encodeToString(Session.serializer(), session))
        partFile(session.id).createNewFile()
        return session
    }

    fun offsetOf(id: String): Long = partFile(id).takeIf { it.isFile }?.length() ?: -1L

    fun sessionOf(id: String): Session? = try {
        metaFile(id).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Session.serializer(), it) }
    } catch (t: Throwable) {
        null
    }

    /**
     * Appends a chunk. [expectedOffset] is what the client believes the server holds; a mismatch is
     * rejected rather than silently appended, because writing a chunk at the wrong position would
     * produce a file that is the right length and the wrong content.
     */
    suspend fun append(
        id: String,
        expectedOffset: Long?,
        read: suspend (FileOutputStream) -> Long,
    ): AppendResult {
        val part = partFile(id)
        if (!part.isFile) return AppendResult.NoSuchSession
        val current = part.length()
        if (expectedOffset != null && expectedOffset != current) {
            return AppendResult.OffsetMismatch(current)
        }
        val written = FileOutputStream(part, true).use { read(it) }
        return AppendResult.Ok(current + written)
    }

    sealed interface AppendResult {
        data object NoSuchSession : AppendResult
        data class OffsetMismatch(val actual: Long) : AppendResult
        data class Ok(val offset: Long) : AppendResult
    }

    /**
     * Verifies, commits into the library, and indexes.
     *
     * The hash is recomputed from what actually landed rather than trusted from the client: that is
     * the only way a truncated or corrupted transfer is caught, and it is also what makes the
     * dedupe guarantee real rather than advisory.
     */
    suspend fun finish(id: String): FinishResult {
        val session = sessionOf(id) ?: return FinishResult.NoSuchSession
        val part = partFile(id)
        if (!part.isFile) return FinishResult.NoSuchSession

        if (session.size > 0 && part.length() != session.size) {
            return FinishResult.SizeMismatch(expected = session.size, actual = part.length())
        }

        val hash = FileInputStream(part).use { it.dualHash() }
        if (session.sha256 != null && !session.sha256.equals(hash.contentHash, ignoreCase = true)) {
            cleanup(id)
            return FinishResult.HashMismatch(expected = session.sha256, actual = hash.contentHash)
        }

        // Already stored: drop the bytes and report success. Re-uploading is meant to be free.
        db.assets().byHash(hash.contentHash)?.let {
            cleanup(id)
            return FinishResult.Duplicate(it.id)
        }

        val relPath = destinationFor(session, hash.contentHash)
        try {
            store.openWrite(relPath).use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(part).use { input -> input.copyTo(out, 256 * 1024) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "upload commit failed for $relPath", t)
            return FinishResult.Failed("${t.javaClass.simpleName}: ${t.message}")
        }

        val entry = store.stat(relPath)
            ?: return FinishResult.Failed("committed file not found at $relPath")

        return when (val indexed = indexer.index(entry, capturedAtHint = session.capturedAt, sourceAlbum = session.sourceAlbum)) {
            is IndexResult.Indexed -> {
                cleanup(id)
                FinishResult.Stored(indexed.assetId, relPath)
            }
            is IndexResult.Duplicate -> {
                cleanup(id)
                FinishResult.Duplicate(indexed.assetId)
            }
            IndexResult.Skipped -> {
                cleanup(id)
                FinishResult.Failed("indexer skipped a freshly written file")
            }
            is IndexResult.Failed -> FinishResult.Failed(indexed.reason)
        }
    }

    sealed interface FinishResult {
        data object NoSuchSession : FinishResult
        data class SizeMismatch(val expected: Long, val actual: Long) : FinishResult
        data class HashMismatch(val expected: String, val actual: String) : FinishResult
        data class Duplicate(val assetId: Long) : FinishResult
        data class Stored(val assetId: Long, val relPath: String) : FinishResult
        data class Failed(val reason: String) : FinishResult
    }

    fun cancel(id: String) = cleanup(id)

    /** Sweeps abandoned transfers so a flaky client cannot slowly fill internal storage. */
    fun sweepStale(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        staging.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Files are laid out by capture date, which keeps any one directory small. That matters for SAF
     * far more than for internal storage: listing a directory with tens of thousands of entries is
     * a per-child Binder round trip.
     *
     * The content hash is appended so two different photos with the same camera filename cannot
     * collide, without needing to probe the store for a free name.
     */
    private fun destinationFor(session: Session, contentHash: String): String {
        val fmt = SimpleDateFormat("yyyy/MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val folder = fmt.format(java.util.Date(session.capturedAt ?: System.currentTimeMillis()))
        val dot = session.name.lastIndexOf('.')
        val stem = if (dot > 0) session.name.substring(0, dot) else session.name
        val ext = if (dot > 0) session.name.substring(dot) else ""
        return joinRel(folder, "$stem-${contentHash.take(8)}$ext")
    }

    /**
     * exFAT rejects these characters outright, and a trailing dot or space silently breaks a name
     * on Windows too. Sanitising here rather than at write time keeps the stored name and the
     * recorded relative path identical, which the resolution ladder depends on.
     */
    private fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { "upload" }
        return if (cleaned.toByteArray().size > 200) cleaned.take(160) else cleaned
    }

    private fun partFile(id: String) = File(staging, "$id.part")
    private fun metaFile(id: String) = File(staging, "$id.json")

    private fun cleanup(id: String) {
        partFile(id).delete()
        metaFile(id).delete()
    }
}
