package io.github.akash904.photohost.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.akash904.photohost.net.LibraryApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handing library photos to another app through Android's share sheet: one from the viewer, or a
 * selection from the grid.
 *
 * The originals live in a library that may be on another device, reached over pinned TLS with a
 * token, so no other app can open them where they are. Copies are fetched into cache/shared/ and
 * lent through a FileProvider, which grants the receiving app read access to those files for the
 * duration of the share and nothing more.
 *
 * Only the latest share is kept. The receiving app may still be reading after the share sheet
 * closes, so the files cannot be deleted then; they are cleared at the start of the next share
 * instead, and Android may clear the cache on its own at any time.
 */
object Share {

    data class SharedFile(val file: File, val mime: String)

    /**
     * Where a fetch is: file [index] of [count], [bytes] of it so far, of [size] when known.
     */
    data class Progress(val index: Int, val count: Int, val bytes: Long, val size: Long?)

    /**
     * Fetches the originals of [ids] into a fresh share folder, in order. Null when one of them
     * could not be fetched; `api.lastError` says why. Cancelling the calling coroutine stops it.
     */
    suspend fun fetch(
        context: Context,
        api: LibraryApi,
        ids: List<Long>,
        onProgress: (Progress) -> Unit,
    ): List<SharedFile>? {
        val dir = withContext(Dispatchers.IO) {
            File(context.cacheDir, "shared").apply {
                deleteRecursively()
                mkdirs()
            }
        }
        val used = HashSet<String>()
        val out = ArrayList<SharedFile>()
        for ((index, id) in ids.withIndex()) {
            val info = api.asset(id)
            val mime = info?.mime.orEmpty()
            val file = File(dir, unique(fileName(info?.relPath, mime, id), used))
            var shown = -1L
            val ok = api.downloadOriginal(id, file) { done, total ->
                // Every 256 KB, not every 64 KB chunk: redrawing per chunk is wasted work.
                if (shown < 0 || done - shown >= 256 * 1024 || done == total) {
                    shown = done
                    onProgress(Progress(index, ids.size, done, total))
                }
            }
            if (!ok) return null
            out += SharedFile(file, mime)
        }
        return out
    }

    /**
     * The name the receiver sees. The library stores files as `<name>-<first 8 of hash>.<ext>` so
     * two photos with the same camera name never collide; that suffix means nothing outside the
     * library and is dropped. Falls back to a generic name when the path is unknown.
     */
    fun fileName(relPath: String?, mime: String, assetId: Long): String {
        val stored = relPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val cleaned = stored
            ?.replace(Regex("-[0-9a-f]{8}(?=\\.[^.]+$)"), "")
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned ?: "PhotoHost-$assetId.${extensionFor(mime)}"
    }

    /** Opens the share sheet: a single send for one file, a multiple send for several. */
    fun launch(context: Context, files: List<SharedFile>) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.share", it.file) }
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }.apply {
            type = commonType(files.map { it.mime })
            // ClipData as well as the extra: some receivers only honour the grant through it.
            clipData = ClipData.newRawUri(files.first().file.name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /**
     * The type receivers filter on: exact when every file agrees, `image/` or `video/` for a single
     * kind, and anything for a mix of photos and videos, which is what galleries send.
     */
    private fun commonType(mimes: List<String>): String {
        val known = mimes.map { it.ifBlank { "application/octet-stream" } }
        if (known.distinct().size == 1) return known.first()
        val families = known.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }

    /** Two photos called IMG_0001.jpg from different months must not overwrite each other. */
    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (!used.add("$stem ($n)$ext")) n++
        return "$stem ($n)$ext"
    }

    private fun extensionFor(mime: String): String = when {
        mime == "image/jpeg" -> "jpg"
        mime == "image/heic" || mime == "image/heif" -> "heic"
        mime == "video/quicktime" -> "mov"
        '/' in mime -> mime.substringAfter('/').substringBefore(';')
        else -> "bin"
    }
}
