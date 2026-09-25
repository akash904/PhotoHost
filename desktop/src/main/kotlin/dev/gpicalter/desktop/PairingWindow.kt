package dev.gpicalter.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The one window the desktop has: the pairing code, the link as text, and the server's state.
 *
 * Browsing is deliberately not here -- the server's web UI at `/` is the photo UI, on this PC and
 * everywhere else.
 */
class PairingWindow(
    private val server: DesktopServer,
    private val scope: CoroutineScope,
    private val onQuit: () -> Unit,
) {
    private val frame = JFrame("PhotoHost")
    private val qr = JLabel().apply { preferredSize = Dimension(QR_SIZE, QR_SIZE) }
    private val state = JLabel(" ")
    private val details = JTextArea(6, 44).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private var shownLink: String? = null

    fun show() {
        val copy = JButton("Copy link").apply {
            addActionListener {
                server.status.value.pairingLink?.let {
                    frame.toolkit.systemClipboard.setContents(StringSelection(it), null)
                }
            }
        }
        val rescan = JButton("Rescan library").apply {
            addActionListener { scope.launch(Dispatchers.IO) { server.enqueueScan(); server.refresh() } }
        }
        val open = JButton("Open library folder").apply {
            addActionListener {
                val path = server.status.value.libraryPath
                if (path.isNotEmpty()) runCatching { Desktop.getDesktop().open(File(path)) }
            }
        }

        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JLabel("<html><b>Pair a phone or browser</b><br>Scan this code in the PhotoHost app " +
                "(Settings, Scan a pairing code), or open the link in a browser.</html>"))
            add(javax.swing.Box.createVerticalStrut(8))
            add(state)
            add(javax.swing.Box.createVerticalStrut(8))
            add(javax.swing.JScrollPane(details))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).apply {
                add(copy)
                add(javax.swing.Box.createHorizontalStrut(8))
                add(rescan)
                add(javax.swing.Box.createHorizontalStrut(8))
                add(open)
            })
        }

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(qr.apply { border = BorderFactory.createEmptyBorder(12, 12, 12, 0) }, BorderLayout.WEST)
        frame.contentPane.add(right, BorderLayout.CENTER)
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            // Closing the window stops the server. Until the tray icon exists (milestone 2) there is
            // nowhere else for a running server to live, and a server nobody can see or stop is worse.
            override fun windowClosing(e: java.awt.event.WindowEvent?) = onQuit()
        })
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        scope.launch {
            server.status.collect { s -> SwingUtilities.invokeLater { render(s) } }
        }
    }

    fun close() = SwingUtilities.invokeLater { frame.dispose() }

    private fun render(s: ServerStatus) {
        state.text = when {
            s.error != null -> "<html><font color='#b00020'><b>Not running:</b> ${s.error}</font></html>"
            !s.running -> "Starting..."
            else -> "<html>Serving <b>${s.assets}</b> photos and videos" +
                (if (s.pendingJobs > 0) " &middot; ${s.pendingJobs} thumbnails queued" else "") +
                (if (s.blockedJobs > 0) " &middot; ${s.blockedJobs} waiting for a decoder" else "") +
                "</html>"
        }
        details.text = buildString {
            appendLine("Library:  ${s.libraryPath}")
            s.addresses.forEach { appendLine("Address:  $it") }
            if (s.fingerprint != null) appendLine("TLS pin:  ${s.fingerprint}")
            s.scanNote?.let { appendLine("Last scan: $it") }
            s.pairingLink?.let { appendLine(); append(it) }
        }
        if (s.pairingLink != shownLink) {
            shownLink = s.pairingLink
            qr.icon = s.pairingLink?.let { ImageIcon(encodeQr(it, QR_SIZE)) }
        }
        frame.pack()
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
