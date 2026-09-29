package io.github.akash904.photohost.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import io.github.akash904.photohost.core.Log
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import javax.swing.SwingUtilities

private const val TAG = "photohost"

/**
 * The real Windows file dialog -- IFileOpenDialog, the one Explorer and every current Windows app
 * uses, with the address bar, search, Quick access and thumbnails.
 *
 * Swing's JFileChooser, even with the Windows look and feel, is Swing drawing its own imitation of
 * the XP-era dialog, and AWT's FileDialog cannot choose a folder at all. Neither is what a Windows
 * user expects to see. This calls the COM interface directly through JNA.
 *
 * Every call returns [Result.Unavailable] rather than throwing when the native dialog cannot be
 * used -- not Windows, a COM failure -- so the caller can fall back to JFileChooser instead of
 * leaving the button dead.
 */
object NativeFileDialog {

    sealed interface Result {
        data class Picked(val files: List<File>) : Result
        data object Cancelled : Result
        data class Unavailable(val reason: String) : Result
    }

    class Filter(val name: String, val patterns: List<String>)

    /** One folder. */
    fun pickFolder(owner: Component?, title: String, okLabel: String, startIn: File? = null): Result =
        show(owner, title, okLabel, startIn, folders = true, multiple = false, filters = emptyList())

    /** One or more files. */
    fun pickFiles(owner: Component?, title: String, okLabel: String, startIn: File? = null, filters: List<Filter>): Result =
        show(owner, title, okLabel, startIn, folders = false, multiple = true, filters = filters)

    // ------------------------------------------------------------------ plumbing

    private fun show(
        owner: Component?,
        title: String,
        okLabel: String,
        startIn: File?,
        folders: Boolean,
        multiple: Boolean,
        filters: List<Filter>,
    ): Result {
        if (!System.getProperty("os.name").startsWith("Windows")) return Result.Unavailable("not Windows")
        val window: Window? = owner?.let { if (it is Window) it else SwingUtilities.getWindowAncestor(it) }
        val hwnd: Pointer? = window?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }

        // COM needs a single-threaded apartment of its own. The dialog runs on a dedicated thread
        // while the Swing thread keeps pumping events through a secondary loop, so the window
        // behind stays painted instead of turning white; owning the dialog by HWND keeps it modal.
        var result: Result = Result.Unavailable("dialog did not run")
        val onSwingThread = SwingUtilities.isEventDispatchThread()
        val loop = if (onSwingThread) Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop() else null
        val thread = Thread({
            result = try {
                runDialog(hwnd, title, okLabel, startIn, folders, multiple, filters)
            } catch (t: Throwable) {
                Log.w(TAG, "native file dialog failed; falling back", t)
                Result.Unavailable("${t.javaClass.simpleName}: ${t.message}")
            } finally {
                loop?.exit()
            }
        }, "file-dialog")
        thread.isDaemon = true
        thread.start()
        if (loop != null) loop.enter() else thread.join()
        thread.join()
        return result
    }

    private fun runDialog(
        hwnd: Pointer?,
        title: String,
        okLabel: String,
        startIn: File?,
        folders: Boolean,
        multiple: Boolean,
        filters: List<Filter>,
    ): Result {
        val init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE)
        if (init.toInt() < 0) return Result.Unavailable("CoInitializeEx 0x%08x".format(init.toInt()))
        try {
            val ppv = PointerByReference()
            val created = Ole32.INSTANCE.CoCreateInstance(CLSID_FileOpenDialog, null, CLSCTX_INPROC_SERVER, IID_IFileOpenDialog, ppv)
            if (created.toInt() < 0) return Result.Unavailable("CoCreateInstance 0x%08x".format(created.toInt()))
            val dialog = Com(ppv.value)
            try {
                val opts = IntByReference()
                check(dialog.call(VT_GET_OPTIONS, opts), "GetOptions")
                var flags = opts.value or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST
                if (folders) flags = flags or FOS_PICKFOLDERS
                if (!folders) flags = flags or FOS_FILEMUSTEXIST
                if (multiple) flags = flags or FOS_ALLOWMULTISELECT
                check(dialog.call(VT_SET_OPTIONS, flags), "SetOptions")
                dialog.call(VT_SET_TITLE, WString(title))
                dialog.call(VT_SET_OK_BUTTON_LABEL, WString(okLabel))

                // COMDLG_FILTERSPEC[]: pairs of wide-string pointers. Memory objects are held in
                // [keep] until the dialog closes, or the strings could be freed underneath it.
                val keep = ArrayList<Memory>()
                if (filters.isNotEmpty()) {
                    val specs = Memory(Native.POINTER_SIZE.toLong() * 2 * filters.size).also { keep += it }
                    filters.forEachIndexed { i, f ->
                        val name = wide(f.name).also { keep += it }
                        val spec = wide(f.patterns.joinToString(";")).also { keep += it }
                        specs.setPointer(Native.POINTER_SIZE.toLong() * (2L * i), name)
                        specs.setPointer(Native.POINTER_SIZE.toLong() * (2L * i + 1), spec)
                    }
                    dialog.call(VT_SET_FILE_TYPES, filters.size, specs)
                }

                startIn?.takeIf { it.exists() }?.let { dir ->
                    val item = PointerByReference()
                    val hr = Shell32Ex.INSTANCE.SHCreateItemFromParsingName(WString(dir.absolutePath), null, IID_IShellItem, item)
                    if (hr.toInt() >= 0 && item.value != null) {
                        val shell = Com(item.value)
                        dialog.call(VT_SET_FOLDER, shell.pointer)
                        shell.Release()
                    }
                }

                val shown = dialog.call(VT_SHOW, hwnd)
                if (shown == HRESULT_CANCELLED) return Result.Cancelled
                if (shown < 0) return Result.Unavailable("Show 0x%08x".format(shown))

                val picked = ArrayList<File>()
                if (multiple) {
                    val arr = PointerByReference()
                    check(dialog.call(VT_GET_RESULTS, arr), "GetResults")
                    val items = Com(arr.value)
                    try {
                        val count = IntByReference()
                        check(items.call(VT_ARRAY_GET_COUNT, count), "GetCount")
                        for (i in 0 until count.value) {
                            val one = PointerByReference()
                            check(items.call(VT_ARRAY_GET_ITEM_AT, i, one), "GetItemAt")
                            pathOf(Com(one.value))?.let { picked += File(it) }
                        }
                    } finally {
                        items.Release()
                    }
                } else {
                    val one = PointerByReference()
                    check(dialog.call(VT_GET_RESULT, one), "GetResult")
                    pathOf(Com(one.value))?.let { picked += File(it) }
                }
                keep.clear()
                return if (picked.isEmpty()) Result.Cancelled else Result.Picked(picked)
            } finally {
                dialog.Release()
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    /** A filesystem path from an IShellItem, releasing the item. */
    private fun pathOf(item: Com): String? = try {
        val out = PointerByReference()
        if (item.call(VT_ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, out) < 0 || out.value == null) {
            null
        } else {
            val s = out.value.getWideString(0)
            Ole32.INSTANCE.CoTaskMemFree(out.value)
            s
        }
    } finally {
        item.Release()
    }

    private fun wide(s: String): Memory = Memory((s.length + 1L) * 2).apply { setWideString(0, s) }

    private fun check(hr: Int, what: String) {
        if (hr < 0) throw IllegalStateException("$what failed: 0x%08x".format(hr))
    }

    /** Calls a COM method by vtable slot, passing `this` first as COM requires. */
    private class Com(p: Pointer) : Unknown(p) {
        fun call(slot: Int, vararg args: Any?): Int = _invokeNativeInt(slot, arrayOf(pointer, *args))
    }

    private interface Shell32Ex : StdCallLibrary {
        fun SHCreateItemFromParsingName(path: WString, bindCtx: Pointer?, riid: Guid.GUID, out: PointerByReference): com.sun.jna.platform.win32.WinNT.HRESULT

        companion object {
            val INSTANCE: Shell32Ex = Native.load("shell32", Shell32Ex::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private val CLSID_FileOpenDialog = Guid.CLSID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
    private val IID_IFileOpenDialog = Guid.GUID("{d57c7288-d4ad-4768-be02-9d969532d960}")
    private val IID_IShellItem = Guid.GUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")

    private const val CLSCTX_INPROC_SERVER = WTypes.CLSCTX_INPROC_SERVER
    private const val COINIT_DISABLE_OLE1DDE = 0x4

    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_ALLOWMULTISELECT = 0x200
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000

    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    /** HRESULT_FROM_WIN32(ERROR_CANCELLED): the user closed the dialog. */
    private const val HRESULT_CANCELLED = 0x800704C7.toInt()

    // vtable slots. IUnknown 0-2, IModalWindow::Show 3, then IFileDialog in declaration order,
    // then IFileOpenDialog (shobjidl_core.h).
    private const val VT_SHOW = 3
    private const val VT_SET_FILE_TYPES = 4
    private const val VT_SET_OPTIONS = 9
    private const val VT_GET_OPTIONS = 10
    private const val VT_SET_FOLDER = 12
    private const val VT_SET_TITLE = 17
    private const val VT_SET_OK_BUTTON_LABEL = 18
    private const val VT_GET_RESULT = 20
    private const val VT_GET_RESULTS = 27

    // IShellItem: IUnknown 0-2, BindToHandler 3, GetParent 4, GetDisplayName 5.
    private const val VT_ITEM_GET_DISPLAY_NAME = 5

    // IShellItemArray: IUnknown 0-2, BindToHandler 3, GetPropertyStore 4,
    // GetPropertyDescriptionList 5, GetAttributes 6, GetCount 7, GetItemAt 8.
    private const val VT_ARRAY_GET_COUNT = 7
    private const val VT_ARRAY_GET_ITEM_AT = 8
}
