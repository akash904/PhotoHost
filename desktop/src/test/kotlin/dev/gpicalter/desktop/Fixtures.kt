package dev.gpicalter.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** Builds a small library of real files whose metadata is known exactly. */
object Fixtures {

    /** Stored 1600x1200 with EXIF orientation 6, taken 2024-03-10 14:15:16 at +05:30. */
    const val PORTRAIT = "2024/portrait.jpg"
    const val PORTRAIT_TAKEN_UTC = 1_710_060_316_000L // 2024-03-10T08:45:16Z

    /** 2000x1500, no EXIF; the date comes from the name. */
    const val LANDSCAPE = "IMG_20240115_123456.jpg"

    /** 300x200 PNG with transparency and nothing but an mtime to date it by. */
    const val SCREENSHOT = "misc/screenshot.png"
    const val SCREENSHOT_MTIME = 1_600_000_000_000L

    /** Not a real HEIC: tests that an undecodable image still indexes and parks its thumbnails. */
    const val HEIC = "misc/phone.heic"

    /** 160x90 H.264, displayed rotated 90 degrees clockwise, created 2023-05-06T07:08:09Z. */
    const val VIDEO = "video/VID_rotated.mp4"
    const val VIDEO_CREATED_UTC = 1_683_356_889_000L

    const val IMAGE_COUNT = 3 // the three ImageIO can decode
    const val MEDIA_COUNT = 5

    fun build(root: File) {
        // Top-left quadrant red, the rest blue: a thumbnail's rotation can then be read off its
        // pixels. After a clockwise quarter turn, red must be in the top-right.
        val portrait = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.color = Color.BLUE
            g.fillRect(0, 0, 1600, 1200)
            g.color = Color.RED
            g.fillRect(0, 0, 800, 600)
            g.dispose()
        }
        write(root, PORTRAIT, withExif(jpeg(portrait), exifOrientation6()))

        val landscape = BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, Color.ORANGE, 2000f, 1500f, Color.MAGENTA)
            g.fillRect(0, 0, 2000, 1500)
            g.dispose()
        }
        write(root, LANDSCAPE, jpeg(landscape))

        val png = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color(0, 128, 0, 128)
            g.fillOval(20, 20, 200, 150)
            g.dispose()
        }
        val pngOut = ByteArrayOutputStream().also { ImageIO.write(png, "png", it) }.toByteArray()
        write(root, SCREENSHOT, pngOut)
        File(root, SCREENSHOT).setLastModified(SCREENSHOT_MTIME)

        write(root, HEIC, ByteArray(4096) { (it * 31).toByte() })

        val video = Fixtures::class.java.getResourceAsStream("/fixtures/VID_rotated.mp4")!!.use { it.readBytes() }
        write(root, VIDEO, video)
    }

    private fun write(root: File, rel: String, bytes: ByteArray) {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    private fun jpeg(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()

    /** Splices an APP1 segment in straight after SOI. */
    private fun withExif(jpeg: ByteArray, tiff: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /**
     * A minimal little-endian TIFF block:
     *   IFD0:     Orientation = 6, ExifIFD pointer
     *   Exif IFD: DateTimeOriginal = "2024:03:10 14:15:16", OffsetTimeOriginal = "+05:30"
     */
    private fun exifOrientation6(): ByteArray {
        val buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        // IFD0 at 8: two entries, 2 + 2*12 + 4 = 30 bytes -> Exif IFD at 38.
        buf.putShort(2)
        buf.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0)
        buf.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(38)
        buf.putInt(0)
        // Exif IFD at 38: two entries -> values start at 68.
        buf.putShort(2)
        buf.putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(68)
        buf.putShort(0x9011.toShort()).putShort(2).putInt(7).putInt(88)
        buf.putInt(0)
        buf.put("2024:03:10 14:15:16\u0000".toByteArray(Charsets.US_ASCII))
        buf.put("+05:30\u0000".toByteArray(Charsets.US_ASCII))
        return buf.array().copyOf(buf.position())
    }
}
