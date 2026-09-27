package io.github.akash904.photohost.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a library photo to another app through Android's share sheet.
 *
 * The original lives in a library that may be on another device, reached over pinned TLS with a
 * token, so no other app can open it where it is. A copy is fetched into cache/shared/ and lent
 * through a FileProvider, which grants the receiving app read access to that one file for the
 * duration of the share and nothing more.
 *
 * Only the latest shared file is kept. The receiving app may still be reading it after the share
 * sheet closes, so it cannot be deleted then; it is cleared at the start of the next share
 * instead, and Android may clear the cache on its own at any time.
 */
object Share {

    /** A clean folder for the next shared file. */
    fun freshFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "shared")
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, name)
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

    /** Opens the share sheet for [file]. */
    fun launch(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData as well as the extra: some receivers only honour the grant through it.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun extensionFor(mime: String): String = when {
        mime == "image/jpeg" -> "jpg"
        mime == "image/heic" || mime == "image/heif" -> "heic"
        mime == "video/quicktime" -> "mov"
        '/' in mime -> mime.substringAfter('/').substringBefore(';')
        else -> "bin"
    }
}
