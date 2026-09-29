package io.github.akash904.photohost.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Originals on a USB-OTG volume, reached through the Storage Access Framework.
 *
 * SAF is the only way an unprivileged app can touch a portable volume: the real mountpoint under
 * `/mnt/media_rw/...` needs the WRITE_MEDIA_STORAGE signature permission, which is why Termux
 * cannot see the drive at all.
 *
 * ### The resolution ladder
 *
 * SAF identifies files by opaque document ids, and the tree URI is not guaranteed to survive the
 * drive being unplugged and replugged. So the durable key is (volume serial, relative path), and
 * a document id is *derived* on demand:
 *
 *  1. **Deterministic reconstruction.** ExternalStorageProvider document ids are literally
 *     `"<volumeSerial>:<pathFromVolumeRoot>"`, so the id can be rebuilt from strings alone. This
 *     means a brand-new tree grant after a replug resolves every asset with zero directory
 *     walking. M0 probe 4 proves this holds on the target device.
 *  2. **Segment walk.** Case-insensitive name matching down from the tree root, for providers
 *     whose ids really are opaque. Correct but slow, so it is the fallback, never the norm.
 *
 * The production version adds a cached-URI fast path in front (from `asset_files`) and a
 * reconcile-by-hash step behind; neither needs the database that M0 does not have yet.
 *
 * ### Why not DocumentFile
 *
 * Every `DocumentFile` accessor is its own Binder round-trip, which is the documented SAF
 * performance cliff. All listing here is a single [ContentResolver.query] with an explicit
 * projection. M0 probe 6 measures the difference.
 */
class SafStore(
    private val context: Context,
    val treeUri: Uri,
) : LibraryStore {

    private val resolver: ContentResolver = context.contentResolver

    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** The exFAT volume serial, e.g. "1234-5678". Stable across replug; the tree URI may not be. */
    val volumeKey: String = treeDocId.substringBefore(':')

    /** Where the granted tree sits relative to the volume root -- often "" but not always. */
    private val rootVolumePath: String = treeDocId.substringAfter(':', "")

    override val kind = StoreKind.SAF
    override val label: String get() = "USB $volumeKey"
    override val isWritable: Boolean
        get() = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    override val isMounted: Boolean
        get() = try {
            documentExists(treeDocId)
        } catch (t: Throwable) {
            false
        }

    // ------------------------------------------------------------------ the ladder

    /** Path from the VOLUME root, which is what a document id encodes after the colon. */
    private fun volumePathOf(relPath: String): String = joinRel(rootVolumePath, relPath)

    /** Ladder step 1: the id this file *should* have, built from strings alone. */
    fun deterministicDocumentId(relPath: String): String = "$volumeKey:${volumePathOf(relPath)}"

    /** Ladder step 2: walk down from the tree root, matching names case-insensitively (exFAT). */
    private fun walkToDocumentId(relPath: String): String? {
        var current = treeDocId
        for (seg in relSegments(relPath)) {
            val hit = listChildren(current).firstOrNull { it.displayName.equals(seg, ignoreCase = true) }
                ?: return null
            current = hit.documentId
        }
        return current
    }

    /** Resolves a relative path to a document id, or null when it genuinely is not there. */
    fun resolveDocumentId(relPath: String): String? {
        // The deterministic id is built from the string, so "../x" would become a document id
        // outside the granted tree. Every read, list, stat, delete and write resolves through here.
        requireConfined(relPath)
        if (relSegments(relPath).isEmpty()) return treeDocId
        val direct = deterministicDocumentId(relPath)
        if (documentExists(direct)) return direct
        return walkToDocumentId(relPath)
    }

    fun uriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun documentExists(documentId: String): Boolean = try {
        resolver.query(uriFor(documentId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ listing

    private fun listChildren(parentDocumentId: String): List<SafRow> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val out = ArrayList<SafRow>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val iMod = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getString(iId) ?: continue
                out += SafRow(
                    documentId = id,
                    displayName = if (c.isNull(iName)) "" else c.getString(iName),
                    mime = if (c.isNull(iMime)) "" else c.getString(iMime),
                    size = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                    lastModified = if (c.isNull(iMod)) 0L else c.getLong(iMod),
                )
            }
        }
        return out
    }

    /** Exposed for M0 probe 6, which benchmarks this against DocumentFile.listFiles(). */
    fun listChildrenOfRel(relPath: String): List<SafRow> {
        val docId = resolveDocumentId(relPath) ?: return emptyList()
        return listChildren(docId)
    }

    override fun list(relPath: String): List<StoreEntry> =
        listChildrenOfRel(relPath).map { r ->
            StoreEntry(
                relPath = joinRel(relPath, r.displayName),
                name = r.displayName,
                isDirectory = r.isDirectory,
                size = r.size,
                lastModified = r.lastModified,
                mime = r.mime,
            )
        }

    override fun stat(relPath: String): StoreEntry? {
        val docId = resolveDocumentId(relPath) ?: return null
        return resolver.query(uriFor(docId), PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) ?: ""
            val mime = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)) ?: ""
            val iSize = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val iMod = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
            StoreEntry(
                relPath = relPath.trim('/'),
                name = name,
                isDirectory = mime == Document.MIME_TYPE_DIR,
                size = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                lastModified = if (c.isNull(iMod)) 0L else c.getLong(iMod),
                mime = mime,
            )
        }
    }

    // ------------------------------------------------------------------ bytes

    override fun openRead(relPath: String): ParcelFileDescriptor {
        val docId = resolveDocumentId(relPath) ?: throw FileNotFoundException("unresolved: $relPath")
        return resolver.openFileDescriptor(uriFor(docId), "r")
            ?: throw IOException("openFileDescriptor returned null for $relPath")
    }

    override fun openWrite(relPath: String, mime: String): ParcelFileDescriptor {
        val existing = resolveDocumentId(relPath)
        val docId = existing ?: createFile(relPath, mime)
        // "rwt" truncates; plain "w" is not guaranteed to on every provider.
        return resolver.openFileDescriptor(uriFor(docId), "rwt")
            ?: throw IOException("openFileDescriptor returned null for $relPath")
    }

    private fun createFile(relPath: String, mime: String): String {
        val segs = relSegments(requireConfined(relPath))
        require(segs.isNotEmpty()) { "cannot create the store root as a file" }
        val name = segs.last()
        val parentRel = segs.dropLast(1).joinToString("/")
        mkdirs(parentRel)
        val parentId = resolveDocumentId(parentRel)
            ?: throw FileNotFoundException("parent unresolved: $parentRel")
        val created = DocumentsContract.createDocument(resolver, uriFor(parentId), mime, name)
            ?: throw IOException("createDocument returned null for $relPath")
        val createdId = DocumentsContract.getDocumentId(created)
        // SAF may rename on create -- appending an extension for the mime type, or a " (1)" suffix
        // on collision. Silently accepting that would desynchronise relPath from the real file, so
        // it is surfaced rather than swallowed. Production stores the name SAF actually used.
        val actual = createdId.substringAfterLast('/')
        if (!actual.equals(name, ignoreCase = true)) {
            throw IOException("SAF renamed '$name' to '$actual' on create; relPath mapping would drift")
        }
        return createdId
    }

    override fun mkdirs(relPath: String) {
        var currentRel = ""
        for (seg in relSegments(requireConfined(relPath))) {
            val next = joinRel(currentRel, seg)
            if (resolveDocumentId(next) == null) {
                val parentId = resolveDocumentId(currentRel)
                    ?: throw FileNotFoundException("parent unresolved: $currentRel")
                DocumentsContract.createDocument(
                    resolver, uriFor(parentId), Document.MIME_TYPE_DIR, seg,
                ) ?: throw IOException("could not create directory $next")
            }
            currentRel = next
        }
    }

    override fun delete(relPath: String): Boolean {
        val docId = resolveDocumentId(relPath) ?: return false
        return try {
            DocumentsContract.deleteDocument(resolver, uriFor(docId))
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * SAF exposes no volume-level capacity call, so this stats an open descriptor. Any file on the
     * volume will do; if there is not one, a scratch file is made and removed.
     */
    override fun capacity(): Capacity? {
        val existing = firstFileRel()
        val scratch = if (existing == null) ".photohost-capacity-probe" else null
        val rel = existing ?: scratch!!
        try {
            if (scratch != null) openWrite(rel).use { }
            openRead(rel).use { pfd ->
                val vfs = Os.fstatvfs(pfd.fileDescriptor)
                return Capacity(vfs.f_bavail * vfs.f_frsize, vfs.f_blocks * vfs.f_frsize)
            }
        } catch (t: Throwable) {
            return null
        } finally {
            if (scratch != null) runCatching { delete(scratch) }
        }
    }

    private fun firstFileRel(): String? =
        list("").firstOrNull { !it.isDirectory }?.relPath

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** A raw row from a children query. */
data class SafRow(
    val documentId: String,
    val displayName: String,
    val mime: String,
    val size: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
    val volumePath: String get() = documentId.substringAfter(':', "")
}
