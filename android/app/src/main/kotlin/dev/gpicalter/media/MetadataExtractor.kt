package dev.gpicalter.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import dev.gpicalter.data.entity.CaptureSource
import dev.gpicalter.data.entity.MediaType
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class MediaMetadata(
    val mediaType: Int,
    val width: Int? = null,
    val height: Int? = null,
    /** Detected rotation in degrees. Browsers rotate JPEGs from EXIF themselves. */
    val orientation: Int = 0,
    val durationMs: Long? = null,
    val capturedAt: Long,
    val capturedAtSource: Int,
    val tzOffsetMinutes: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
)

/**
 * Reads metadata from a descriptor. There is never a file path -- on a USB volume there cannot be
 * one -- so every API used here has to accept a `FileDescriptor`.
 *
 * ### Capture time is the hard part
 * It is also the most important: the timeline *is* the product, and a wrong date is invisible until
 * the library is large and then agonising to repair across tens of thousands of rows. EXIF
 * `DateTimeOriginal` carries no timezone, so it is ambiguous by up to a day at the edges. Sources
 * are tried best-first and **which one won is recorded**, so low-confidence dates can be surfaced
 * in the UI rather than silently trusted.
 *
 * `width`/`height` are **display** dimensions, already swapped for 90/270 rotation, so the grid can
 * compute aspect ratios without knowing anything about orientation.
 */
class MetadataExtractor {

    fun extract(pfd: ParcelFileDescriptor, mime: String, fallbackModifiedAt: Long, name: String): MediaMetadata =
        if (mime.startsWith("video/")) {
            video(pfd, fallbackModifiedAt, name)
        } else {
            image(pfd, mime, fallbackModifiedAt, name)
        }

    // ------------------------------------------------------------------ images

    private fun image(pfd: ParcelFileDescriptor, mime: String, modifiedAt: Long, name: String): MediaMetadata {
        val fd = pfd.fileDescriptor
        var width: Int? = null
        var height: Int? = null
        var rotation = 0
        var capturedAt: Long? = null
        var source = CaptureSource.UNKNOWN
        var tzOffset: Int? = null
        var lat: Double? = null
        var lon: Double? = null
        var make: String? = null
        var model: String? = null

        try {
            rewind(fd)
            val exif = ExifInterface(FileInputStream(fd))

            rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.ifEmpty { null }
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.ifEmpty { null }

            runCatching { exif.latLong }.getOrNull()?.let {
                if (it.size >= 2 && (it[0] != 0.0 || it[1] != 0.0)) {
                    lat = it[0]
                    lon = it[1]
                }
            }

            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val offsetRaw = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)

            if (raw != null) {
                val offsetMinutes = offsetRaw?.let(::parseOffsetMinutes)
                if (offsetMinutes != null) {
                    // Best case: the camera recorded the offset, so this is unambiguous.
                    capturedAt = parseExifDate(raw, offsetMinutes)
                    tzOffset = offsetMinutes
                    source = CaptureSource.EXIF_WITH_OFFSET
                } else {
                    // No offset recorded. Interpreting it in the device's current zone is a guess,
                    // and it is marked as one.
                    val deviceOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
                    capturedAt = parseExifDate(raw, deviceOffset)
                    tzOffset = deviceOffset
                    source = CaptureSource.EXIF_NAIVE
                }
            }
        } catch (t: Throwable) {
            // A corrupt or absent EXIF block is not a reason to reject the file.
        }

        try {
            rewind(fd)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, opts)
            if (opts.outWidth > 0) {
                width = opts.outWidth
                height = opts.outHeight
            }
        } catch (t: Throwable) {
            // Unsupported codec; dimensions stay null and the grid falls back to a square cell.
        }

        if (capturedAt == null) {
            val fromName = parseNameDate(name)
            if (fromName != null) {
                capturedAt = fromName
                source = CaptureSource.FILENAME
            } else if (modifiedAt > 0) {
                capturedAt = modifiedAt
                source = CaptureSource.MTIME
            }
        }

        val (dw, dh) = displayDimensions(width, height, rotation)
        return MediaMetadata(
            mediaType = if (isRaw(mime, name)) MediaType.RAW else MediaType.IMAGE,
            width = dw,
            height = dh,
            orientation = rotation,
            capturedAt = capturedAt ?: System.currentTimeMillis(),
            capturedAtSource = if (capturedAt == null) CaptureSource.UNKNOWN else source,
            tzOffsetMinutes = tzOffset,
            latitude = lat,
            longitude = lon,
            cameraMake = make,
            cameraModel = model,
        )
    }

    // ------------------------------------------------------------------ video

    private fun video(pfd: ParcelFileDescriptor, modifiedAt: Long, name: String): MediaMetadata {
        val mmr = MediaMetadataRetriever()
        var width: Int? = null
        var height: Int? = null
        var rotation = 0
        var duration: Long? = null
        var capturedAt: Long? = null
        var source = CaptureSource.UNKNOWN

        try {
            rewind(pfd.fileDescriptor)
            mmr.setDataSource(pfd.fileDescriptor)
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            // Container creation time, typically "yyyyMMdd'T'HHmmss.SSS'Z'" in UTC -- an absolute
            // instant, unlike naive EXIF, so it needs no guessing.
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { raw ->
                parseContainerDate(raw)?.let {
                    capturedAt = it
                    source = CaptureSource.MEDIASTORE
                }
            }
        } catch (t: Throwable) {
            // Unreadable container; fall through to filename and mtime.
        } finally {
            runCatching { mmr.release() }
        }

        if (capturedAt == null) {
            val fromName = parseNameDate(name)
            if (fromName != null) {
                capturedAt = fromName
                source = CaptureSource.FILENAME
            } else if (modifiedAt > 0) {
                capturedAt = modifiedAt
                source = CaptureSource.MTIME
            }
        }

        val (dw, dh) = displayDimensions(width, height, rotation)
        return MediaMetadata(
            mediaType = MediaType.VIDEO,
            width = dw,
            height = dh,
            orientation = rotation,
            durationMs = duration,
            capturedAt = capturedAt ?: System.currentTimeMillis(),
            capturedAtSource = if (capturedAt == null) CaptureSource.UNKNOWN else source,
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Both ExifInterface and MediaMetadataRetriever read from the descriptor's current position, so
     * it has to be rewound between them or the second one sees an exhausted file.
     */
    private fun rewind(fd: java.io.FileDescriptor) {
        runCatching { Os.lseek(fd, 0, OsConstants.SEEK_SET) }
    }

    private fun displayDimensions(w: Int?, h: Int?, rotation: Int): Pair<Int?, Int?> =
        if ((rotation == 90 || rotation == 270) && w != null && h != null) h to w else w to h

    private fun isRaw(mime: String, name: String): Boolean {
        if (mime.contains("dng", ignoreCase = true) || mime.contains("raw", ignoreCase = true)) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw")
    }

    private fun parseExifDate(raw: String, offsetMinutes: Int): Long? = try {
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        fmt.timeZone = fixedZone(offsetMinutes)
        fmt.parse(raw.trim())?.time
    } catch (t: Throwable) {
        null
    }

    private fun parseContainerDate(raw: String): Long? {
        val patterns = listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyy-MM-dd HH:mm:ss")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = fmt.parse(raw.trim()) ?: continue
                // Some encoders write 1904 or 1970 epochs when they have no clock.
                if (parsed.time > 946_684_800_000L) return parsed.time
            } catch (t: Throwable) {
                // try the next pattern
            }
        }
        return null
    }

    /**
     * Recovers a date from the filename.
     *
     * Handles three shapes, most precise first:
     *  - `IMG_20240115_123456` / `PXL_20240115_123456789` -- full date and time
     *  - `IMG-20260901-WA0000` -- WhatsApp and similar: date only, no time. Extremely common, and
     *    previously rejected outright because the old pattern demanded a time component, which is
     *    why every WhatsApp image fell through to its meaningless mtime.
     *  - `image-1787473356054.png` -- an epoch in milliseconds, as some downloaders emit
     *
     * A date-only name is anchored at midday rather than midnight: with no time recorded, noon is
     * the choice that cannot land the photo on the wrong calendar day in a neighbouring timezone.
     */
    fun parseNameDate(name: String): Long? {
        NAME_DATE_TIME.find(name)?.let { m ->
            val (y, mo, d, h, mi, sec) = m.destructured
            atLocal("$y$mo$d$h$mi$sec", "yyyyMMddHHmmss")?.let { return it }
        }
        NAME_DATE_ONLY.find(name)?.let { m ->
            val (y, mo, d) = m.destructured
            if (mo.toInt() in 1..12 && d.toInt() in 1..31) {
                atLocal("$y$mo${d}12", "yyyyMMddHH")?.let { return it }
            }
        }
        NAME_EPOCH.find(name)?.let { m ->
            val millis = m.groupValues[1].toLongOrNull()
            if (millis != null && millis > MIN_PLAUSIBLE && millis < 4_102_444_800_000L) return millis
        }
        return null
    }

    private fun atLocal(text: String, pattern: String): Long? = try {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val parsed = fmt.parse(text)
        if (parsed != null && parsed.time > MIN_PLAUSIBLE) parsed.time else null
    } catch (t: Throwable) {
        null
    }

    private fun parseOffsetMinutes(raw: String): Int? {
        val s = raw.trim()
        val m = OFFSET.matchEntire(s) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hours = m.groupValues[2].toIntOrNull() ?: return null
        val minutes = m.groupValues[3].toIntOrNull() ?: return null
        return sign * (hours * 60 + minutes)
    }

    private fun fixedZone(offsetMinutes: Int): TimeZone {
        val sign = if (offsetMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMinutes)
        return TimeZone.getTimeZone(String.format(Locale.US, "GMT%s%02d:%02d", sign, abs / 60, abs % 60))
    }

    companion object {
        const val MIN_PLAUSIBLE = 946_684_800_000L
        val NAME_DATE_TIME = Regex("""(20\d{2})(\d{2})(\d{2})[_\-T]?(\d{2})(\d{2})(\d{2})""")
        val NAME_DATE_ONLY = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""")
        val NAME_EPOCH = Regex("""(?<!\d)(1[5-9]\d{11})(?!\d)""")
        val OFFSET = Regex("""([+\-])(\d{2}):?(\d{2})""")
    }
}
