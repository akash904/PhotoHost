package io.github.akash904.photohost.storage

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Originals on the phone itself.
 *
 * Rooted at `getExternalFilesDir(null)/library`, which is app-private (no runtime permission
 * needed) but lives on the shared volume, so it is visible over MTP for debugging and has the
 * whole user partition available rather than a small app-data quota.
 *
 * **Caveat worth knowing:** app-private directories are deleted when the app is uninstalled. That
 * is fine for development and for the M0/M2 milestones, but an internal-storage library is never
 * a place to keep the only copy of anything -- which is equally true of the USB drive (plan,
 * Risk 5).
 *
 * Unlike [SafStore] there is no resolution ladder here: a relative path is just a [File] join, and
 * everything is a real POSIX file with a real seekable descriptor. That makes this store the
 * useful control case -- if something works here and fails on SAF, SAF is the cause.
 */
class InternalStore(context: Context) : LibraryStore {

    val root: File = File(context.getExternalFilesDir(null), "library").apply { mkdirs() }

    override val kind = StoreKind.INTERNAL
    override val label: String get() = "Internal storage"
    override val location: String get() = "App storage"
    override val isMounted: Boolean get() = root.isDirectory
    override val isWritable: Boolean get() = root.canWrite()

    /**
     * Confined to [root] twice over: `..` segments are refused outright, and the canonical result
     * must still sit under the canonical root, which also catches a symlink placed inside the library
     * that points out of it.
     */
    private fun fileFor(relPath: String): File {
        if (relPath.isBlank()) return root
        val f = File(root, requireConfined(relPath).trim('/'))
        val rootPath = root.canonicalPath
        val path = f.canonicalPath
        if (path != rootPath && !path.startsWith(rootPath + File.separator)) {
            throw FileNotFoundException("refused path: $relPath")
        }
        return f
    }

    override fun list(relPath: String): List<StoreEntry> {
        val dir = fileFor(relPath)
        val kids = dir.listFiles() ?: return emptyList()
        return kids.map { f -> entryOf(joinRel(relPath, f.name), f) }
    }

    override fun stat(relPath: String): StoreEntry? {
        val f = fileFor(relPath)
        return if (f.exists()) entryOf(relPath.trim('/'), f) else null
    }

    override fun openRead(relPath: String): ParcelFileDescriptor {
        val f = fileFor(relPath)
        if (!f.isFile) throw FileNotFoundException("no such file: $relPath")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openWrite(relPath: String, mime: String): ParcelFileDescriptor {
        val f = fileFor(relPath)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(
            f,
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun mkdirs(relPath: String) {
        fileFor(relPath).mkdirs()
    }

    override fun delete(relPath: String): Boolean = fileFor(relPath).deleteRecursively()

    override fun capacity(): Capacity {
        val fs = StatFs(root.absolutePath)
        return Capacity(fs.availableBytes, fs.totalBytes)
    }

    private fun entryOf(relPath: String, f: File) = StoreEntry(
        relPath = relPath,
        name = f.name,
        isDirectory = f.isDirectory,
        size = if (f.isDirectory) 0L else f.length(),
        lastModified = f.lastModified(),
        mime = if (f.isDirectory) MIME_DIR else guessMime(f.name),
    )

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val MIME_DIR = "vnd.android.document/directory"
    }
}
