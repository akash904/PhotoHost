package dev.gpicalter.desktop

import dev.gpicalter.core.DualHash
import dev.gpicalter.core.Log
import dev.gpicalter.core.copyHashing
import dev.gpicalter.core.dualHash
import dev.gpicalter.core.sha256Prefix
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.CaptureSource
import dev.gpicalter.data.entity.ImportItemEntity
import dev.gpicalter.data.entity.ImportSessionEntity
import dev.gpicalter.data.entity.ImportSource
import dev.gpicalter.data.entity.ImportStage
import dev.gpicalter.data.entity.ImportState
import dev.gpicalter.data.entity.SourceFingerprintEntity
import dev.gpicalter.data.entity.SourceKind
import dev.gpicalter.index.IndexResult
import dev.gpicalter.index.LibraryIndexer
import dev.gpicalter.media.MediaProbe
import dev.gpicalter.storage.FolderStore
import dev.gpicalter.storage.Mime
import dev.gpicalter.storage.joinRel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "gpic"

/** What the window shows about the import. */
data class ImportStatus(
    val phase: Phase = Phase.IDLE,
    val sessionId: Long = 0,
    val source: String? = null,
    val total: Int = 0,
    /** Items finished in any way: copied, duplicate, skipped or failed. */
    val done: Int = 0,
    val copied: Int = 0,
    val duplicates: Int = 0,
    /** Already imported from this exact path earlier, recognised without reading the file. */
    val unchanged: Int = 0,
    val failed: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val current: String? = null,
    val message: String? = null,
) {
    enum class Phase { IDLE, DISCOVERING, AWAITING_CONFIRM, RUNNING, PAUSED, DONE, CANCELLED, FAILED }
}

/** The outcome of listing a folder, shown to the user before anything is copied. */
data class Discovery(
    val sessionId: Long,
    val files: Int,
    val bytes: Long,
    val freeBytes: Long?,
    val unreadable: Int,
)

/**
 * Copies photos and videos from a folder on this PC into the library.
 *
 * The library owns what is in it, so an archive is copied in rather than indexed where it lies --
 * and the source is only ever opened for reading. Each file goes through the stage machine the
 * database was designed with (`import_items`, unused until now), and every stage is recorded before
 * the next begins, so a crash, a closed window or a power cut resumes where it stopped:
 *
 *  1. **Already seen?** A fingerprint of (path, size, mtime) from an earlier import skips the file
 *     without opening it. Importing the same folder again only costs a directory walk.
 *  2. **Duplicate?** Size plus the first 64 KiB rule most files out as new without reading them
 *     whole. Only when that matches something already stored is the file hashed in full, and if the
 *     bytes are already in the library -- from a phone backup, or another folder -- it is skipped.
 *  3. **Copy** to `.gpic-tmp/<uuid>.part` inside the library, hashing as it goes, then fsync.
 *  4. **Verify** by reading the copy back and hashing it again. A truncated or corrupted write can
 *     never be committed.
 *  5. **Commit**: rename into `yyyy/MM/<name>-<hash8>.<ext>` -- the layout phone uploads use -- and
 *     give it the source's modified time, which is still the best date some files have.
 *  6. **Index** with the verified hash, so the indexer does not read the file a third time.
 *
 * Before each copy there must be room for the file plus [RESERVE_BYTES]; otherwise the import pauses
 * with a message instead of filling the disk.
 */
class FolderImporter(
    private val db: AppDatabase,
    private val library: FolderStore,
    private val indexer: LibraryIndexer,
    private val probe: MediaProbe,
    private val volumeId: Long,
    private val scope: CoroutineScope,
    private val freeSpace: () -> Long? = { library.capacity()?.availableBytes },
) {
    private val _status = MutableStateFlow(ImportStatus())
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** Set only by [cancel]. Shutting the app down stops the job too, but must leave it resumable. */
    @Volatile
    private var userCancelled = false

    val busy: Boolean get() = job?.isActive == true

    // ------------------------------------------------------------------ lifecycle

    /**
     * Picks up where the app left off: a running import continues on its own, a paused one waits
     * for Resume, and one interrupted while still listing is abandoned -- the source folder is not
     * recorded, so it cannot be re-listed, and starting it again is cheap because everything already
     * copied is recognised.
     */
    suspend fun resumeOnStartup() = withContext(Dispatchers.IO) {
        val active = db.imports().activeSession()
        when (active?.state) {
            ImportState.RUNNING -> start(active.id)
            ImportState.DISCOVERING -> {
                finish(active.id, ImportState.CANCELLED, "interrupted while listing files; start it again")
            }
            else -> {
                db.imports().recentSessions(1).firstOrNull()?.takeIf { it.state == ImportState.PAUSED }?.let {
                    publishCounts(it.id, ImportStatus.Phase.PAUSED, it.lastError)
                }
            }
        }
    }

    /** A whole folder, including everything under it. */
    suspend fun discover(source: File): Discovery = discover(listOf(source))

    /**
     * Lists [sources] -- folders, individual files, or a mix -- and records every media file among
     * them, copying nothing yet. The caller shows the result and then calls [start] or [discard].
     */
    suspend fun discover(sources: List<File>): Discovery = withContext(Dispatchers.IO) {
        check(!busy) { "an import is already running" }
        require(sources.isNotEmpty()) { "nothing chosen" }
        val roots = sources.map { it.toPath().toAbsolutePath().normalize() }
        val lib = library.root
        for (root in roots) {
            require(Files.exists(root)) { "not found: $root" }
            require(!root.startsWith(lib) && !(Files.isDirectory(root) && lib.startsWith(root))) {
                "Choose something outside the library. Importing the library into itself, or a folder " +
                    "that contains the library, would copy the library's own files back in."
            }
        }
        val label = if (roots.size == 1) roots[0].toString() else "${roots.size} selected items"

        val now = System.currentTimeMillis()
        val sessionId = db.imports().createSession(
            ImportSessionEntity(
                source = ImportSource.STORE_FOLDER,
                targetVolumeId = volumeId,
                state = ImportState.DISCOVERING,
                startedAt = now,
                updatedAt = now,
            ),
        )
        _status.value = ImportStatus(ImportStatus.Phase.DISCOVERING, sessionId, label, message = "Listing files...")

        var files = 0
        var bytes = 0L
        var unreadable = 0
        val pending = ArrayList<ImportItemEntity>(BATCH)
        val flush: suspend () -> Unit = {
            if (pending.isNotEmpty()) {
                db.imports().addItems(pending.toList())
                pending.clear()
                _status.update { it.copy(total = files, bytesTotal = bytes) }
            }
        }

        // A file chosen twice -- picked directly and also inside a chosen folder -- is listed once.
        val found = LinkedHashMap<Path, BasicFileAttributes>()
        for (root in roots) {
            if (!Files.isDirectory(root)) {
                // Chosen individually: taken as long as it is a photo or video.
                runCatching { Files.readAttributes(root, BasicFileAttributes::class.java) }
                    .onSuccess { if (it.isRegularFile && isMedia(root.fileName.toString())) found[root] = it }
                    .onFailure { unreadable++ }
                continue
            }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && skipDirectory(dir.fileName.toString())) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && isMedia(file.fileName.toString())) found[file] = attrs
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    unreadable++
                    return FileVisitResult.CONTINUE
                }
            })
        }

        for ((file, attrs) in found) {
            currentCoroutineContext().ensureActive()
            files++
            bytes += attrs.size()
            pending += ImportItemEntity(
                sessionId = sessionId,
                sourceKey = keyFor(file),
                sourceUri = file.toString(),
                displayName = file.fileName.toString(),
                sizeBytes = attrs.size(),
                modifiedAt = attrs.lastModifiedTime().toMillis(),
                stage = ImportStage.DISCOVERED,
                updatedAt = now,
            )
            if (pending.size >= BATCH) flush()
        }
        flush()

        db.imports().session(sessionId)?.let {
            db.imports().upsertSession(it.copy(totalItems = files, bytesTotal = bytes, updatedAt = System.currentTimeMillis()))
        }
        val free = freeSpace()
        _status.value = ImportStatus(
            ImportStatus.Phase.AWAITING_CONFIRM, sessionId, label,
            total = files, bytesTotal = bytes,
        )
        Log.i(TAG, "import: listed $files files, $bytes bytes in $label ($unreadable unreadable)")
        Discovery(sessionId, files, bytes, free, unreadable)
    }

    /** Starts, or resumes, copying for a listed session. */
    fun start(sessionId: Long) {
        if (busy) return
        userCancelled = false
        job = scope.launch(Dispatchers.IO) {
            try {
                run(sessionId)
            } catch (e: CancellationException) {
                if (userCancelled) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        finish(sessionId, ImportState.CANCELLED, "cancelled")
                    }
                }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "import $sessionId failed", t)
                finish(sessionId, ImportState.FAILED, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** Abandons a listed session that the user decided not to run. */
    suspend fun discard(sessionId: Long) = withContext(Dispatchers.IO) {
        finish(sessionId, ImportState.CANCELLED, "not started")
    }

    /** Stops the running import for good. What is already copied stays in the library. */
    fun cancel() {
        userCancelled = true
        job?.cancel()
    }

    // ------------------------------------------------------------------ the run

    private suspend fun run(sessionId: Long) {
        val session = db.imports().session(sessionId) ?: return
        db.imports().upsertSession(session.copy(state = ImportState.RUNNING, lastError = null, updatedAt = now()))
        publishCounts(sessionId, ImportStatus.Phase.RUNNING, null)

        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = db.imports().resumable(sessionId, BATCH)
            if (batch.isEmpty()) break
            for (item in batch) {
                currentCoroutineContext().ensureActive()
                _status.update { it.copy(current = item.displayName) }
                when (process(item)) {
                    Step.PAUSE -> return
                    Step.NEXT -> Unit
                }
                bumpSession(sessionId, item.sizeBytes)
                publishCounts(sessionId, ImportStatus.Phase.RUNNING, null)
            }
        }
        finish(sessionId, ImportState.DONE, null)
    }

    private enum class Step { NEXT, PAUSE }

    private suspend fun process(item: ImportItemEntity): Step {
        // Resuming after a crash mid-copy: whatever was half written is discarded and the item
        // starts again. A commit that happened but was not indexed only needs indexing.
        item.tempRelPath?.let { runCatching { library.delete(it) } }
        if (item.stage == ImportStage.COMMITTED || item.stage == ImportStage.INDEXED) {
            val final = item.finalRelPath ?: return failed(item, "committed without a destination")
            // Rare (a crash between commit and index), so the indexer is left to re-hash it.
            return index(item, final, null)
        }

        val src = Path.of(item.sourceUri ?: return failed(item, "no source path"))
        if (!Files.isRegularFile(src)) return failed(item, "no longer there: $src")
        val size = Files.size(src)
        val mtime = Files.getLastModifiedTime(src).toMillis()
        val key = item.sourceKey

        // 1. Imported from this exact path before, and untouched since.
        db.fingerprints().find(SourceKind.IMPORT_PATH, key)?.let { fp ->
            if (fp.sizeBytes == size && kotlin.math.abs(fp.modifiedAt - mtime) <= MTIME_TOLERANCE_MS) {
                val asset = db.assets().byHash(fp.contentHash)
                if (asset != null) {
                    return save(item.copy(stage = ImportStage.PREFILTER_SKIPPED, contentHash = fp.contentHash, assetId = asset.id))
                }
            }
        }

        // 2. Already in the library under other names? Cheap test first, full hash only if needed.
        val head = Files.newInputStream(src).use { it.sha256Prefix(HEAD).sha256 }
        var known: DualHash? = null
        if (db.assets().byHeadHash(head, size).isNotEmpty()) {
            val full = Files.newInputStream(src).use { it.dualHash() }
            known = full
            db.assets().byHash(full.contentHash)?.let { existing ->
                remember(key, size, mtime, full.contentHash)
                return save(item.copy(stage = ImportStage.DUPLICATE, contentHash = full.contentHash, assetId = existing.id))
            }
        }

        // Room for this file, with a margin the rest of the system can live on.
        val free = freeSpace()
        if (free != null && free < size + RESERVE_BYTES) {
            val message = "Paused: the library drive has ${formatBytes(free)} free, and the next file " +
                "needs ${formatBytes(size)} plus ${formatBytes(RESERVE_BYTES)} to spare. Free some space, then press Resume."
            finish(item.sessionId, ImportState.PAUSED, message)
            return Step.PAUSE
        }

        // 3. Copy under a temporary name, hashing the bytes as they pass.
        val temp = joinRel(TEMP_DIR, "${UUID.randomUUID()}.part")
        save(item.copy(stage = ImportStage.COPYING, tempRelPath = temp))
        val copied = library.openWrite(temp).use { out ->
            val h = Files.newInputStream(src).use { it.copyHashing(out.output) }
            out.sync()
            h
        }
        if (copied.bytes != size) return failed(item, "read ${copied.bytes} bytes, expected $size", temp)
        if (known != null && known.contentHash != copied.sha256) {
            return failed(item, "the file changed while it was being copied", temp)
        }

        // 4. Verify what actually landed on disk.
        val verified = library.openRead(temp).use { it.inputStream().dualHash() }
        if (verified.contentHash != copied.sha256) return failed(item, "copy did not verify; the disk may be failing", temp)

        // Another import or a phone backup may have stored these bytes while this one copied.
        db.assets().byHash(verified.contentHash)?.let { existing ->
            runCatching { library.delete(temp) }
            remember(key, size, mtime, verified.contentHash)
            return save(item.copy(stage = ImportStage.DUPLICATE, contentHash = verified.contentHash, assetId = existing.id, tempRelPath = null))
        }

        // 5. Commit under its permanent name, dated like the source.
        val final = destinationFor(src, item.displayName, verified.contentHash, mtime)
        val finalPath = library.pathFor(final)
        Files.createDirectories(finalPath.parent)
        if (Files.exists(finalPath)) {
            val there = library.openRead(final).use { it.inputStream().dualHash() }
            if (there.contentHash != verified.contentHash) {
                return failed(item, "a different file already has the name $final", temp)
            }
            runCatching { library.delete(temp) }
        } else {
            Files.move(library.pathFor(temp), finalPath, StandardCopyOption.ATOMIC_MOVE)
        }
        runCatching { Files.setLastModifiedTime(finalPath, FileTime.fromMillis(mtime)) }
        save(item.copy(stage = ImportStage.COMMITTED, contentHash = verified.contentHash, tempRelPath = null, finalRelPath = final))
        remember(key, size, mtime, verified.contentHash)

        // 6. Index with the hash just verified.
        return index(item.copy(contentHash = verified.contentHash, finalRelPath = final), final, verified)
    }

    private suspend fun index(item: ImportItemEntity, final: String, hash: DualHash?): Step {
        val entry = library.stat(final) ?: return failed(item, "committed file is missing: $final")
        val album = Path.of(item.sourceUri ?: "").parent?.fileName?.toString()
        return when (val r = indexer.index(entry, force = true, sourceAlbum = album, knownHash = hash)) {
            is IndexResult.Indexed -> save(item.copy(stage = ImportStage.DONE, assetId = r.assetId, tempRelPath = null))
            is IndexResult.Duplicate -> save(item.copy(stage = ImportStage.DONE, assetId = r.assetId, tempRelPath = null))
            IndexResult.Skipped -> save(item.copy(stage = ImportStage.DONE, tempRelPath = null))
            is IndexResult.Failed -> failed(item.copy(stage = ImportStage.COMMITTED), "index: ${r.reason}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun save(item: ImportItemEntity): Step {
        db.imports().upsertItem(item.copy(updatedAt = now()))
        return Step.NEXT
    }

    private suspend fun failed(item: ImportItemEntity, reason: String, temp: String? = null): Step {
        temp?.let { runCatching { library.delete(it) } }
        Log.w(TAG, "import: ${item.sourceUri}: $reason")
        db.imports().upsertItem(
            item.copy(
                stage = ImportStage.FAILED,
                tempRelPath = null,
                attempts = item.attempts + 1,
                lastError = reason.take(300),
                updatedAt = now(),
            ),
        )
        return Step.NEXT
    }

    private suspend fun remember(key: String, size: Long, mtime: Long, hash: String) {
        db.fingerprints().upsert(
            SourceFingerprintEntity(
                id = db.fingerprints().find(SourceKind.IMPORT_PATH, key)?.id ?: 0,
                sourceKind = SourceKind.IMPORT_PATH,
                sourceKey = key,
                sizeBytes = size,
                modifiedAt = mtime,
                contentHash = hash,
                hashedAt = now(),
            ),
        )
    }

    private suspend fun bumpSession(sessionId: Long, bytes: Long) {
        val s = db.imports().session(sessionId) ?: return
        db.imports().upsertSession(s.copy(bytesDone = s.bytesDone + bytes, updatedAt = now()))
    }

    private suspend fun finish(sessionId: Long, state: Int, message: String?) {
        val s = db.imports().session(sessionId) ?: return
        val terminal = state == ImportState.DONE || state == ImportState.CANCELLED || state == ImportState.FAILED
        db.imports().upsertSession(
            s.copy(state = state, lastError = message, updatedAt = now(), finishedAt = if (terminal) now() else null),
        )
        // Anything half copied is cleared away; the stage machine redoes it on resume.
        runCatching { library.delete(TEMP_DIR) }
        val phase = when (state) {
            ImportState.DONE -> ImportStatus.Phase.DONE
            ImportState.PAUSED -> ImportStatus.Phase.PAUSED
            ImportState.FAILED -> ImportStatus.Phase.FAILED
            else -> ImportStatus.Phase.CANCELLED
        }
        publishCounts(sessionId, phase, message)
        Log.i(TAG, "import $sessionId -> $phase${message?.let { ": $it" } ?: ""}")
    }

    /** Counters come from the rows, not from memory, so they are right after a resume too. */
    private suspend fun publishCounts(sessionId: Long, phase: ImportStatus.Phase, message: String?) {
        val s = db.imports().session(sessionId) ?: return
        val counts = db.imports().stageCounts(sessionId).associate { it.stage to it.count }
        val copied = counts[ImportStage.DONE] ?: 0
        val dup = counts[ImportStage.DUPLICATE] ?: 0
        val unchanged = counts[ImportStage.PREFILTER_SKIPPED] ?: 0
        val failed = counts[ImportStage.FAILED] ?: 0
        _status.update {
            it.copy(
                phase = phase,
                sessionId = sessionId,
                total = s.totalItems,
                done = copied + dup + unchanged + failed,
                copied = copied,
                duplicates = dup,
                unchanged = unchanged,
                failed = failed,
                bytesTotal = s.bytesTotal,
                bytesDone = s.bytesDone,
                current = if (phase == ImportStatus.Phase.RUNNING) it.current else null,
                message = message,
            )
        }
    }

    /** The failures of a session, newest first, for the window to list. */
    suspend fun failures(sessionId: Long): List<ImportItemEntity> =
        withContext(Dispatchers.IO) { db.imports().failures(sessionId, 200) }

    /**
     * `yyyy/MM/<name>-<hash8>.<ext>`, the layout phone uploads use. The month is the capture month in
     * the photo's own time zone when it has one, otherwise this PC's; the source's mtime stands in
     * when the file carries no date at all.
     */
    private fun destinationFor(src: Path, displayName: String, hash: String, mtime: Long): String {
        val meta = runCatching {
            FolderStore(src.parent.toFile()).let { store ->
                probe.metadata(store, src.fileName.toString(), Mime.forName(displayName), mtime, displayName)
            }
        }.getOrNull()
        val at = meta?.takeIf { it.capturedAtSource != CaptureSource.UNKNOWN }?.capturedAt ?: mtime
        val zone: ZoneId = meta?.tzOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
        val folder = DateTimeFormatter.ofPattern("yyyy/MM").format(Instant.ofEpochMilli(at).atZone(zone))
        val name = sanitize(displayName)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return joinRel(folder, "$stem-${hash.take(8)}$ext")
    }

    /** Same rules as UploadService: names that are legal on every filesystem the library may sit on. */
    private fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { "import" }
        return if (cleaned.toByteArray().size > 200) cleaned.take(160) else cleaned
    }

    private fun keyFor(file: Path) = "import:${file.toAbsolutePath().normalize()}"

    private fun now() = System.currentTimeMillis()

    companion object {
        const val BATCH = 200
        const val HEAD = 64 * 1024L
        const val TEMP_DIR = ".gpic-tmp"
        private const val MTIME_TOLERANCE_MS = 2_000L

        /** Never fill the library drive completely: Windows and the database both need room. */
        const val RESERVE_BYTES = 1L shl 30

        /**
         * The phone's scanner's list (index/LibraryIndexer.kt, StoreScanner), copied because it is
         * private there.
         */
        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp",
            "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw",
            "mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi",
        )

        fun isMedia(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS

        /** `*.jpg`-style patterns for a file dialog's "Photos and videos" filter. */
        val MEDIA_PATTERNS: List<String> get() = MEDIA_EXTENSIONS.map { "*.$it" }

        /** Folders that are never someone's photos: system, recycle bin, and hidden ones. */
        private fun skipDirectory(name: String): Boolean =
            name.startsWith(".") || name.startsWith("$") || name.equals("System Volume Information", ignoreCase = true)

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 40 -> "%.1f TB".format(bytes / 1099511627776.0)
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }
}
