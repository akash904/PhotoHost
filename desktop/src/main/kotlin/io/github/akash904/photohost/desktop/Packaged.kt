package io.github.akash904.photohost.desktop

import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Whether this PhotoHost runs from the Microsoft Store package (MSIX) rather than the setup .exe.
 *
 * A package changes the rules for three things this app does. Registry writes under HKCU go to a
 * private copy, so the Run key Windows reads at sign-in is never touched; a StartupTask in the
 * manifest replaces it. The install folder's name carries the version, so a firewall rule naming the
 * exe stops matching at the next update; the manifest declares the rule instead. And
 * %LOCALAPPDATA%\PhotoHost is redirected into the package and removed with it.
 */
object Packaged {

    private const val APPMODEL_ERROR_NO_PACKAGE = 15700
    private const val ERROR_INSUFFICIENT_BUFFER = 122

    /** Matches the StartupTask's TaskId in packaging/msix/AppxManifest.xml. */
    const val STARTUP_TASK_ID = "PhotoHostAtSignIn"

    @Suppress("FunctionName")
    private interface AppModel : StdCallLibrary {
        fun GetCurrentPackageFamilyName(length: IntByReference, name: CharArray?): Int
    }

    private val lib by lazy { Native.load("kernel32", AppModel::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    /** Windows' answer to "which package am I in", for tests: 15700 outside a package. */
    internal fun probe(): Int = lib.GetCurrentPackageFamilyName(IntByReference(0), null)

    /** The package family name, e.g. AkashVerma.PhotoHost_xxxxxxxxxxxxx, or null when not packaged. */
    val familyName: String? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) return@lazy null
        runCatching {
            // Absent before Windows 8; the UnsatisfiedLinkError lands in runCatching.
            val length = IntByReference(0)
            when (lib.GetCurrentPackageFamilyName(length, null)) {
                APPMODEL_ERROR_NO_PACKAGE -> null
                ERROR_INSUFFICIENT_BUFFER -> {
                    val buf = CharArray(length.value)
                    if (lib.GetCurrentPackageFamilyName(length, buf) == 0) Native.toString(buf) else null
                }
                else -> null
            }
        }.getOrNull()
    }

    val isPackaged: Boolean get() = familyName != null
}
