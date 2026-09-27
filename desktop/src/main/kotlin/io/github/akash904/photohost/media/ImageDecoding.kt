package io.github.akash904.photohost.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream
import kotlin.math.roundToInt

/**
 * ImageIO in place of BitmapFactory, Matrix and Bitmap.compress.
 *
 * Every operation mirrors what the phone does, in the same order, so a thumbnail made here looks
 * like one made there: header-only bounds, a power-of-two subsampled decode (BitmapFactory's
 * `inSampleSize`), then rotation, then scaling, then JPEG at the same quality.
 */
object ImageDecoding {

    init {
        // ImageIO otherwise caches every stream it reads to a temp file on disk. Thumbnailing a
        // library would then write each original a second time, into %TEMP%.
        ImageIO.setUseCache(false)
    }

    /** Whether ImageIO (with TwelveMonkeys on the classpath) has a reader for this type at all. */
    fun canDecode(mime: String): Boolean =
        mime.startsWith("image/") && ImageIO.getImageReadersByMIMEType(mime).hasNext()

    class Bounds(val width: Int, val height: Int)

    fun bounds(input: InputStream): Bounds? = withReader(input) { reader ->
        val w = reader.getWidth(0)
        val h = reader.getHeight(0)
        if (w > 0 && h > 0) Bounds(w, h) else null
    }

    /**
     * Decodes at `1/sample` scale in each dimension. Only every sample-th pixel is materialised, so a
     * 50 MP photo decoded for a 256 px thumbnail never exists at full size in the heap -- the
     * property that `inSampleSize` gives the phone.
     */
    fun decodeSampled(input: InputStream, sample: Int): BufferedImage? = withReader(input) { reader ->
        val param = reader.defaultReadParam
        if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
        toRgb(reader.read(0, param))
    }

    /** The largest power of two that keeps [relevant] at or above [target], as the phone computes it. */
    fun sampleFor(relevant: Int, target: Int): Int {
        var sample = 1
        while (relevant / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Lossless quarter-turn rotation. Anything other than 90/180/270 is returned untouched. */
    fun rotate(src: BufferedImage, degrees: Int): BufferedImage {
        val turn = ((degrees % 360) + 360) % 360
        if (turn != 90 && turn != 180 && turn != 270) return src
        val img = toRgb(src)
        val w = img.width
        val h = img.height
        val input = (img.raster.dataBuffer as DataBufferInt).data
        val outW = if (turn == 180) w else h
        val outH = if (turn == 180) h else w
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val output = (out.raster.dataBuffer as DataBufferInt).data
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = input[row + x]
                when (turn) {
                    // Clockwise, which is what EXIF orientation 6 ("rotate 90") asks for.
                    90 -> output[x * outW + (h - 1 - y)] = p
                    180 -> output[(h - 1 - y) * outW + (w - 1 - x)] = p
                    else -> output[(w - 1 - x) * outW + y] = p
                }
            }
        }
        return out
    }

    /**
     * Scales to exactly [targetW] x [targetH].
     *
     * Halving in steps before the final bilinear pass: a single bilinear pass from much larger
     * only samples a few source pixels per output pixel and aliases badly, which on photos shows
     * up as shimmering edges and moire.
     */
    fun scale(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
        var current = toRgb(src)
        if (current.width == targetW && current.height == targetH) return current
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            current = draw(current, current.width / 2, current.height / 2)
        }
        return draw(current, targetW, targetH)
    }

    /** Scales so the long edge is [longEdge], never enlarging. */
    fun fitLongEdge(src: BufferedImage, longEdge: Int): BufferedImage {
        val w = src.width
        val h = src.height
        if (maxOf(w, h) <= longEdge) return toRgb(src)
        val ratio = longEdge.toFloat() / maxOf(w, h)
        return scale(src, maxOf(1, (w * ratio).roundToInt()), maxOf(1, (h * ratio).roundToInt()))
    }

    fun pixels(img: BufferedImage): IntArray {
        val rgb = toRgb(img)
        return (rgb.raster.dataBuffer as DataBufferInt).data.copyOf()
    }

    /**
     * Writes a JPEG atomically: to a temporary name in the same directory, then renamed over the
     * destination. A crash mid-write leaves a stray temp file, never a truncated thumbnail under the
     * real name for the server to hand out.
     */
    fun writeJpeg(img: BufferedImage, dest: File, quality: Int) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(tmp).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }
                writer.write(null, IIOImage(toRgb(img), null, null), param)
            }
        } finally {
            writer.dispose()
        }
        Files.move(
            tmp.toPath(), dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun <T> withReader(input: InputStream, block: (ImageReader) -> T?): T? {
        val iis: ImageInputStream = ImageIO.createImageInputStream(BufferedInputStream(input, 64 * 1024))
            ?: return null
        iis.use {
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                // seekForwardOnly: a thumbnail decode never goes back, so ImageIO need not keep
                // everything it has read. Metadata is kept, because TwelveMonkeys needs the APP14 and
                // ICC segments to decode CMYK JPEGs correctly.
                reader.setInput(iis, true, false)
                return block(reader)
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * Flattens anything to opaque INT_RGB. Transparency goes onto white. (The phone's
     * `Bitmap.compress(JPEG)` drops alpha to black instead; white reads better for logos and
     * screenshots, and thumbnails are per-server anyway.)
     */
    private fun toRgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, src.width, src.height)
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun draw(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(maxOf(1, w), maxOf(1, h), BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, out.width, out.height, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
