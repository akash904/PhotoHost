package io.github.akash904.photohost.storage

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Originals in an ordinary directory on a PC -- the desktop's only store.
 *
 * The library owns this folder: imports and uploads are copied in, and purge deletes for real,
 * exactly as on the phone. Nothing outside it is ever touched.
 *
 * ### Paths are confined to the root
 * Relative paths reach this class from HTTP (`/api/v1/fs/read?path=`), so every one is checked to
 * stay inside [root]. `..`, absolute paths, drive letters and backslashes are refused outright, and
 * so is any `:` -- on NTFS `photo.jpg:hidden` names an alternate data stream, not a file.
 * The phone's InternalStore does not check this: it joins with `File(root, relPath)` unchecked, so
 * an authenticated client can read outside the library there.
 */
class FolderStore(rootDir: File) : LibraryStore {

    val root: Path = rootDir.toPath().toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override val kind = StoreKind.FOLDER
    override val label: String get() = root.toString()
    override val isMounted: Boolean get() = Files.isDirectory(root)
    override val isWritable: Boolean get() = Files.isWritable(root)

    /**
     * A stable identity for this library, stored inside it.
     *
     * The phone keys a volume on the exFAT serial; a PC folder has nothing equivalent, and its path
     * is not stable either -- a USB disk comes back as E: one day and F: the next. A random id
     * written into the folder travels with it, which is the property the `volumes` table needs.
     */
    fun volumeKey(): String {
        val marker = root.resolve(ID_FILE)
        if (Files.isRegularFile(marker)) {
            Files.readString(marker).trim().takeIf { it.isNotEmpty() }?.let { return "folder:$it" }
        }
        val fresh = UUID.randomUUID().toString()
        Files.writeString(marker, fresh)
        runCatching { Files.setAttribute(marker, "dos:hidden", true) }
        return "folder:$fresh"
    }

    fun pathFor(relPath: String): Path {
        val segments = relSegments(relPath.replace('\\', '/'))
        for (seg in segments) {
            if (seg == "." || seg == ".." || seg.contains(':') || seg.contains('\\')) {
                throw FileNotFoundException("refused path: $relPath")
            }
        }
        val resolved = segments.fold(root) { acc, seg -> acc.resolve(seg) }.normalize()
        if (!resolved.startsWith(root)) throw FileNotFoundException("refused path: $relPath")
        return resolved
    }

    override fun list(relPath: String): List<StoreEntry> {
        val dir = pathFor(relPath)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { p ->
                val name = p.fileName.toString()
                if (name == ID_FILE) return@mapNotNull null
                runCatching { entryOf(joinRel(relPath, name), p) }.getOrNull()
            }
        }
    }

    override fun stat(relPath: String): StoreEntry? {
        val p = pathFor(relPath)
        return if (Files.exists(p)) entryOf(relPath.trim('/'), p) else null
    }

    override fun openRead(relPath: String): ReadHandle {
        val p = pathFor(relPath)
        if (!Files.isRegularFile(p)) throw FileNotFoundException("no such file: $relPath")
        return ChannelReadHandle(FileChannel.open(p, StandardOpenOption.READ))
    }

    override fun openWrite(relPath: String, mime: String): WriteHandle {
        val p = pathFor(relPath)
        p.parent?.let { Files.createDirectories(it) }
        val channel = FileChannel.open(
            p,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        return ChannelWriteHandle(channel)
    }

    override fun mkdirs(relPath: String) {
        Files.createDirectories(pathFor(relPath))
    }

    override fun delete(relPath: String): Boolean {
        val p = pathFor(relPath)
        if (p == root) return false
        return p.toFile().deleteRecursively()
    }

    override fun capacity(): Capacity? = runCatching {
        val fs = Files.getFileStore(root)
        Capacity(fs.usableSpace, fs.totalSpace)
    }.getOrNull()

    private fun entryOf(relPath: String, p: Path): StoreEntry {
        val dir = Files.isDirectory(p)
        return StoreEntry(
            relPath = relPath,
            name = p.fileName?.toString() ?: "",
            isDirectory = dir,
            size = if (dir) 0L else Files.size(p),
            lastModified = Files.getLastModifiedTime(p).toMillis(),
            mime = if (dir) MIME_DIR else Mime.forName(p.fileName.toString()),
        )
    }

    companion object {
        const val MIME_DIR = "inode/directory"

        /** Starts with ".photohost" so the scanner's scratch-name rule already leaves it alone. */
        const val ID_FILE = ".photohost-library-id"
    }
}

/**
 * Positional reads over one channel. Each stream carries its own offset and uses
 * `read(dst, position)`, so two streams over the same handle cannot disturb each other.
 */
private class ChannelReadHandle(private val channel: FileChannel) : ReadHandle {

    override val size: Long get() = channel.size()

    override fun inputStream(position: Long): InputStream = object : InputStream() {
        private var pos = position

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = channel.read(ByteBuffer.wrap(b, off, len), pos)
            if (n > 0) pos += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val step = minOf(n, (channel.size() - pos).coerceAtLeast(0))
            pos += step
            return step
        }

        override fun available(): Int = (channel.size() - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        // Deliberately does not close the channel: the handle owns it.
        override fun close() {}
    }

    override fun close() = channel.close()
}

private class ChannelWriteHandle(private val channel: FileChannel) : WriteHandle {

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buf = ByteBuffer.wrap(b, off, len)
            while (buf.hasRemaining()) channel.write(buf)
        }

        // The handle owns the channel; closing the stream only ends this writer's use of it.
        override fun close() {}
    }

    override fun sync() = channel.force(true)

    override fun close() = channel.close()
}
