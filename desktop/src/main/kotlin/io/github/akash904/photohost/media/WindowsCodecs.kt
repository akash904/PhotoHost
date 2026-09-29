package io.github.akash904.photohost.media

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.github.akash904.photohost.core.Log
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "photohost"

/** The Windows codec that would decode this file is not installed, e.g. no HEVC Video Extensions. */
class DecoderUnavailableException(message: String) : Exception(message)

/**
 * Windows' own decoders, for what Java cannot decode itself.
 *
 *  - [decodeImage]: the Windows Imaging Component, which decodes HEIC/HEIF (with the HEIF and HEVC
 *    extensions), AVIF (AV1 extension), camera RAW (Raw Image Extension) and WebP -- the same
 *    decoders Photos and Explorer use, so what Windows can show, PhotoHost can thumbnail.
 *  - [videoThumbnail]: the Shell's thumbnail engine, which renders a frame through Media Foundation
 *    and whichever video decoders the PC has, the thumbnail Explorer itself shows.
 *
 * Nothing is bundled and nothing is licensed by PhotoHost: the codecs are Microsoft's. When one is
 * missing the call throws [DecoderUnavailableException], which parks the thumbnail job until the
 * extension is installed instead of failing it.
 *
 * COM wants a single-threaded apartment, so every call runs on one dedicated thread that stays
 * initialised for the life of the process. That also serialises decoding, which on a PC the size of
 * a thumbnail backlog is gentle rather than slow.
 */
object WindowsCodecs {

    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /** Off in tests that exercise the other paths: the ffmpeg fallback, and "no decoder at all". */
    @Volatile
    var enabled: Boolean = true

    val available: Boolean get() = isWindows && enabled

    private val sta = Executors.newSingleThreadExecutor { r ->
        Thread({
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
            r.run()
        }, "windows-codecs").apply { isDaemon = true }
    }

    private fun <T> onSta(timeoutS: Long = 60, block: () -> T): T {
        check(isWindows) { "not Windows" }
        val f = sta.submit(Callable { block() })
        return try {
            f.get(timeoutS, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ------------------------------------------------------------------ images: WIC

    /**
     * Decodes [file] to at most [maxEdge] pixels on the long edge, in its stored orientation --
     * exactly like a subsampled ImageIO decode, so rotation is applied by the caller from EXIF, the
     * same as for a JPEG.
     */
    fun decodeImage(file: File, maxEdge: Int): BufferedImage = onSta {
        val factory = create(CLSID_WICImagingFactory, IID_IWICImagingFactory, "WIC")
        val cleanup = ArrayList<Com>()
        cleanup += factory
        try {
            val dec = PointerByReference()
            val hr = factory.call(WIC_CREATE_DECODER_FROM_FILENAME, WString(file.absolutePath), null, GENERIC_READ, WIC_DECODE_METADATA_ON_DEMAND, dec)
            if (hr == WINCODEC_ERR_COMPONENTNOTFOUND) {
                throw DecoderUnavailableException(missingCodecHint(file))
            }
            checkHr(hr, "CreateDecoderFromFilename")
            val decoder = Com(dec.value).also { cleanup += it }

            val fr = PointerByReference()
            val hrFrame = decoder.call(DEC_GET_FRAME, 0, fr)
            // The HEIF container decoder is present but its HEVC decoder is not: the frame, not the
            // file, is what fails.
            if (hrFrame == WINCODEC_ERR_COMPONENTNOTFOUND || hrFrame == MF_E_TOPO_CODEC_NOT_FOUND) {
                throw DecoderUnavailableException(missingCodecHint(file))
            }
            checkHr(hrFrame, "GetFrame")
            val frame = Com(fr.value).also { cleanup += it }

            val w = IntByReference()
            val h = IntByReference()
            checkHr(frame.call(SRC_GET_SIZE, w, h), "GetSize")
            val (tw, th) = fit(w.value, h.value, maxEdge)

            var source = frame
            if (tw != w.value || th != h.value) {
                val sc = PointerByReference()
                checkHr(factory.call(WIC_CREATE_BITMAP_SCALER, sc), "CreateBitmapScaler")
                val scaler = Com(sc.value).also { cleanup += it }
                checkHr(scaler.call(SCALER_INITIALIZE, source.pointer, tw, th, WIC_INTERPOLATION_FANT), "Scaler.Initialize")
                source = scaler
            }

            val cv = PointerByReference()
            checkHr(factory.call(WIC_CREATE_FORMAT_CONVERTER, cv), "CreateFormatConverter")
            val converter = Com(cv.value).also { cleanup += it }
            checkHr(
                converter.call(CONVERTER_INITIALIZE, source.pointer, GUID_WICPixelFormat32bppBGRA, 0, null, 0.0, 0),
                "Converter.Initialize",
            )

            val stride = tw * 4
            val buf = Memory(stride.toLong() * th)
            checkHr(converter.call(SRC_COPY_PIXELS, null, stride, (stride.toLong() * th).toInt(), buf), "CopyPixels")
            toImage(buf, tw, th)
        } finally {
            cleanup.asReversed().forEach { runCatching { it.Release() } }
        }
    }

    /** Only the dimensions, without decoding pixels. Null when Windows cannot read the file. */
    fun imageSize(file: File): Pair<Int, Int>? = runCatching {
        onSta {
            val factory = create(CLSID_WICImagingFactory, IID_IWICImagingFactory, "WIC")
            try {
                val dec = PointerByReference()
                checkHr(factory.call(WIC_CREATE_DECODER_FROM_FILENAME, WString(file.absolutePath), null, GENERIC_READ, WIC_DECODE_METADATA_ON_DEMAND, dec), "decoder")
                val decoder = Com(dec.value)
                try {
                    val fr = PointerByReference()
                    checkHr(decoder.call(DEC_GET_FRAME, 0, fr), "frame")
                    val frame = Com(fr.value)
                    try {
                        val w = IntByReference()
                        val h = IntByReference()
                        checkHr(frame.call(SRC_GET_SIZE, w, h), "size")
                        w.value to h.value
                    } finally {
                        frame.Release()
                    }
                } finally {
                    decoder.Release()
                }
            } finally {
                factory.Release()
            }
        }
    }.getOrNull()

    // ------------------------------------------------------------------ video: the Shell thumbnailer

    /**
     * The frame Explorer would show for [file], at most [maxEdge] pixels on the long edge.
     *
     * Unlike [decodeImage] this comes back **already upright**: the Shell applies the video's rotation
     * itself. Callers must not rotate it again.
     */
    fun videoThumbnail(file: File, maxEdge: Int): BufferedImage = onSta {
        val item = PointerByReference()
        val hr = Shell32Ex.INSTANCE.SHCreateItemFromParsingName(WString(file.absolutePath), null, IID_IShellItemImageFactory, item)
        checkHr(hr.toInt(), "SHCreateItemFromParsingName")
        val factory = Com(item.value)
        try {
            val hbm = PointerByReference()
            // SIZE is two 32-bit ints passed by value; on x64 an 8-byte struct travels as one integer.
            val size = (maxEdge.toLong() and 0xffffffffL) or (maxEdge.toLong() shl 32)
            val got = factory.call(IMAGEFACTORY_GET_IMAGE, size, SIIGBF_RESIZETOFIT or SIIGBF_THUMBNAILONLY, hbm)
            if (got < 0 || hbm.value == null) {
                // No thumbnail provider could render it -- most often a codec Windows lacks.
                throw DecoderUnavailableException(
                    "Windows cannot make a thumbnail for ${file.name} (0x%08x). ".format(got) + missingCodecHint(file),
                )
            }
            val bitmap = WinDef.HBITMAP(hbm.value)
            try {
                fromHBitmap(bitmap)
            } finally {
                GDI32.INSTANCE.DeleteObject(bitmap)
            }
        } finally {
            factory.Release()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun fromHBitmap(bitmap: WinDef.HBITMAP): BufferedImage {
        val bm = WinGDI.BITMAP()
        GDI32.INSTANCE.GetObject(bitmap, bm.size(), bm.pointer)
        bm.read()
        val w = bm.bmWidth.toInt()
        val h = bm.bmHeight.toInt()
        val info = WinGDI.BITMAPINFO().apply {
            bmiHeader.biWidth = w
            bmiHeader.biHeight = -h // top-down
            bmiHeader.biPlanes = 1
            bmiHeader.biBitCount = 32
            bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val buf = Memory(w.toLong() * h * 4)
        val dc = com.sun.jna.platform.win32.User32.INSTANCE.GetDC(null)
        try {
            GDI32.INSTANCE.GetDIBits(dc, bitmap, 0, h, buf, info, WinGDI.DIB_RGB_COLORS)
        } finally {
            com.sun.jna.platform.win32.User32.INSTANCE.ReleaseDC(null, dc)
        }
        return toImage(buf, w, h)
    }

    /** BGRA bytes, as both WIC and GDI hand them over, into an opaque INT_RGB image. */
    private fun toImage(buf: Memory, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val dst = (img.raster.dataBuffer as DataBufferInt).data
        val ints = buf.getIntArray(0, w * h)
        // Little-endian BGRA reads as 0xAARRGGBB; transparency goes onto white, as in ImageDecoding.
        for (i in ints.indices) {
            val p = ints[i]
            val a = (p ushr 24) and 0xff
            dst[i] = if (a == 0xff) {
                p and 0xffffff
            } else {
                val r = ((p shr 16) and 0xff) * a / 255 + (255 - a)
                val g = ((p shr 8) and 0xff) * a / 255 + (255 - a)
                val b = (p and 0xff) * a / 255 + (255 - a)
                (r shl 16) or (g shl 8) or b
            }
        }
        return img
    }

    private fun fit(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxEdge) return w to h
        val ratio = maxEdge.toDouble() / long
        return maxOf(1, Math.round(w * ratio).toInt()) to maxOf(1, Math.round(h * ratio).toInt())
    }

    private fun missingCodecHint(file: File): String = when (file.extension.lowercase()) {
        "heic", "heif", "hif" -> "Install \"HEIF Image Extensions\" and \"HEVC Video Extensions\" from the Microsoft Store."
        "avif" -> "Install \"AV1 Video Extension\" from the Microsoft Store."
        "mp4", "mov", "m4v", "3gp", "mkv" -> "The video may be HEVC: install \"HEVC Video Extensions\" from the Microsoft Store."
        "webm" -> "Install \"VP9 Video Extensions\" from the Microsoft Store."
        else -> "Windows has no decoder for this file type; installing \"Raw Image Extension\" covers camera RAW."
    }

    private fun create(clsid: Guid.CLSID, iid: Guid.GUID, what: String): Com {
        val ppv = PointerByReference()
        checkHr(Ole32.INSTANCE.CoCreateInstance(clsid, null, CLSCTX_INPROC_SERVER, iid, ppv).toInt(), "CoCreateInstance $what")
        return Com(ppv.value)
    }

    private fun checkHr(hr: Int, what: String) {
        if (hr < 0) throw IllegalStateException("$what failed: 0x%08x".format(hr))
    }

    private class Com(p: Pointer) : Unknown(p) {
        fun call(slot: Int, vararg args: Any?): Int = _invokeNativeInt(slot, arrayOf(pointer, *args))
    }

    private interface Shell32Ex : StdCallLibrary {
        fun SHCreateItemFromParsingName(path: WString, bindCtx: Pointer?, riid: Guid.GUID, out: PointerByReference): WinNT.HRESULT

        companion object {
            val INSTANCE: Shell32Ex = Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private val CLSID_WICImagingFactory = Guid.CLSID("{cacaf262-9370-4615-a13b-9f5539da4c0a}")
    private val IID_IWICImagingFactory = Guid.GUID("{ec5ec8a9-c395-4314-9c77-54d7a935ff70}")
    private val GUID_WICPixelFormat32bppBGRA = Guid.GUID("{6fddc324-4e03-4bfe-b185-3d77768dc90f}")
    private val IID_IShellItemImageFactory = Guid.GUID("{bcc18b79-ba16-442f-80c4-8a59c30c463b}")

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val GENERIC_READ = 0x80000000.toInt()
    private const val WIC_DECODE_METADATA_ON_DEMAND = 0
    private const val WIC_INTERPOLATION_FANT = 3
    private const val WINCODEC_ERR_COMPONENTNOTFOUND = 0x88982F50.toInt()
    private const val MF_E_TOPO_CODEC_NOT_FOUND = 0xC00D5212.toInt()

    private const val SIIGBF_RESIZETOFIT = 0x0
    private const val SIIGBF_THUMBNAILONLY = 0x8

    // vtable slots (wincodec.h, shobjidl_core.h). IUnknown occupies 0-2 everywhere.
    // IWICImagingFactory: CreateDecoderFromFilename 3, ... CreateFormatConverter 10, CreateBitmapScaler 11.
    private const val WIC_CREATE_DECODER_FROM_FILENAME = 3
    private const val WIC_CREATE_FORMAT_CONVERTER = 10
    private const val WIC_CREATE_BITMAP_SCALER = 11
    // IWICBitmapDecoder: ... GetFrameCount 12, GetFrame 13.
    private const val DEC_GET_FRAME = 13
    // IWICBitmapSource: GetSize 3, GetPixelFormat 4, GetResolution 5, CopyPalette 6, CopyPixels 7.
    private const val SRC_GET_SIZE = 3
    private const val SRC_COPY_PIXELS = 7
    // IWICBitmapScaler / IWICFormatConverter: Initialize 8, after the five IWICBitmapSource methods.
    private const val SCALER_INITIALIZE = 8
    private const val CONVERTER_INITIALIZE = 8
    // IShellItemImageFactory: GetImage 3.
    private const val IMAGEFACTORY_GET_IMAGE = 3

    init {
        if (isWindows) Log.i(TAG, "Windows codecs available for HEIC, AVIF, RAW and video thumbnails")
    }
}
