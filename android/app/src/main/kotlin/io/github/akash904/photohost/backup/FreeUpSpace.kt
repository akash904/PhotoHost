package io.github.akash904.photohost.backup

import android.content.Context
import android.util.Log
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.data.entity.SourceKind
import io.github.akash904.photohost.net.LibraryApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "photohost"

/**
 * Finds the photos this phone can delete because its backup library holds them safely.
 *
 * Every rule errs towards keeping the photo:
 *  - only photos in the folders backup covers, sent by this phone's own backup (a fingerprint
 *    exists) and unchanged since (an edited photo is new bytes the library does not hold);
 *  - only what the library confirms, at the moment of asking, it holds safely -- see
 *    LibraryApi.verifySafe -- never this phone's own memory of having sent it;
 *  - by default nothing from the last 30 days, and nothing from WhatsApp, whose chats show a
 *    deleted photo as missing;
 *  - a library too old to answer that question frees nothing.
 *
 * Nothing here deletes. The caller hands the result to Android's own trash request, which asks the
 * user and keeps the photos restorable for 30 days.
 */
class FreeUpSpace(
    private val context: Context,
    private val db: AppDatabase,
    private val api: LibraryApi,
    private val prefs: Prefs,
) {
    data class Options(val keepRecentDays: Int = 30, val includeWhatsApp: Boolean = false)

    sealed interface Scan {
        data class Ready(
            /**
             * The folders looked at: those backup covers. Shown on screen, because "9 photos" means
             * nothing without "from Camera" -- a test once removed nine camera photos when the
             * person expected three pictures from a folder that was never in scope.
             */
            val folders: List<String>,
            /** Safe to remove from this phone. */
            val removable: List<DeviceItem>,
            val removableBytes: Long,
            /** Kept, each for its reason, so the screen can say why a photo stays. */
            val keptRecent: Int,
            val keptWhatsApp: Int,
            val keptNotBackedUp: Int,
            val keptNotConfirmed: Int,
        ) : Scan

        /** The backup library cannot answer the strict question yet. */
        data object LibraryTooOld : Scan

        data class Failed(val message: String) : Scan
    }

    suspend fun scan(options: Options): Scan = withContext(Dispatchers.IO) {
        val buckets = MediaBuckets.enumerate(context)
        val chosen = prefs.backupBuckets ?: MediaBuckets.defaultSelection(buckets)
        val folders = buckets.filter { it.id in chosen }.map { it.name }
        val items = try {
            DeviceMedia.list(context, chosen)
        } catch (t: Throwable) {
            return@withContext Scan.Failed("Could not read this phone's photos: ${t.message}")
        }

        val cutoff = System.currentTimeMillis() - options.keepRecentDays * 86_400_000L
        var recent = 0
        var whatsApp = 0
        var notBackedUp = 0
        val candidates = ArrayList<Pair<DeviceItem, String>>()
        for (item in items) {
            when {
                options.keepRecentDays > 0 && item.sortKey > cutoff -> recent++
                !options.includeWhatsApp && item.bucketName?.contains("WhatsApp", ignoreCase = true) == true -> whatsApp++
                else -> {
                    val fp = db.fingerprints().find(SourceKind.MEDIASTORE, item.sourceKey)
                    if (fp != null && isUnchanged(item, fp)) candidates += item to fp.contentHash else notBackedUp++
                }
            }
        }

        val safe = HashSet<String>()
        for (batch in candidates.chunked(VERIFY_BATCH)) {
            when (val r = api.verifySafe(batch.map { it.second }.distinct())) {
                is LibraryApi.VerifyOutcome.Checked -> safe += r.safe
                LibraryApi.VerifyOutcome.Unsupported -> return@withContext Scan.LibraryTooOld
                is LibraryApi.VerifyOutcome.Failed ->
                    return@withContext Scan.Failed("Could not reach the library to check: ${r.error}")
            }
        }

        val removable = candidates.filter { it.second in safe }.map { it.first }
        Log.i(
            TAG,
            "free up space: ${removable.size} removable of ${items.size}; kept recent=$recent " +
                "whatsapp=$whatsApp notBackedUp=$notBackedUp notConfirmed=${candidates.size - removable.size}",
        )
        Scan.Ready(
            folders = folders,
            removable = removable,
            removableBytes = removable.sumOf { it.size },
            keptRecent = recent,
            keptWhatsApp = whatsApp,
            keptNotBackedUp = notBackedUp,
            keptNotConfirmed = candidates.size - removable.size,
        )
    }

    /**
     * Asks the library again, just before the trash request, about exactly the photos about to go.
     * The scan may be minutes old; a photo trashed in the library since then must not be removed.
     */
    suspend fun reconfirm(items: List<DeviceItem>): List<DeviceItem>? = withContext(Dispatchers.IO) {
        val hashOf = HashMap<DeviceItem, String>()
        for (item in items) {
            val fp = db.fingerprints().find(SourceKind.MEDIASTORE, item.sourceKey) ?: continue
            if (isUnchanged(item, fp)) hashOf[item] = fp.contentHash
        }
        val safe = HashSet<String>()
        for (batch in hashOf.values.distinct().chunked(VERIFY_BATCH)) {
            val r = api.verifySafe(batch) as? LibraryApi.VerifyOutcome.Checked ?: return@withContext null
            safe += r.safe
        }
        items.filter { hashOf[it] in safe }
    }

    private companion object {
        const val VERIFY_BATCH = 200
    }
}
