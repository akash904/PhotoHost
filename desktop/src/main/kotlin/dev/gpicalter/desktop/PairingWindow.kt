package dev.gpicalter.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/**
 * The one window the desktop has: the pairing code, the link as text, where the library lives, and
 * the server's state.
 *
 * Browsing is deliberately not here -- the server's web UI at `/` is the photo UI, on this PC and
 * everywhere else. "Open in browser" goes there.
 */
class PairingWindow(
    private val scope: CoroutineScope,
    /** Called on the UI thread with the folder the user picked. */
    private val onChangeLibrary: (File) -> Unit,
    /** The window's close button: hide to the tray, or quit when there is no tray. */
    private val onClose: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private val frame = JFrame("PhotoHost")
    private val qr = JLabel().apply { preferredSize = Dimension(QR_SIZE, QR_SIZE) }
    private val state = JLabel(" ")
    private val library = JLabel(" ")
    private val details = JTextArea(7, 48).apply {
        isEditable = false
        lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private var shownLink: String? = null
    private var server: DesktopServer? = null
    private var watching: Job? = null
    private var watchingImport: Job? = null

    private val autostart = javax.swing.JCheckBox("Start PhotoHost when I sign in to Windows").apply {
        if (Autostart.supported) {
            isSelected = Autostart.isEnabled()
            toolTipText = "Starts quietly in the tray, so the library is always available to your phones."
            addActionListener {
                if (!Autostart.setEnabled(isSelected)) isSelected = Autostart.isEnabled()
            }
        } else {
            isEnabled = false
            toolTipText = "Available when running the installed PhotoHost.exe"
        }
    }

    private val importLine = JLabel(" ")
    private val importCancel = JButton("Cancel import").apply { isVisible = false }
    private val importResume = JButton("Resume import").apply { isVisible = false }
    private val importProblems = JButton("Show problems").apply { isVisible = false }
    private var importStatus = ImportStatus()

    private val buttons = mutableListOf<JButton>()

    /** @param startHidden true for a start at sign-in: the server runs, the window stays in the tray. */
    fun show(startHidden: Boolean = false) {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

        val copy = button("Copy link") {
            server?.status?.value?.pairingLink?.let {
                frame.toolkit.systemClipboard.setContents(StringSelection(it), null)
            }
        }
        val browse = button("Open in browser") { openInBrowser() }
        val rescan = button("Rescan library") {
            val s = server ?: return@button
            scope.launch(Dispatchers.IO) { s.enqueueScan(); s.refresh() }
        }
        val open = button("Open library folder") {
            val path = server?.status?.value?.libraryPath.orEmpty()
            if (path.isNotEmpty()) runCatching { Desktop.getDesktop().open(File(path)) }
        }
        val change = button("Change library folder...") { chooseLibrary() }
        val importFolderButton = button("Import a folder...") { chooseImportFolder() }
        val importFilesButton = button("Import photos...") { chooseImportFiles() }
        importCancel.addActionListener {
            val ok = JOptionPane.showConfirmDialog(
                frame,
                "Stop this import? Files already copied stay in the library.",
                "Cancel import", JOptionPane.OK_CANCEL_OPTION,
            )
            if (ok == JOptionPane.OK_OPTION) server?.importer?.cancel()
        }
        importResume.addActionListener {
            server?.importer?.start(importStatus.sessionId)
        }
        importProblems.addActionListener { showProblems() }

        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JLabel("<html><b>Pair a phone or browser</b><br>In the PhotoHost phone app: Settings, " +
                "Scan a pairing code, then point it at this code.</html>"))
            add(Box.createVerticalStrut(10))
            add(state)
            add(Box.createVerticalStrut(6))
            add(library)
            add(Box.createVerticalStrut(8))
            add(JScrollPane(details))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).apply {
                add(change)
                add(Box.createHorizontalStrut(8))
                add(open)
                add(Box.createHorizontalStrut(8))
                add(rescan)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(copy)
                add(Box.createHorizontalStrut(8))
                add(browse)
                add(Box.createHorizontalStrut(8))
                // Always enabled: quitting must work even while the server is failing to start.
                add(JButton("Quit PhotoHost").apply { addActionListener { onQuit() } })
            })
            add(Box.createVerticalStrut(6))
            add(autostart)
            add(Box.createVerticalStrut(14))
            add(JLabel("<html><b>Add photos from this PC</b><br>Copies a whole folder, or photos and " +
                "videos you pick, into the library. The originals are never changed.</html>"))
            add(Box.createVerticalStrut(6))
            add(importLine)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).apply {
                add(importFolderButton)
                add(Box.createHorizontalStrut(8))
                add(importFilesButton)
                add(Box.createHorizontalStrut(8))
                add(importCancel)
                add(importResume)
                add(Box.createHorizontalStrut(8))
                add(importProblems)
            })
        }
        right.components.forEach { (it as? javax.swing.JComponent)?.alignmentX = 0f }

        // Title bar and taskbar; Windows picks whichever size fits the DPI.
        frame.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 256).map { AppIcon.render(it) }
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(qr.apply { border = BorderFactory.createEmptyBorder(12, 12, 12, 0) }, BorderLayout.WEST)
        frame.contentPane.add(right, BorderLayout.CENTER)
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) = onClose()
        })
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = !startHidden
    }

    /** Shows the window and brings it in front of everything, e.g. from the tray or a second launch. */
    fun bringToFront() = SwingUtilities.invokeLater {
        frame.isVisible = true
        frame.extendedState = frame.extendedState and java.awt.Frame.ICONIFIED.inv()
        // Windows refuses to steal focus for a background process; flipping always-on-top is the
        // accepted way to get in front anyway.
        frame.isAlwaysOnTop = true
        frame.toFront()
        frame.requestFocus()
        frame.isAlwaysOnTop = false
    }

    fun hide() = SwingUtilities.invokeLater { frame.isVisible = false }

    /** The web UI on this PC, signed in through the pairing link over loopback. */
    fun openInBrowser() {
        val s = server?.status?.value ?: return
        val token = s.pairingLink?.let { Regex("[?&]c=([^&]+)").find(it)?.groupValues?.get(1) } ?: return
        runCatching { Desktop.getDesktop().browse(URI("http://127.0.0.1:${s.port}/pair?c=$token")) }
    }

    /** Points the window at a (new) server instance, e.g. after the library folder changed. */
    fun attach(next: DesktopServer) {
        watching?.cancel()
        watchingImport?.cancel()
        server = next
        watching = scope.launch {
            next.status.collect { s -> SwingUtilities.invokeLater { render(s) } }
        }
        // The importer only exists once the server has started, which is after this is called.
        watchingImport = scope.launch {
            while (next.importer == null) kotlinx.coroutines.delay(100)
            next.importer!!.status.collect { s -> SwingUtilities.invokeLater { renderImport(s) } }
        }
    }

    /** A whole folder, everything under it included. */
    private fun chooseImportFolder() {
        val importer = server?.importer ?: return
        if (importer.busy) return
        val picked = pickFolder("Choose a folder of photos to copy into the library", "Import this folder", null)
            ?: return
        startImport(importer, listOf(picked), picked.absolutePath)
    }

    /** Individual photos and videos, several at once. */
    private fun chooseImportFiles() {
        val importer = server?.importer ?: return
        if (importer.busy) return
        val filters = listOf(
            NativeFileDialog.Filter("Photos and videos", FolderImporter.MEDIA_PATTERNS),
            NativeFileDialog.Filter("All files", listOf("*.*")),
        )
        val files = when (val r = NativeFileDialog.pickFiles(frame, "Choose photos and videos to copy into the library", "Import", null, filters)) {
            is NativeFileDialog.Result.Picked -> r.files
            NativeFileDialog.Result.Cancelled -> return
            is NativeFileDialog.Result.Unavailable -> {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Choose photos and videos to copy into the library"
                    isMultiSelectionEnabled = true
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                        "Photos and videos", *FolderImporter.MEDIA_PATTERNS.map { it.removePrefix("*.") }.toTypedArray(),
                    )
                }
                if (chooser.showDialog(frame, "Import") != JFileChooser.APPROVE_OPTION) return
                chooser.selectedFiles.toList()
            }
        }
        if (files.isEmpty()) return
        val label = if (files.size == 1) files[0].absolutePath else "${files.size} chosen files"
        startImport(importer, files, label)
    }

    private fun startImport(importer: FolderImporter, sources: List<File>, label: String) {
        scope.launch {
            val found = try {
                importer.discover(sources)
            } catch (t: Throwable) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(frame, t.message ?: t.toString(), "Cannot import", JOptionPane.WARNING_MESSAGE)
                }
                return@launch
            }
            SwingUtilities.invokeLater { confirmImport(importer, label, found) }
        }
    }

    /**
     * The native Windows folder picker, or Swing's when the native one cannot be shown. Null when
     * cancelled.
     */
    private fun pickFolder(title: String, okLabel: String, startIn: File?): File? =
        when (val r = NativeFileDialog.pickFolder(frame, title, okLabel, startIn)) {
            is NativeFileDialog.Result.Picked -> r.files.firstOrNull()
            NativeFileDialog.Result.Cancelled -> null
            is NativeFileDialog.Result.Unavailable -> {
                val chooser = JFileChooser(startIn).apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showDialog(frame, okLabel) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
        }

    private fun confirmImport(importer: FolderImporter, label: String, found: Discovery) {
        if (found.files == 0) {
            JOptionPane.showMessageDialog(frame, "No photos or videos were found in\n$label")
            scope.launch { importer.discard(found.sessionId) }
            return
        }
        val size = FolderImporter.formatBytes(found.bytes)
        val free = found.freeBytes?.let { FolderImporter.formatBytes(it) } ?: "unknown"
        val tight = found.freeBytes != null && found.bytes + FolderImporter.RESERVE_BYTES > found.freeBytes
        val message = buildString {
            append("<html>Found <b>${"%,d".format(found.files)}</b> photos and videos (<b>$size</b>) in<br>")
            append("<b>$label</b><br><br>")
            append("The library drive has <b>$free</b> free.<br>")
            if (tight) {
                append("<font color='#b00020'>That may not be enough. Files already in the library are skipped, " +
                    "so it may still fit;<br>if space runs low the import pauses rather than filling the drive.</font><br>")
            }
            if (found.unreadable > 0) append("${found.unreadable} items could not be read and will be left out.<br>")
            append("<br>Each file is copied into the library and checked. Your folder is not changed.<br>")
            append("Photos already in the library are skipped. You can close PhotoHost and it carries on next time.</html>")
        }
        val ok = JOptionPane.showConfirmDialog(frame, message, "Import photos", JOptionPane.OK_CANCEL_OPTION)
        if (ok == JOptionPane.OK_OPTION) {
            importer.start(found.sessionId)
        } else {
            scope.launch { importer.discard(found.sessionId) }
        }
    }

    private fun renderImport(s: ImportStatus) {
        importStatus = s
        val fmt = FolderImporter::formatBytes
        importLine.text = when (s.phase) {
            ImportStatus.Phase.IDLE -> " "
            ImportStatus.Phase.DISCOVERING -> "Listing files in ${s.source}... ${"%,d".format(s.total)} found"
            ImportStatus.Phase.AWAITING_CONFIRM -> "Found ${"%,d".format(s.total)} files (${fmt(s.bytesTotal)})"
            ImportStatus.Phase.RUNNING -> "<html>Importing <b>${"%,d".format(s.done)}</b> of ${"%,d".format(s.total)}" +
                " &middot; ${fmt(s.bytesDone)} of ${fmt(s.bytesTotal)}<br>" + importCounts(s) +
                (s.current?.let { "<br><font color='#666666'>$it</font>" } ?: "") + "</html>"
            ImportStatus.Phase.PAUSED -> "<html><b>Import paused.</b> ${s.message ?: ""}<br>${importCounts(s)}</html>"
            ImportStatus.Phase.DONE -> "<html><b>Import finished.</b> ${importCounts(s)}</html>"
            ImportStatus.Phase.CANCELLED -> "<html>Import stopped. ${importCounts(s)}</html>"
            ImportStatus.Phase.FAILED -> "<html><font color='#b00020'><b>Import failed:</b> ${s.message}</font></html>"
        }
        importCancel.isVisible = s.phase == ImportStatus.Phase.RUNNING
        importResume.isVisible = s.phase == ImportStatus.Phase.PAUSED
        importProblems.isVisible = s.failed > 0
        frame.pack()
    }

    private fun importCounts(s: ImportStatus): String = buildList {
        add("${"%,d".format(s.copied)} copied")
        if (s.duplicates > 0) add("${"%,d".format(s.duplicates)} already in the library")
        if (s.unchanged > 0) add("${"%,d".format(s.unchanged)} imported before")
        if (s.failed > 0) add("${"%,d".format(s.failed)} could not be copied")
    }.joinToString(" &middot; ")

    private fun showProblems() {
        val importer = server?.importer ?: return
        val id = importStatus.sessionId
        scope.launch {
            val rows = importer.failures(id)
            val text = rows.joinToString("\n") { "${it.sourceUri}\n    ${it.lastError}" }.ifEmpty { "No problems recorded." }
            SwingUtilities.invokeLater {
                val area = JTextArea(text, 18, 80).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
                JOptionPane.showMessageDialog(frame, JScrollPane(area), "Files that could not be imported", JOptionPane.PLAIN_MESSAGE)
            }
        }
    }

    /** Shown while the server stops and restarts on another folder. */
    fun setBusy(message: String) = SwingUtilities.invokeLater {
        state.text = message
        buttons.forEach { it.isEnabled = false }
    }

    fun close() = SwingUtilities.invokeLater { frame.dispose() }

    private fun chooseLibrary() {
        val current = server?.status?.value?.libraryPath?.takeIf { it.isNotEmpty() }?.let(::File)
        val picked = pickFolder("Choose the folder that holds this library", "Use this folder", current?.parentFile)
            ?: return
        if (current != null && picked.absoluteFile == current.absoluteFile) return

        val answer = JOptionPane.showConfirmDialog(
            frame,
            "<html>Serve the library in<br><b>${picked.absolutePath}</b>?<br><br>" +
                "Photos already in that folder are indexed. Photos backed up from a phone are saved there.<br>" +
                "Deleting from the trash deletes files from that folder.<br><br>" +
                "Paired phones stay paired. The current folder and its index are left untouched,<br>" +
                "and switching back later picks up where it left off.</html>",
            "Change library folder",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (answer == JOptionPane.OK_OPTION) onChangeLibrary(picked)
    }

    private fun render(s: ServerStatus) {
        buttons.forEach { it.isEnabled = s.running || it.text.startsWith("Change") }
        state.text = when {
            s.error != null -> "<html><font color='#b00020'><b>Not running:</b> ${s.error}</font></html>"
            !s.running -> "Starting..."
            else -> "<html>Serving <b>${s.assets}</b> photos and videos" +
                (if (s.pendingJobs > 0) " &middot; ${s.pendingJobs} thumbnails queued" else "") +
                (if (s.blockedJobs > 0) " &middot; ${s.blockedJobs} waiting for a decoder" else "") +
                "</html>"
        }
        library.text = "<html>Library folder: <b>${s.libraryPath.ifEmpty { "-" }}</b></html>"
        details.text = buildString {
            s.addresses.forEach { appendLine("Address:   $it") }
            if (s.fingerprint != null) appendLine("TLS pin:   ${s.fingerprint}")
            if (s.running) appendLine("Video thumbnails: ${s.videoDecoder?.let { "on (ffmpeg at $it)" } ?: "off - ffmpeg not found"}")
            s.scanNote?.let { appendLine("Last scan: $it") }
            s.pairingLink?.let { appendLine(); append(it) }
        }
        if (s.pairingLink != shownLink) {
            shownLink = s.pairingLink
            qr.icon = s.pairingLink?.let { ImageIcon(encodeQr(it, QR_SIZE)) }
        }
        frame.pack()
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply {
        addActionListener { action() }
        buttons += this
    }

    private companion object {
        const val QR_SIZE = 320

        /** Same settings as the phone's pairing QR: level M, one-module margin, UTF-8. */
        fun encodeQr(text: String, size: Int): BufferedImage {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until size) for (x in 0 until size) {
                img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
            return img
        }
    }
}
