package io.github.akash904.photohost.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.akash904.photohost.core.dualHash
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.data.entity.AssetFileEntity
import io.github.akash904.photohost.data.entity.SourceFingerprintEntity
import io.github.akash904.photohost.data.entity.SourceKind
import io.github.akash904.photohost.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "photohost"

/**
 * Moves a library's files from one store to another, one file at a time.
 *
 * ### Why one at a time
 *
 * App storage and a folder on the phone are the same partition. Copying everything first and
 * deleting afterwards would need twice the library's size free, which a phone holding a large
 * library does not have. So each file is copied, verified, repointed and only then deleted, and the
 * extra space needed is one file.
 *
 * ### Why it can be interrupted safely
 *
 * Until the last file is done the library is split between the two places, and the server finds a
 * file by its path in whichever store is current -- so a half-moved library must never be served.
 * The destination is recorded in [io.github.akash904.photohost.core.Prefs.moveTarget] before the
 * first file moves, the server refuses to start while it is set, and running the move again simply
 * carries on: a file already at the destination with the right hash is recognised and skipped.
 *
 * Every copy is read back and hashed before the original is deleted. A file whose bytes no longer
 * match the hash the library recorded is still moved -- refusing would strand the whole library
 * over one damaged photo -- but it is counted and logged.
 */
class LibraryMover(
    private val db: AppDatabase,
    private val from: LibraryStore,
    private val fromVolumeId: Long,
    private val to: LibraryStore,
    private val toVolumeId: Long,
) {
    data class Outcome(
        val moved: Int,
        val missing: Int,
        val damaged: Int,
        val failed: Int,
        /** Set when the move could not start at all; nothing was touched. */
        val refusal: String? = null,
    )

    suspend fun run(onProgress: (done: Int, total: Int) -> Unit): Outcome {
        val rows = db.assetFiles().onVolume(fromVolumeId)

        if (!samePartition()) {
            val need = rows.sumOf { it.sizeBytes }
            val free = runCatching { to.capacity()?.availableBytes }.getOrNull()
            if (free != null && free < need + SPACE_MARGIN) {
                return Outcome(0, 0, 0, 0, refusal = "Not enough space there: the library needs " +
                    "${need / MB} MB and ${free / MB} MB is free.")
            }
        }

        var moved = 0
        var missing = 0
        var damaged = 0
        var failed = 0
        rows.forEachIndexed { i, row ->
            onProgress(i, rows.size)
            try {
                when (moveOne(row)) {
                    Result.MOVED -> moved++
                    Result.MISSING -> missing++
                    Result.DAMAGED -> { moved++; damaged++ }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "move: ${row.relPath}: ${t.javaClass.simpleName}: ${t.message}")
                failed++
            }
        }
        onProgress(rows.size, rows.size)
        if (failed == 0) runCatching { removeEmptyDirs("") }
        return Outcome(moved, missing, damaged, failed)
    }

    private enum class Result { MOVED, MISSING, DAMAGED }

    private suspend fun moveOne(row: AssetFileEntity): Result {
        val asset = db.assets().byId(row.assetId) ?: return Result.MISSING

        // Where it goes: the same path, unless something else already occupies it there.
        var dest = row.relPath
        var alreadyThere = false
        var n = 1
        while (true) {
            val st = to.stat(dest)
            val clash = db.assetFiles().byPath(toVolumeId, dest)?.takeIf { it.id != row.id }
            if (st == null && clash == null) break
            if (st != null && (clash == null || clash.assetId == row.assetId) &&
                st.size == asset.byteSize && hashOf(to, dest) == asset.contentHash
            ) {
                alreadyThere = true
                break
            }
            n++
            dest = numbered(row.relPath, n)
        }

        var result = Result.MOVED
        if (!alreadyThere) {
            if (from.stat(row.relPath) == null) return Result.MISSING
            // A generic type, not the asset's: given "image/heic" for a ".heif" name, SAF appends
            // the extension it thinks belongs, and the file would no longer be where the row says.
            val sourceHash = copy(row.relPath, dest, "application/octet-stream")
            val copyHash = hashOf(to, dest)
            if (copyHash != sourceHash) {
                runCatching { to.delete(dest) }
                throw java.io.IOException("copy did not read back the same")
            }
            if (sourceHash != asset.contentHash) {
                Log.w(TAG, "move: ${row.relPath} no longer matches its recorded hash; moved as-is")
                result = Result.DAMAGED
            }
        }

        val st = to.stat(dest) ?: throw java.io.IOException("copy vanished")
        val now = System.currentTimeMillis()
        val clash = db.assetFiles().byPath(toVolumeId, dest)?.takeIf { it.id != row.id }
        if (clash != null) {
            // Another row already records this asset at the destination; this one is redundant.
            db.assetFiles().delete(row.id)
        } else {
            db.assetFiles().upsert(
                row.copy(
                    volumeId = toVolumeId,
                    relPath = dest,
                    displayName = dest.substringAfterLast('/'),
                    lastDocumentId = null,
                    sizeBytes = st.size,
                    modifiedAt = st.lastModified,
                    verifiedAt = now,
                    missingSince = null,
                ),
            )
        }
        moveFingerprint(row.relPath, dest, st, asset.contentHash, now)

        // Only now, with the copy verified and the library pointing at it, does the original go.
        if (from.stat(row.relPath) != null) from.delete(row.relPath)
        return result
    }

    /** Copies bytes across, flushed to disk, and returns the SHA-256 of what was read. */
    private fun copy(srcRel: String, destRel: String, mime: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        from.openRead(srcRel).use { src ->
            to.openWrite(destRel, mime).use { dst ->
                val input = FileInputStream(src.fileDescriptor)
                val output = FileOutputStream(dst.fileDescriptor)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    digest.update(buf, 0, r)
                    output.write(buf, 0, r)
                }
                output.flush()
                // Durable before the original is deleted, not merely handed to the page cache.
                dst.fileDescriptor.sync()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashOf(store: LibraryStore, rel: String): String =
        store.openRead(rel).use { FileInputStream(it.fileDescriptor).dualHash().contentHash }

    /**
     * Carries the "already hashed" record across, so the first start after a move does not re-read
     * the entire library to rediscover what it just verified.
     */
    private suspend fun moveFingerprint(oldRel: String, newRel: String, st: StoreEntry, hash: String, now: Long) {
        val oldKey = "vol:$fromVolumeId:$oldRel"
        val newKey = "vol:$toVolumeId:$newRel"
        if (oldKey != newKey) db.fingerprints().forget(SourceKind.STORE_PATH, oldKey)
        db.fingerprints().upsert(
            SourceFingerprintEntity(
                id = db.fingerprints().find(SourceKind.STORE_PATH, newKey)?.id ?: 0,
                sourceKind = SourceKind.STORE_PATH,
                sourceKey = newKey,
                sizeBytes = st.size,
                modifiedAt = st.lastModified,
                contentHash = hash,
                hashedAt = now,
            ),
        )
    }

    /** Leaves the old location tidy: the dated folders the library made there, now empty. */
    private fun removeEmptyDirs(rel: String) {
        for (child in from.list(rel)) {
            if (!child.isDirectory) continue
            removeEmptyDirs(child.relPath)
            if (from.list(child.relPath).isEmpty()) from.delete(child.relPath)
        }
    }

    /** App storage and a folder on the phone's own storage share a partition; a USB drive does not. */
    private fun samePartition(): Boolean {
        fun onPhone(s: LibraryStore) = s is InternalStore || (s as? SafStore)?.volumeKey == "primary"
        return onPhone(from) && onPhone(to)
    }

    private companion object {
        const val MB = 1024L * 1024
        const val SPACE_MARGIN = 200 * MB

        /** "2026/09/IMG_1.jpg", 2 -> "2026/09/IMG_1 (2).jpg" */
        fun numbered(rel: String, n: Int): String {
            val dir = rel.substringBeforeLast('/', "")
            val name = rel.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            val numberedName = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
            return joinRel(dir, numberedName)
        }
    }
}

/** What the Storage card shows while a move runs. */
object MoveState {
    data class Snapshot(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** The result of the last run, for the card to show once it stops. */
        val message: String? = null,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(f: (Snapshot) -> Snapshot) = _state.update(f)
}

/**
 * Runs a move as foreground work, so it survives the screen going off and the app being left.
 * Reads its destination from preferences rather than its input, which is what makes a resumed run
 * after a crash identical to the first one.
 */
class LibraryMoveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val container = AppContainer.get(applicationContext)
        val prefs = container.prefs
        val (backend, tree) = prefs.moveTarget ?: return androidx.work.ListenableWorker.Result.success()

        runCatching { setForeground(foregroundInfo()) }
        MoveState.update { MoveState.Snapshot(running = true) }

        val outcome = try {
            val from = container.buildStore()
            val to = container.storeFor(backend, tree)
            if (sameLocation(from, to)) {
                LibraryMover.Outcome(0, 0, 0, 0)
            } else {
                LibraryMover(
                    container.db,
                    from, container.ensureVolume(from),
                    to, container.ensureVolume(to),
                ).run { done, total -> MoveState.update { it.copy(done = done, total = total) } }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "library move failed", t)
            LibraryMover.Outcome(0, 0, 0, 1, refusal = "The move stopped: ${t.message}")
        }

        val message: String
        val failed: Boolean
        when {
            outcome.refusal != null && outcome.moved == 0 && outcome.failed == 0 -> {
                // Refused before touching anything, so there is nothing to finish: drop the target.
                prefs.cancelMove()
                message = outcome.refusal
                failed = true
            }
            outcome.failed > 0 || outcome.refusal != null -> {
                message = outcome.refusal ?: ("${outcome.failed} could not be moved. Nothing has " +
                    "been switched yet; try again to finish.")
                failed = true
            }
            else -> {
                prefs.finishMove()
                message = buildString {
                    append("Moved ${outcome.moved} files.")
                    if (outcome.missing > 0) append(" ${outcome.missing} were already missing.")
                    if (outcome.damaged > 0) append(" ${outcome.damaged} did not match their recorded contents; moved as they are.")
                }
                failed = false
            }
        }
        MoveState.update { it.copy(running = false, message = message, failed = failed) }
        return androidx.work.ListenableWorker.Result.success()
    }

    private fun sameLocation(a: LibraryStore, b: LibraryStore): Boolean = when {
        a is InternalStore && b is InternalStore -> true
        a is SafStore && b is SafStore -> a.treeUri == b.treeUri
        else -> false
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Library move", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL)
            .setContentTitle("PhotoHost")
            .setContentText("Moving the library…")
            .setSmallIcon(io.github.akash904.photohost.R.drawable.ic_stat_photohost)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "library-move"
        private const val NOTIF_ID = 3
        private const val WORK = "photohost-library-move"

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryMoveWorker>().build(),
            )
        }
    }
}
