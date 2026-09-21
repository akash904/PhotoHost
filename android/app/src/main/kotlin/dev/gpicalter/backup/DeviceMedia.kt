package dev.gpicalter.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

private const val TAG = "gpic"

/** One item in this device's own media store. */
data class DeviceItem(
    val mediaStoreId: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
    val generation: Long?,
    val takenAt: Long?,
    val isVideo: Boolean,
    val bucketId: String?,
    val bucketName: String?,
) {
    val sourceKey: String get() = "ms:$mediaStoreId"
    val sortKey: Long get() = takenAt ?: modifiedAt
}

/**
 * Reads the device's camera roll.
 *
 * Shared by the backup engine and the manual picker on purpose: if the two disagreed about what
 * counts as media, or about which rows to skip, the picker would offer photos the engine refuses to
 * upload and there would be no way to tell from the UI why nothing happened.
 */
object DeviceMedia {

    fun list(context: Context, bucketIds: Set<String>? = null): List<DeviceItem> {
        val out = ArrayList<DeviceItem>()
        val hasGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )

        for ((collection, isVideo) in collections) {
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.DATE_MODIFIED)
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.IS_PENDING)
                add(MediaStore.MediaColumns.BUCKET_ID)
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                if (hasGeneration) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            }.toTypedArray()

            try {
                context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val iPending = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                    val iBucketName = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val iGen = if (hasGeneration) {
                        c.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)
                    } else {
                        -1
                    }

                    while (c.moveToNext()) {
                        // Still being written by another app: hashing it now would capture a
                        // half-finished file and then remember that as its fingerprint.
                        if (iPending >= 0 && c.getInt(iPending) == 1) continue
                        val size = if (c.isNull(iSize)) 0L else c.getLong(iSize)
                        if (size <= 0L) continue
                        val bucket = if (iBucket >= 0 && !c.isNull(iBucket)) c.getString(iBucket) else null
                        if (bucketIds != null && bucket !in bucketIds) continue

                        val id = c.getLong(iId)
                        out += DeviceItem(
                            mediaStoreId = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(iName) ?: "media_$id",
                            size = size,
                            // DATE_MODIFIED is seconds; DATE_TAKEN is already millis.
                            modifiedAt = if (c.isNull(iMod)) 0L else c.getLong(iMod) * 1000L,
                            generation = if (iGen >= 0 && !c.isNull(iGen)) c.getLong(iGen) else null,
                            takenAt = if (iTaken >= 0 && !c.isNull(iTaken)) c.getLong(iTaken) else null,
                            isVideo = isVideo,
                            bucketId = bucket,
                            bucketName = if (iBucketName >= 0 && !c.isNull(iBucketName)) {
                                c.getString(iBucketName)
                            } else {
                                null
                            },
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "media query failed for $collection: ${t.message}")
            }
        }
        // Newest first: what you shot today is what you most want backed up.
        return out.sortedByDescending { it.sortKey }
    }
}
