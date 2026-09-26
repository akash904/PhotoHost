package dev.gpicalter.media

import dev.gpicalter.desktop.Fixtures
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Windows' own decoders, against real files. Needs Windows; the HEIC cases also need samples in
 * `testdata/` (not committed) and the HEIF and HEVC extensions, and skip without them.
 *
 * Correctness is judged against ffmpeg's decode of the same file, which is an independent decoder:
 * agreeing on size and pixels rules out a wrong channel order, a wrong scale, and a wrong rotation.
 */
class WindowsCodecsTest {

    private val ffmpeg = VideoFrames.locate(null).ffmpeg

    private fun windows() = assumeTrue(WindowsCodecs.available, "not Windows")

    @Test
    fun `decodes HEIC at full size with the same pixels ffmpeg sees`() {
        windows()
        val sample = File("testdata/autumn.heic")
        assumeTrue(sample.isFile, "no testdata/autumn.heic")

        assertEquals(1440 to 960, WindowsCodecs.imageSize(sample))
        val img = WindowsCodecs.decodeImage(sample, 1440)
        assertEquals(1440, img.width)
        assertEquals(960, img.height)

        assumeTrue(ffmpeg != null, "no ffmpeg to compare against")
        val reference = ffmpegFrame(ffmpeg!!, sample)
        assertEquals(img.width, reference.width)
        val diff = meanDiff(img, reference)
        assertTrue(diff < 6.0, "HEIC decode differs from ffmpeg's by $diff per channel")
    }

    @Test
    fun `decodes HEIC scaled down, keeping the aspect ratio`() {
        windows()
        val sample = File("testdata/autumn.heic")
        assumeTrue(sample.isFile, "no testdata/autumn.heic")
        val img = WindowsCodecs.decodeImage(sample, 384)
        assertEquals(384, img.width)
        assertEquals(256, img.height)
    }

    @Test
    fun `a file Windows cannot decode is reported as a missing decoder, not a crash`() {
        windows()
        val junk = Files.createTempFile("junk", ".heic").toFile()
        try {
            junk.writeBytes(ByteArray(4096) { (it * 31).toByte() })
            assertFailsWith<DecoderUnavailableException> { WindowsCodecs.decodeImage(junk, 256) }
        } finally {
            junk.delete()
        }
    }

    @Test
    fun `video thumbnail from Windows comes back upright`() {
        windows()
        assumeTrue(ffmpeg != null, "no ffmpeg to compare against")
        val video = Files.createTempFile("rotated", ".mp4").toFile()
        try {
            Fixtures::class.java.getResourceAsStream("/fixtures/VID_rotated.mp4")!!.use { video.writeBytes(it.readBytes()) }
            val thumb = WindowsCodecs.videoThumbnail(video, 1440)
            println("Windows video thumbnail: ${thumb.width}x${thumb.height}")
            assertTrue(thumb.height > thumb.width, "a portrait video must give a portrait thumbnail: ${thumb.width}x${thumb.height}")

            // ffmpeg's upright frame, scaled to the same size, is the reference.
            val reference = ImageDecoding.scale(ffmpegFrame(ffmpeg!!, video), thumb.width, thumb.height)
            val same = meanDiff(thumb, reference)
            val flipped = meanDiff(thumb, rotate180(reference))
            println("upright diff=$same, flipped diff=$flipped")
            assertTrue(same * 2 < flipped, "Windows' frame does not match ffmpeg's upright one: $same vs $flipped")
        } finally {
            video.delete()
        }
    }

    @Test
    fun `HEVC video, as phones record in high-efficiency mode, thumbnails upright`() {
        windows()
        assumeTrue(ffmpeg != null, "no ffmpeg to make an HEVC clip with")
        val dir = Files.createTempDirectory("hevc").toFile()
        try {
            // Portrait as a phone stores it: landscape pixels plus a 90-degree display rotation.
            val plain = File(dir, "plain.mp4")
            val rotated = File(dir, "portrait.mp4")
            run(ffmpeg!!, "-f", "lavfi", "-i", "testsrc=duration=1:size=320x180:rate=10", "-c:v", "libx265", "-tag:v", "hvc1", "-pix_fmt", "yuv420p", plain.absolutePath)
            assumeTrue(plain.isFile && plain.length() > 0, "this ffmpeg cannot encode HEVC")
            run(ffmpeg, "-display_rotation:v:0", "-90", "-i", plain.absolutePath, "-c", "copy", rotated.absolutePath)

            val thumb = try {
                WindowsCodecs.videoThumbnail(rotated, 1440)
            } catch (e: DecoderUnavailableException) {
                assumeTrue(false, "no HEVC extension on this PC: ${e.message}")
                return
            }
            assertTrue(thumb.height > thumb.width, "portrait HEVC gave ${thumb.width}x${thumb.height}")
            val reference = ImageDecoding.scale(ffmpegFrame(ffmpeg, rotated), thumb.width, thumb.height)
            val same = meanDiff(thumb, reference)
            val flipped = meanDiff(thumb, rotate180(reference))
            println("HEVC ${thumb.width}x${thumb.height}: upright diff=$same, flipped diff=$flipped")
            assertTrue(same * 2 < flipped, "HEVC frame not upright: $same vs $flipped")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun run(ffmpeg: File, vararg args: String) {
        val p = ProcessBuilder(listOf(ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-nostdin", "-y") + args)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor()
    }

    /** ffmpeg's own decode, autorotation on: what the file is meant to look like. */
    private fun ffmpegFrame(ffmpeg: File, file: File): BufferedImage {
        val p = ProcessBuilder(
            ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-i", file.absolutePath, "-frames:v", "1", "-f", "image2pipe", "-c:v", "bmp", "-",
        ).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun rotate180(src: BufferedImage): BufferedImage = ImageDecoding.rotate(src, 180)

    private fun meanDiff(a: BufferedImage, b: BufferedImage): Double {
        var sum = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            for (shift in intArrayOf(16, 8, 0)) sum += abs(((p shr shift) and 0xff) - ((q shr shift) and 0xff))
        }
        return sum.toDouble() / (a.width * a.height * 3)
    }
}
