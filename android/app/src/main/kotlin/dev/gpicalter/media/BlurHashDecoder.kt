package dev.gpicalter.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign

/**
 * Decodes a BlurHash into a tiny bitmap, for use as a grid placeholder.
 *
 * This is what stops the timeline flashing grey boxes. Every timeline row already carries its
 * blurhash, so a tile can be painted at the correct size and roughly the correct colours on the
 * very first frame -- before any thumbnail request is even made. The thumbnail then fades in over
 * it, and nothing ever shifts position.
 *
 * Mirrors [BlurHash] on the encoding side; decoded bitmaps are cached because a scrolling grid
 * re-binds the same items constantly.
 */
object BlurHashDecoder {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > 256
    }

    fun decode(hash: String?, width: Int = 24, height: Int = 24): Bitmap? {
        if (hash.isNullOrBlank() || hash.length < 6) return null
        val key = "$hash:$width:$height"
        synchronized(cache) { cache[key]?.let { return it } }

        val sizeFlag = decode83(hash.substring(0, 1)) ?: return null
        val numX = (sizeFlag % 9) + 1
        val numY = (sizeFlag / 9) + 1
        if (hash.length != 4 + 2 * numX * numY) return null

        val quantMax = decode83(hash.substring(1, 2)) ?: return null
        val maxValue = (quantMax + 1) / 166f

        val colors = Array(numX * numY) { FloatArray(3) }
        val dc = decode83(hash.substring(2, 6)) ?: return null
        colors[0] = floatArrayOf(
            srgbToLinear(dc shr 16),
            srgbToLinear((dc shr 8) and 255),
            srgbToLinear(dc and 255),
        )
        for (i in 1 until numX * numY) {
            val v = decode83(hash.substring(4 + i * 2, 6 + i * 2)) ?: return null
            colors[i] = floatArrayOf(
                signedPow((v / (19 * 19) - 9) / 9f) * maxValue,
                signedPow(((v / 19) % 19 - 9) / 9f) * maxValue,
                signedPow((v % 19 - 9) / 9f) * maxValue,
            )
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    val cosY = cos(Math.PI * y * j / height).toFloat()
                    for (i in 0 until numX) {
                        val basis = cos(Math.PI * x * i / width).toFloat() * cosY
                        val c = colors[i + j * numX]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[x + y * width] = Color.rgb(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    private fun decode83(s: String): Int? {
        var value = 0
        for (c in s) {
            val i = ALPHABET.indexOf(c)
            if (i < 0) return null
            value = value * 83 + i
        }
        return value
    }

    private fun signedPow(v: Float): Float = v.sign * v.absoluteValue.pow(2f)

    private fun srgbToLinear(component: Int): Float {
        val v = component / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val out = if (v <= 0.0031308f) v * 12.92f * 255f else (1.055f * v.pow(1 / 2.4f) - 0.055f) * 255f
        return (out + 0.5f).toInt().coerceIn(0, 255)
    }
}
