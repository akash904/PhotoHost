package dev.gpicalter.media

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.mov.QuickTimeDirectory
import com.drew.metadata.mov.media.QuickTimeVideoDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.drew.metadata.mp4.media.Mp4VideoDirectory
import dev.gpicalter.data.entity.CaptureSource
import dev.gpicalter.data.entity.MediaType
import dev.gpicalter.storage.ReadHandle
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads metadata from a store handle.
 *
 * DESKTOP REIMPLEMENTATION of gpicAlter's MetadataExtractor. The phone uses ExifInterface,
 * BitmapFactory bounds and MediaMetadataRetriever; this uses metadata-extractor and ImageIO. The
 * decision rules are copied, not reinvented, because the same photo must get the same date whichever
 * server indexed it:
 *
 *  - date: `DateTimeOriginal`, else `DateTime`; offset: `OffsetTimeOriginal`, else `OffsetTime`
 *  - with an offset the date is EXIF_WITH_OFFSET; without one it is read in this machine's zone and
 *    marked EXIF_NAIVE
 *  - no EXIF date: filename, then mtime (the name parser below is the phone's, verbatim)
 *  - orientation: EXIF 6/3/8 map to 90/180/270; mirrored orientations are ignored, as on the phone
 *  - GPS (0, 0) is treated as absent
 *  - videos: the container creation time, rejected before 2000 (encoders with no clock write 1904
 *    or 1970), recorded as MEDIASTORE exactly as the phone records METADATA_KEY_DATE
 *
 * ### Capture time is the hard part
 * It is also the most important: the timeline *is* the product, and a wrong date is invisible until
 * the library is large and then agonising to repair across tens of thousands of rows. Sources
 * are tried best-first and **which one won is recorded**, so low-confidence dates can be surfaced
 * in the UI rather than silently trusted.
 *
 * `width`/`height` are **display** dimensions, already swapped for 90/270 rotation, so the grid can
 * compute aspect ratios without knowing anything about orientation.
 */
class MetadataExtractor {

    fun extract(handle: ReadHandle, mime: String, fallbackModifiedAt: Long, name: String): MediaMetadata =
        if (mime.startsWith("video/")) {
            video(handle, fallbackModifiedAt, name)
        } else {
            image(handle, mime, fallbackModifiedAt, name)
        }

    // ------------------------------------------------------------------ images

    private fun image(handle: ReadHandle, mime: String, modifiedAt: Long, name: String): MediaMetadata {
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
        var exifWidth: Int? = null
        var exifHeight: Int? = null

        try {
            val md = read(handle)
            val ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)

            rotation = when (ifd0?.getInteger(ExifDirectoryBase.TAG_ORIENTATION)) {
                6 -> 90
                3 -> 180
                8 -> 270
                else -> 0
            }
            make = ifd0?.getString(ExifDirectoryBase.TAG_MAKE)?.trim()?.ifEmpty { null }
            model = ifd0?.getString(ExifDirectoryBase.TAG_MODEL)?.trim()?.ifEmpty { null }

            md.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation?.let {
                if (!it.isZero) {
                    lat = it.latitude
                    lon = it.longitude
                }
            }

            val raw = stringOf(ExifDirectoryBase.TAG_DATETIME_ORIGINAL, sub, ifd0)
                ?: stringOf(ExifDirectoryBase.TAG_DATETIME, ifd0, sub)
            val offsetRaw = stringOf(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL, sub, ifd0)
                ?: stringOf(ExifDirectoryBase.TAG_TIME_ZONE, sub, ifd0)

            if (raw != null) {
                val offsetMinutes = offsetRaw?.let(::parseOffsetMinutes)
                if (offsetMinutes != null) {
                    // Best case: the camera recorded the offset, so this is unambiguous.
                    capturedAt = parseExifDate(raw, offsetMinutes)
                    tzOffset = offsetMinutes
                    source = CaptureSource.EXIF_WITH_OFFSET
                } else {
                    // No offset recorded. Interpreting it in the machine's current zone is a guess,
                    // and it is marked as one.
                    val deviceOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
                    capturedAt = parseExifDate(raw, deviceOffset)
                    tzOffset = deviceOffset
                    source = CaptureSource.EXIF_NAIVE
                }
                if (capturedAt == null) {
                    // An unparseable date string is no date at all, not a zero one.
                    source = CaptureSource.UNKNOWN
                    tzOffset = null
                }
            }

            exifWidth = sub?.getInteger(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH)
            exifHeight = sub?.getInteger(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT)
        } catch (t: Throwable) {
            // A corrupt or absent EXIF block is not a reason to reject the file.
        }

        try {
            // Header-only, like BitmapFactory's inJustDecodeBounds.
            ImageDecoding.bounds(handle.inputStream())?.let {
                width = it.width
                height = it.height
            }
        } catch (t: Throwable) {
            // Unsupported codec; fall back below.
        }
        if (width == null && exifWidth != null && exifHeight != null && exifWidth > 0 && exifHeight > 0) {
            // HEIC has no ImageIO reader. The phone gets real bounds from its platform decoder;
            // the EXIF pixel dimensions are the closest thing available here.
            width = exifWidth
            height = exifHeight
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

    private fun video(handle: ReadHandle, modifiedAt: Long, name: String): MediaMetadata {
        var width: Int? = null
        var height: Int? = null
        var rotation = 0
        var duration: Long? = null
        var capturedAt: Long? = null
        var source = CaptureSource.UNKNOWN

        try {
            val md = read(handle)
            // MP4 and QuickTime are separate directory families in metadata-extractor, with the same
            // shape. Which one appears depends on the file's brand, not its extension.
            val mp4 = md.getFirstDirectoryOfType(Mp4Directory::class.java)
            val mov = md.getFirstDirectoryOfType(QuickTimeDirectory::class.java)

            if (mp4 != null) {
                val v = md.getFirstDirectoryOfType(Mp4VideoDirectory::class.java)
                width = v?.getInteger(Mp4VideoDirectory.TAG_WIDTH)
                height = v?.getInteger(Mp4VideoDirectory.TAG_HEIGHT)
                rotation = mp4.getInteger(Mp4Directory.TAG_ROTATION) ?: 0
                duration = durationMs(mp4, Mp4Directory.TAG_DURATION, Mp4Directory.TAG_TIME_SCALE)
                capturedAt = mp4.getDate(Mp4Directory.TAG_CREATION_TIME)?.time
            } else if (mov != null) {
                val v = md.getFirstDirectoryOfType(QuickTimeVideoDirectory::class.java)
                width = v?.getInteger(QuickTimeVideoDirectory.TAG_WIDTH)
                height = v?.getInteger(QuickTimeVideoDirectory.TAG_HEIGHT)
                rotation = mov.getInteger(QuickTimeDirectory.TAG_ROTATION) ?: 0
                duration = durationMs(mov, QuickTimeDirectory.TAG_DURATION, QuickTimeDirectory.TAG_TIME_SCALE)
                capturedAt = mov.getDate(QuickTimeDirectory.TAG_CREATION_TIME)?.time
            }
            // metadata-extractor measures the track matrix's angle counter-clockwise; Android's
            // METADATA_KEY_VIDEO_ROTATION, which the phone stores, is clockwise. Verified on a
            // fixture that ffprobe reports as "rotation -90" -- the value it shows for a phone's
            // portrait video, for which MediaMetadataRetriever returns 90 -- and for which
            // metadata-extractor returned 270. Not yet checked against a video from a real phone.
            rotation = (360 - ((rotation % 360) + 360) % 360) % 360
            if (rotation !in setOf(0, 90, 180, 270)) rotation = 0
            if (width != null && width <= 0) width = null
            if (height != null && height <= 0) height = null

            // Some encoders write 1904 or 1970 epochs when they have no clock.
            if (capturedAt != null && capturedAt > MIN_PLAUSIBLE) {
                source = CaptureSource.MEDIASTORE
            } else {
                capturedAt = null
            }
        } catch (t: Throwable) {
            // Unreadable container; fall through to filename and mtime.
            capturedAt = null
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

    private fun read(handle: ReadHandle): Metadata =
        BufferedInputStream(handle.inputStream(), 64 * 1024).use { input ->
            ImageMetadataReader.readMetadata(input, handle.size)
        }

    private fun stringOf(tag: Int, vararg dirs: Directory?): String? =
        dirs.firstNotNullOfOrNull { d -> d?.getString(tag)?.trim()?.takeIf { it.isNotEmpty() } }

    private fun durationMs(dir: Directory, durationTag: Int, scaleTag: Int): Long? {
        val units = dir.getLongObject(durationTag) ?: return null
        val scale = dir.getLongObject(scaleTag)?.takeIf { it > 0 } ?: return null
        return units * 1000 / scale
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

    // ---------------------------------------------------------------- copied verbatim from gpicAlter

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
