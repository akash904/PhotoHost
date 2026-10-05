package io.github.akash904.photohost.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.akash904.photohost.core.Log
import java.io.File

private const val TAG = "photohost"

/**
 * "Start PhotoHost when I sign in to Windows".
 *
 * The per-user Run key, not a service and not the Startup folder: it needs no administrator
 * rights, it is exactly what Settings > Apps > Startup lists and lets the user switch off, and it
 * starts PhotoHost as the signed-in user, who can read their own photo folders.
 *
 * Only offered when running as the packaged PhotoHost.exe. A development run from Gradle has no
 * stable executable to point Windows at.
 *
 * The Microsoft Store package is different (see [Packaged]): the Run key would land in the package's
 * private registry, so its manifest declares a StartupTask, off until the user turns it on. Turning
 * it on from code takes the WinRT StartupTask API, which a JVM cannot reach without a native bridge;
 * Settings > Apps > Startup lists the task and switches it, so [setEnabled] opens that page instead
 * and [isEnabled] reads back what the user chose there.
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
        val family = Packaged.familyName
        if (family != null) {
            // Where Windows keeps a packaged app's startup task state: 2 = enabled, 4 = enabled by
            // policy; 0 disabled, 1 disabled by the user, 3 disabled by policy. No value yet = the
            // manifest's default, which is off.
            val key = "$STARTUP_STATE_KEY\\$family\\${Packaged.STARTUP_TASK_ID}"
            Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key) &&
                Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "State") &&
                Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, "State") in setOf(2, 4)
        } else {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
        }
    }.getOrDefault(false)

    /** True when [setEnabled] only opens Windows' own switch rather than changing anything itself. */
    val managedByWindows: Boolean get() = Packaged.isPackaged

    /** Returns whether the change took. */
    fun setEnabled(on: Boolean): Boolean {
        if (managedByWindows) {
            openStartupSettings()
            return false
        }
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
        if (supported && !managedByWindows && isEnabled()) setEnabled(true)
    }

    private const val STARTUP_STATE_KEY =
        "Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\SystemAppData"

    private fun openStartupSettings() {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:startupapps")) }
            .onFailure { Log.w(TAG, "could not open Startup settings", it) }
    }
}
