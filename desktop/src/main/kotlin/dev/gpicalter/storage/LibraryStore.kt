package dev.gpicalter.storage

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Where originals live.
 *
 * DIVERGES FROM gpicAlter: the phone's version hands back `android.os.ParcelFileDescriptor` from
 * [openRead] and [openWrite]. This is the platform-neutral replacement proposed for the phone as
 * well, being proved here first. Everything else about the contract is unchanged:
 *
 * The interface speaks **relative paths**, never absolute ones. That is the same durable identity
 * the database uses (`asset_files.rel_path`), so Range serving, thumbnail decoding and the indexer
 * are written once and work on every store.
 *
 * Implementations are blocking. Callers run them on an IO dispatcher.
 */
interface LibraryStore {

    val kind: StoreKind

    /** Human label for the UI, e.g. "D:\Photos". */
    val label: String

    /** False when the volume is gone (drive unplugged). Jobs go BLOCKED, not FAILED. */
    val isMounted: Boolean

    val isWritable: Boolean

    /** Lists one directory. [relPath] is "" for the store root. */
    fun list(relPath: String): List<StoreEntry>

    /** Null when absent. */
    fun stat(relPath: String): StoreEntry?

    /** Opens for reading. Caller closes. */
    fun openRead(relPath: String): ReadHandle

    /** Opens for writing, creating the file and any missing parents. Truncates. Caller closes. */
    fun openWrite(relPath: String, mime: String = "application/octet-stream"): WriteHandle

    fun mkdirs(relPath: String)

    fun delete(relPath: String): Boolean

    /** (available, total) in bytes, or null when the store cannot report it. */
    fun capacity(): Capacity?
}

/**
 * An open, seekable file.
 *
 * Seeking is expressed as "a stream starting here" rather than a shared cursor, because that is
 * all any caller actually does -- Range serving starts at the range, decoders start at zero -- and
 * a positional stream cannot be left at the wrong offset by the previous reader, which is the bug
 * the phone's `lseek`-then-read pattern invites.
 */
interface ReadHandle : Closeable {
    val size: Long

    /** Reads from [position]. Closing the stream does not close the handle. */
    fun inputStream(position: Long = 0): InputStream
}

interface WriteHandle : Closeable {
    val output: OutputStream

    /**
     * Forces the bytes to the device. An upload's staging copy is deleted only after this returns,
     * so a power cut can never leave a client told "stored" with nothing actually stored.
     */
    fun sync()
}

/** INTERNAL and SAF are the phone's; FOLDER is a plain directory on a PC. */
enum class StoreKind { INTERNAL, SAF, FOLDER }

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
