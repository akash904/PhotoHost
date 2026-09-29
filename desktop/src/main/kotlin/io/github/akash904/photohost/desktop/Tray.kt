package io.github.akash904.photohost.desktop

import io.github.akash904.photohost.core.Log
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

private const val TAG = "photohost"

/**
 * PhotoHost's icon next to the clock, which is where a server with no window lives.
 *
 * A photo library is only useful while it is being served, so closing the window must not stop it
 * -- that is what the tray is for: the window can go away and the server carries on, and the icon is
 * how you get the window back or quit for real.
 */
class Tray(
    private val onOpen: () -> Unit,
    private val onOpenBrowser: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var icon: TrayIcon? = null

    /** False when this desktop has no notification area; the caller then keeps close = quit. */
    val available: Boolean get() = icon != null

    fun install(): Boolean {
        if (!SystemTray.isSupported()) {
            Log.w(TAG, "no system tray on this desktop; closing the window will quit")
            return false
        }
        val tray = SystemTray.getSystemTray()
        // Rendered at the size the tray actually uses, so Windows does not scale a large bitmap down
        // into a blur at 125% and 150% display scaling.
        val size = tray.trayIconSize
        val menu = PopupMenu().apply {
            add(MenuItem("Open PhotoHost").apply { addActionListener { onOpen() } })
            add(MenuItem("Open library in browser").apply { addActionListener { onOpenBrowser() } })
            addSeparator()
            add(MenuItem("Quit PhotoHost").apply { addActionListener { onQuit() } })
        }
        val trayIcon = TrayIcon(AppIcon.render(maxOf(size.width, size.height)), "PhotoHost", menu).apply {
            isImageAutoSize = true
            // Double-click on Windows; a single left click also opens, as most tray apps do.
            addActionListener { onOpen() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) onOpen()
                }
            })
        }
        return try {
            tray.add(trayIcon)
            icon = trayIcon
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not add tray icon", t)
            false
        }
    }

    fun tooltip(text: String) {
        icon?.toolTip = text
    }

    /** A one-line notice from the tray, e.g. the first time the window is closed. */
    fun notify(title: String, message: String) {
        icon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }
}
