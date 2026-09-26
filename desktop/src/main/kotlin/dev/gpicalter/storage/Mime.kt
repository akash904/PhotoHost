package dev.gpicalter.storage

/**
 * Extension to MIME type, standing in for Android's MimeTypeMap.
 *
 * A fixed table rather than `Files.probeContentType`: on Windows that consults the registry, so
 * the same file would get a different type on different PCs -- and the type is stored on the asset
 * and decides image-versus-video handling. The values are meant to match what MimeTypeMap returns
 * on Android, so a file gets the same type whichever server indexed it. They were written from
 * Android's MimeUtils table as remembered, NOT checked against a device -- worth one pass with a
 * real phone before anyone relies on the RAW entries.
 */
object Mime {
    private val TABLE = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "dng" to "image/x-adobe-dng",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "raf" to "image/x-fuji-raf",
        "srw" to "image/x-samsung-srw",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/avi",
        "html" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "txt" to "text/plain",
    )

    fun forName(name: String): String =
        TABLE[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}
