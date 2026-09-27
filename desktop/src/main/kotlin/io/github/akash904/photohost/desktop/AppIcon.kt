package io.github.akash904.photohost.desktop

import java.awt.Color
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The phone app's launcher icon, drawn for Windows.
 *
 * A transcription of the phone app's adaptive icon -- res/drawable/ic_launcher_background.xml and
 * ic_launcher_foreground.xml, colours from res/values/colors.xml -- not a new design. Same
 * coordinates, in the same 108-unit space. Change it there first; this must follow.
 *
 * Android masks the icon to whatever shape the launcher uses. Windows applies no mask, so the tile
 * is cut here the way the icon design page (design/photohost-icon.html, not in the repository) previews its
 * "rounded" mask: the 72-unit safe zone at 18..90, corners at 16% of the edge.
 */
object AppIcon {

    private val CORAL = Color(0xFF5E5B)
    private val BLUE = Color(0x2EA9E0)
    private val YELLOW = Color(0xFFD23F)
    private val VIOLET = Color(0x8E6BE0)
    private val ORANGE = Color(0xFF9F1C)
    private val GREEN = Color(0x3FBF87)
    private val CORE = Color(0x443C7A)

    /** One print: a 28-unit square with 7-unit corner radii, as in the path data. */
    private fun print(x: Double, y: Double) = Area(RoundRectangle2D.Double(x, y, 28.0, 28.0, 14.0, 14.0))

    private fun meet(vararg areas: Area): Area = Area(areas[0]).apply { areas.drop(1).forEach { intersect(it) } }

    /** Renders the icon at [size] x [size] pixels, transparent outside the rounded tile. */
    fun render(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // Map the 72-unit safe zone (18..90 in the 108 viewport) onto the image.
            val scale = size / 72.0
            g.transform = AffineTransform.getScaleInstance(scale, scale).apply { translate(-18.0, -18.0) }

            val tile = Area(RoundRectangle2D.Double(18.0, 18.0, 72.0, 72.0, 72 * 0.32, 72 * 0.32))
            g.clip(tile)

            // Background: radial gradient centred at (54, 50), radius 76.
            g.paint = RadialGradientPaint(
                Point2D.Double(54.0, 50.0), 76f,
                floatArrayOf(0f, 0.65f, 1f),
                arrayOf(Color(0xFFFFFF), Color(0xFBFCFE), Color(0xEDF1F6)),
                MultipleGradientPaint.CycleMethod.NO_CYCLE,
            )
            g.fill(Rectangle2D.Double(0.0, 0.0, 108.0, 108.0))

            val top = print(40.0, 28.0)
            val left = print(30.0, 46.0)
            val right = print(50.0, 46.0)

            // Same painting order as the XML: the three prints, the three pairwise overlaps, then
            // the three-way core last so it survives the pairs painted over it.
            g.color = CORAL; g.fill(top)
            g.color = BLUE; g.fill(left)
            g.color = YELLOW; g.fill(right)
            g.color = VIOLET; g.fill(meet(top, left))
            g.color = ORANGE; g.fill(meet(top, right))
            g.color = GREEN; g.fill(meet(left, right))
            g.color = CORE; g.fill(meet(top, left, right))
        } finally {
            g.dispose()
        }
        return img
    }

    /** The sizes Windows asks for: title bar, taskbar, Explorer views, up to the 256 "jumbo" view. */
    val ICO_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

    /**
     * Writes a Windows .ico holding one PNG-compressed image per size -- the format Windows has read
     * since Vista, and the only one that keeps the 256 px entry a sensible size.
     */
    fun writeIco(dest: File) {
        val pngs = ICO_SIZES.map { s -> s to ByteArrayOutputStream().also { ImageIO.write(render(s), "png", it) }.toByteArray() }
        val header = 6 + 16 * pngs.size
        val buf = ByteBuffer.allocate(header + pngs.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0).putShort(1).putShort(pngs.size.toShort())
        var offset = header
        for ((s, png) in pngs) {
            buf.put((if (s >= 256) 0 else s).toByte())   // width; 0 means 256
            buf.put((if (s >= 256) 0 else s).toByte())   // height
            buf.put(0).put(0)                             // palette size, reserved
            buf.putShort(1).putShort(32)                  // colour planes, bits per pixel
            buf.putInt(png.size).putInt(offset)
            offset += png.size
        }
        pngs.forEach { buf.put(it.second) }
        dest.parentFile?.mkdirs()
        dest.writeBytes(buf.array())
    }
}

/** Build step: `AppIconKt <output.ico> [preview.png]`. Run by the generateIcon Gradle task. */
fun main(args: Array<String>) {
    val ico = File(args.getOrNull(0) ?: error("usage: <output.ico> [preview.png]"))
    AppIcon.writeIco(ico)
    args.getOrNull(1)?.let { ImageIO.write(AppIcon.render(512), "png", File(it)) }
    println("wrote $ico")
}
