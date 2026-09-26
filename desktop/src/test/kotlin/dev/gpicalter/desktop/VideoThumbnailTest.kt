package dev.gpicalter.desktop

import dev.gpicalter.media.VideoFrames
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Video thumbnails through ffmpeg. Skipped where no ffmpeg can be found.
 *
 * The orientation check is the point. ffmpeg itself applies the display matrix when it autorotates,
 * so a reference frame from ffmpeg with autorotation ON is ground truth for how the video is meant
 * to look. The server decodes with autorotation OFF and rotates by the orientation it stored, so the
 * two agree only if the stored orientation's direction is right.
 */
class VideoThumbnailTest {

    /** Through Windows' thumbnailer, which is first in line on Windows. */
    @Test
    fun `video thumbnail is rendered upright, matching ffmpeg's own rotation`() = uprightThroughTheServer()

    /** Through the ffmpeg fallback: Windows' decoders switched off, so the server must rotate itself. */
    @Test
    fun `the ffmpeg fallback renders video thumbnails upright too`() {
        dev.gpicalter.media.WindowsCodecs.enabled = false
        try {
            uprightThroughTheServer()
        } finally {
            dev.gpicalter.media.WindowsCodecs.enabled = true
        }
    }

    private fun uprightThroughTheServer() = runBlocking {
        val ffmpeg = VideoFrames.locate(null).ffmpeg
        assumeTrue(ffmpeg != null, "no ffmpeg on this machine")

        val dataDir = Files.createTempDirectory("photohost-data").toFile()
        val libraryDir = Files.createTempDirectory("photohost-lib").toFile()
        try {
            Fixtures.build(libraryDir)
            val cfg = Config.load(dataDir).apply {
                libraryRoot = libraryDir
                port = freePortPair()
            }
            val server = DesktopServer(cfg)
            assertTrue(server.start(), server.status.value.error ?: "")
            try {
                val asset = awaitVideoThumb(server)
                val thumbs = File(dataDir, "libraries").walkTopDown()
                    .first { it.isFile && it.name == "${asset}_g.jpg" }
                val ours = ImageIO.read(thumbs)

                // Stored 160x90, displayed rotated: 90x160, below the 256 edge so not scaled.
                assertEquals(90, ours.width)
                assertEquals(160, ours.height)

                val reference = referenceFrame(ffmpeg!!, File(libraryDir, Fixtures.VIDEO))
                assertEquals(ours.width, reference.width)
                assertEquals(ours.height, reference.height)

                val same = meanDiff(ours, reference)
                val upsideDown = meanDiff(ours, rotate180(reference))
                assertTrue(same < 20.0, "thumbnail differs from ffmpeg's upright frame: $same")
                assertTrue(same * 3 < upsideDown, "rotation looks wrong: upright=$same, flipped=$upsideDown")
            } finally {
                server.stop()
            }
        } finally {
            dataDir.deleteRecursively()
            libraryDir.deleteRecursively()
        }
    }

    /** Waits for the fixture video's grid thumbnail and returns the video's content hash. */
    private suspend fun awaitVideoThumb(server: DesktopServer): String {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val video = server.db.assetFiles().let { dao ->
                (1L..20L).firstNotNullOfOrNull { id -> dao.canonical(id)?.takeIf { it.relPath == Fixtures.VIDEO } }
            }
            if (video != null) {
                val asset = server.db.assets().byId(video.assetId)!!
                val thumb = server.db.thumbnails().find(asset.id, 0)
                if (thumb?.state == 3) return asset.contentHash
            }
            delay(200)
        }
        error("video thumbnail never became ready")
    }

    /** What ffmpeg considers upright: the same frame time the server picks, autorotation on. */
    private fun referenceFrame(ffmpeg: File, video: File): BufferedImage {
        val p = ProcessBuilder(
            ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-ss", "0.200", "-i", video.absolutePath, "-frames:v", "1",
            "-f", "image2pipe", "-c:v", "bmp", "-",
        ).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun rotate180(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            out.setRGB(src.width - 1 - x, src.height - 1 - y, src.getRGB(x, y))
        }
        return out
    }

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
