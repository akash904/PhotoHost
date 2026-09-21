package dev.gpicalter.backup

import android.content.Context
import android.provider.MediaStore
import android.util.Log

private const val TAG = "gpic"

/**
 * The folders MediaStore groups media into: `Camera`, `Screenshots`, `WhatsApp Images`, `Instagram`,
 * `Download`, and so on. These are what a person actually thinks of as "albums", and they are the
 * right granularity for deciding what gets backed up.
 *
 * Backing up everything indiscriminately means uploading meme caches, browser downloads and
 * thumbnails from other apps. Choosing per folder is the difference between a photo library and a
 * copy of the phone's junk drawer.
 */
data class MediaBucket(
    val id: String,
    val name: String,
    /** e.g. "DCIM/Camera/" -- present on API 29+, used to guess sensible defaults. */
    val relativePath: String?,
    val itemCount: Int,
    val totalBytes: Long,
    val newestAt: Long,
) {
    /**
     * Whether this folder looks like it holds photos this phone took, as opposed to files it
     * received. Used only to pre-tick sensible defaults, never to override an explicit choice.
     */
    val looksLikeCamera: Boolean
        get() {
            val path = relativePath?.lowercase().orEmpty()
            return path.startsWith("dcim/") || name.equals("Camera", ignoreCase = true)
        }
}

object MediaBuckets {

    fun enumerate(context: Context): List<MediaBucket> {
        val acc = LinkedHashMap<String, Accumulator>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val projection = arrayOf(
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING,
        )

        for (collection in collections) {
            try {
                context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val iPath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iPending = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)

                    while (c.moveToNext()) {
                        if (iPending >= 0 && c.getInt(iPending) == 1) continue
                        if (c.isNull(iId)) continue
                        val id = c.getString(iId) ?: continue
                        val entry = acc.getOrPut(id) {
                            Accumulator(
                                name = if (c.isNull(iName)) "(unnamed)" else c.getString(iName),
                                relativePath = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) else null,
                            )
                        }
                        entry.count++
                        if (!c.isNull(iSize)) entry.bytes += c.getLong(iSize)
                        if (!c.isNull(iMod)) {
                            val modMillis = c.getLong(iMod) * 1000L
                            if (modMillis > entry.newest) entry.newest = modMillis
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bucket enumeration failed for $collection: ${t.message}")
            }
        }

        return acc.map { (id, a) ->
            MediaBucket(id, a.name, a.relativePath, a.count, a.bytes, a.newest)
        }.sortedWith(
            // Camera-like folders first, then by size: the biggest folders are the ones worth
            // making a deliberate decision about.
            compareByDescending<MediaBucket> { it.looksLikeCamera }.thenByDescending { it.itemCount },
        )
    }

    /**
     * What to tick when the user has never chosen. Camera-like folders only, matching the
     * expectation that "back up my photos" means the ones this phone took -- not every image any
     * app ever cached.
     */
    fun defaultSelection(buckets: List<MediaBucket>): Set<String> =
        buckets.filter { it.looksLikeCamera }.map { it.id }.toSet()

    private class Accumulator(val name: String, val relativePath: String?) {
        var count = 0
        var bytes = 0L
        var newest = 0L
    }
}
