package dev.gpicalter.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dev.gpicalter.core.Log
import java.io.File

private const val TAG = "gpic"

/**
 * "Start PhotoHost when I sign in to Windows".
 *
 * The per-user Run key, not a service and not the Startup folder: it needs no administrator
 * rights, it is exactly what Settings > Apps > Startup lists and lets the user switch off, and it
 * starts PhotoHost as the signed-in user, who can read their own photo folders.
 *
 * Only offered when running as the packaged PhotoHost.exe. A development run from Gradle has no
 * stable executable to point Windows at.
 */
object Autostart {

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "PhotoHost"

    /** Starts in the tray rather than opening the window, which is what a login start should do. */
    const val MINIMIZED_FLAG = "--minimized"

    /** The packaged executable, or null when running from a build. */
    val executable: File?
        get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    val supported: Boolean
        get() = executable != null && System.getProperty("os.name").startsWith("Windows")

    fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
    }.getOrDefault(false)

    /** Returns whether the change took. */
    fun setEnabled(on: Boolean): Boolean {
        val exe = executable ?: return false
        return try {
            if (on) {
                // Quoted: the path has spaces whenever PhotoHost is under "Program Files" or a
                // profile folder with a space in the user name.
                Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, "\"${exe.absolutePath}\" $MINIMIZED_FLAG",
                )
            } else if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
            Log.i(TAG, "start at sign-in ${if (on) "on" else "off"}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not change start at sign-in", t)
            false
        }
    }

    /**
     * Keeps an enabled entry pointing at this copy of PhotoHost. If the folder was moved, or a new
     * version installed elsewhere, the old entry would start nothing at the next sign-in.
     */
    fun refreshIfEnabled() {
        if (supported && isEnabled()) setEnabled(true)
    }
}
