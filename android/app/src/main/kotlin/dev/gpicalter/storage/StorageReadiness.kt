package dev.gpicalter.storage

import android.content.Context
import android.net.Uri

/**
 * Whether the configured library location can actually be used right now.
 *
 * This exists because [dev.gpicalter.di.AppContainer.buildStore] falls back to [InternalStore]
 * whenever the SAF grant is missing. That fallback is right for the server, which must come up and
 * serve something rather than refuse to start, but it is silent: somebody who picked a USB drive
 * gets internal storage instead and nothing ever says so. Asking the question separately is what
 * lets the UI say it.
 *
 * Call it off the main thread. [SafStore.isMounted] is a ContentResolver query, and on a drive that
 * has been pulled it is the slow kind that waits for a timeout.
 */
enum class StorageReadiness {
    /** Photos can be read and written where they are configured to live. */
    READY,

    /** Configured for a USB volume whose grant is gone, or which is simply not plugged in. */
    FOLDER_MISSING,
}

fun storageReadiness(context: Context, backend: String, treeUri: Uri?): StorageReadiness {
    // Internal storage is app-private and always there. There is nothing to be missing.
    if (backend != StoreKind.SAF.name) return StorageReadiness.READY

    val uri = treeUri ?: return StorageReadiness.FOLDER_MISSING

    // A grant can be revoked by the system or by the user clearing app data, and the stored URI
    // outlives it. Holding the string is not the same as still being allowed to read it.
    val granted = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)
    if (!granted) return StorageReadiness.FOLDER_MISSING

    return if (runCatching { SafStore(context, uri).isMounted }.getOrDefault(false)) {
        StorageReadiness.READY
    } else {
        StorageReadiness.FOLDER_MISSING
    }
}
