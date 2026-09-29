package io.github.akash904.photohost.media

import io.github.akash904.photohost.core.Log
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private const val TAG = "photohost"

/**
 * Pulls one frame out of a video with ffmpeg, run as a separate process.
 *
 * The desktop's replacement for MediaMetadataRetriever. Out of process on purpose, and for the same
 * reason Android's retriever is: a decoder that crashes or hangs on a malformed file takes down a
 * child process, not the server. A frame that takes longer than [TIMEOUT_S] is abandoned.
 *
 * The frame comes back in its *stored* orientation (`-noautorotate`), exactly like a decoded
 * JPEG, so ThumbnailGenerator rotates videos and photos by the same code path and the same
 * `orientation` value the index holds.
 */
class VideoFrames(val ffmpeg: File?) {

    val available: Boolean get() = ffmpeg != null

    /**
     * @param durationMs used to pick the frame: 10% in, capped at one second, as on the phone,
     *   because frame zero is so often a black fade-in.
     */
    fun frame(video: Path, durationMs: Long?): BufferedImage? {
        val exe = ffmpeg ?: return null
        val atMs = minOf(1_000L, (durationMs ?: 0L) / 10)
        return grab(exe, video, atMs) ?: if (atMs > 0) grab(exe, video, 0) else null
    }

    private fun grab(exe: File, video: Path, atMs: Long): BufferedImage? {
        val cmd = listOf(
            exe.absolutePath,
            "-hide_banner", "-loglevel", "error", "-nostdin",
            "-noautorotate",
            // Before -i: a fast keyframe seek, the equivalent of the phone's OPTION_CLOSEST_SYNC.
            "-ss", "%.3f".format(java.util.Locale.US, atMs / 1000.0),
            "-i", video.toAbsolutePath().toString(),
            "-frames:v", "1",
            // Never larger than the PREVIEW size, never enlarged: a 4K frame would otherwise cross
            // the pipe and the heap at full size only to be scaled down immediately.
            "-vf", "scale=w='min(iw,1440)':h='min(ih,1440)':force_original_aspect_ratio=decrease",
            // BMP: uncompressed, so ffmpeg spends no time encoding what Java decodes at once.
            "-f", "image2pipe", "-c:v", "bmp", "-",
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val errors = StringBuilder()
        val stderr = Thread { runCatching { errors.append(process.errorStream.bufferedReader().readText()) } }
            .apply { isDaemon = true; start() }
        val out = java.io.ByteArrayOutputStream()
        val stdout = Thread { runCatching { process.inputStream.copyTo(out) } }
            .apply { isDaemon = true; start() }

        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Log.w(TAG, "ffmpeg timed out after ${TIMEOUT_S}s on $video")
            return null
        }
        stdout.join(5_000)
        stderr.join(1_000)
        if (process.exitValue() != 0 || out.size() == 0) {
            Log.w(TAG, "ffmpeg exit ${process.exitValue()} on $video: ${errors.toString().trim().take(300)}")
            return null
        }
        return ImageIO.read(ByteArrayInputStream(out.toByteArray()))
    }

    companion object {
        const val TIMEOUT_S = 30L

        /**
         * Where ffmpeg is, in order: an explicit setting ("none" turns video frames off), a copy
         * shipped next to the app (`<app folder>\ffmpeg\ffmpeg.exe`), then the PATH.
         */
        fun locate(setting: String?): VideoFrames {
            if (setting.equals("none", ignoreCase = true)) return VideoFrames(null)
            setting?.let { File(it) }?.takeIf { it.isFile }?.let { return VideoFrames(it) }

            System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
                ?.let { File(it, "ffmpeg/ffmpeg.exe") }
                ?.takeIf { it.isFile }
                ?.let { return VideoFrames(it) }

            val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("ffmpeg.exe") else listOf("ffmpeg")
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).forEach { dir ->
                if (dir.isBlank()) return@forEach
                for (n in names) {
                    val f = File(dir.trim('"'), n)
                    if (f.isFile) return VideoFrames(f)
                }
            }
            return VideoFrames(null)
        }
    }
}
