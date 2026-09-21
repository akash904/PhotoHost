package dev.gpicalter.media

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * BlurHash encoder: a ~28 character string describing a blurred version of an image.
 *
 * This is what makes a photo grid feel instant. Every timeline row ships its blurhash inline, so
 * the grid paints a real approximation of each photo on first render -- correct colours, correct
 * aspect, no grey boxes, no layout shift, and no second round-trip. Thumbnails then swap in as they
 * arrive. Without it, scrolling a fresh library means a screen of placeholders and a request storm.
 *
 * Implemented from the BlurHash spec rather than pulled in as a dependency: it is one small pure
 * function, and the algorithm is a discrete cosine transform over a handful of basis functions.
 */
object BlurHash {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /**
     * Encodes [bitmap] with [componentsX] x [componentsY] components (4x3 is the usual default).
     *
     * The bitmap should already be small -- 32px on the long edge is plenty, and the cost is
     * O(width * height * componentsX * componentsY), so encoding a full-size image would be
     * absurdly expensive for a result that is deliberately blurry.
     */
    fun encode(bitmap: Bitmap, componentsX: Int = 4, componentsY: Int = 3): String? {
        if (componentsX !in 1..9 || componentsY !in 1..9) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Linearised sRGB, cached once: every basis function reads every pixel.
        val linear = FloatArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            linear[i * 3] = srgbToLinear((p shr 16) and 0xFF)
            linear[i * 3 + 1] = srgbToLinear((p shr 8) and 0xFF)
            linear[i * 3 + 2] = srgbToLinear(p and 0xFF)
        }

        val factors = Array(componentsX * componentsY) { FloatArray(3) }
        for (y in 0 until componentsY) {
            for (x in 0 until componentsX) {
                val normalisation = if (x == 0 && y == 0) 1f else 2f
                var r = 0f
                var g = 0f
                var b = 0f
                for (py in 0 until h) {
                    val cosY = cos(PI * y * py / h).toFloat()
                    for (px in 0 until w) {
                        val basis = cos(PI * x * px / w).toFloat() * cosY
                        val o = (py * w + px) * 3
                        r += basis * linear[o]
                        g += basis * linear[o + 1]
                        b += basis * linear[o + 2]
                    }
                }
                val scale = normalisation / (w * h)
                factors[y * componentsX + x] = floatArrayOf(r * scale, g * scale, b * scale)
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)

        val sb = StringBuilder()
        val sizeFlag = (componentsX - 1) + (componentsY - 1) * 9
        sb.append(encode83(sizeFlag, 1))

        val maximumValue: Float
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { c -> c.maxOf { it.absoluteValue } }
            val quantisedMax = ((actualMax * 166f - 0.5f).toInt()).coerceIn(0, 82)
            maximumValue = (quantisedMax + 1) / 166f
            sb.append(encode83(quantisedMax, 1))
        } else {
            maximumValue = 1f
            sb.append(encode83(0, 1))
        }

        sb.append(encode83(encodeDc(dc), 4))
        for (c in ac) sb.append(encode83(encodeAc(c, maximumValue), 2))
        return sb.toString()
    }

    private fun encodeDc(c: FloatArray): Int {
        val r = linearToSrgb(c[0])
        val g = linearToSrgb(c[1])
        val b = linearToSrgb(c[2])
        return (r shl 16) + (g shl 8) + b
    }

    private fun encodeAc(c: FloatArray, maximumValue: Float): Int {
        fun q(v: Float): Int =
            (((v / maximumValue).sign * (v / maximumValue).absoluteValue.pow(0.5f)) * 9f + 9.5f)
                .toInt().coerceIn(0, 18)
        return q(c[0]) * 19 * 19 + q(c[1]) * 19 + q(c[2])
    }

    private fun encode83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            sb.append(ALPHABET[digit])
        }
        return sb.toString()
    }

    private fun srgbToLinear(component: Int): Float {
        val v = component / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) {
            (v * 12.92f * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        } else {
            ((1.055f * v.pow(1 / 2.4f) - 0.055f) * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        }
    }

    /** Downscales so encoding stays cheap; the result is blurry by definition. */
    fun scaleForEncoding(source: Bitmap, longEdge: Int = 32): Bitmap {
        val w = source.width
        val h = source.height
        if (max(w, h) <= longEdge) return source
        val ratio = longEdge.toFloat() / max(w, h)
        return Bitmap.createScaledBitmap(
            source,
            maxOf(1, (w * ratio).roundToInt()),
            maxOf(1, (h * ratio).roundToInt()),
            true,
        )
    }
}
