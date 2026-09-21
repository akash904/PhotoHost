package dev.gpicalter.storage

import android.os.ParcelFileDescriptor

/**
 * Where originals live. Two implementations: [InternalStore] (a plain directory on the phone) and
 * [SafStore] (a USB-OTG volume reached through the Storage Access Framework).
 *
 * The interface speaks **relative paths**, never document URIs or absolute paths. That is not a
 * convenience -- it is the same durable identity the database uses (`asset_files.rel_path`), and
 * it is what lets SAF hide its resolution ladder behind the same calls the internal store answers
 * with a `File` join. Everything above this layer -- Range serving, thumbnail decoding, the import
 * stage machine -- is written once and works on both.
 *
 * Both implementations hand back a **seekable** [ParcelFileDescriptor], because HTTP Range serving
 * and two-pass bitmap decoding both need to seek. Whether SAF genuinely delivers that is what M0
 * probe 2 exists to find out.
 *
 * Implementations are blocking. Callers run them on an IO dispatcher.
 */
interface LibraryStore {

    val kind: StoreKind

    /** Human label for the UI, e.g. "Internal storage" or "USB 1234-5678". */
    val label: String

    /** False when the volume is gone (drive unplugged). Jobs go BLOCKED, not FAILED. */
    val isMounted: Boolean

    val isWritable: Boolean

    /** Lists one directory. [relPath] is "" for the store root. */
    fun list(relPath: String): List<StoreEntry>

    /** Null when absent. */
    fun stat(relPath: String): StoreEntry?

    /** Opens for reading. Caller closes. */
    fun openRead(relPath: String): ParcelFileDescriptor

    /** Opens for writing, creating the file and any missing parents. Truncates. Caller closes. */
    fun openWrite(relPath: String, mime: String = "application/octet-stream"): ParcelFileDescriptor

    fun mkdirs(relPath: String)

    fun delete(relPath: String): Boolean

    /** (available, total) in bytes, or null when the store cannot report it. */
    fun capacity(): Capacity?
}

enum class StoreKind { INTERNAL, SAF }

data class StoreEntry(
    /** Path relative to the store root, '/'-separated, no leading slash. */
    val relPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mime: String,
)

data class Capacity(val availableBytes: Long, val totalBytes: Long)

/** Joins path segments the way every store expects: '/'-separated, no leading or trailing slash. */
fun joinRel(parent: String, child: String): String {
    val p = parent.trim('/')
    val c = child.trim('/')
    return when {
        p.isEmpty() -> c
        c.isEmpty() -> p
        else -> "$p/$c"
    }
}

fun relSegments(relPath: String): List<String> =
    relPath.split('/').filter { it.isNotEmpty() }
