package dev.gpicalter.ui.screen

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.gpicalter.ui.components.Footnote

/**
 * Builds the pairing link the QR encodes.
 *
 * The code carries the ordinary pairing URL, `http://<host>:<port>/pair?c=<token>`, rather than a
 * private format. That single choice makes one QR serve both clients: this app parses it to
 * configure itself, and any phone camera or browser opens it as a link, which signs that browser
 * in. A bespoke `gpic://` payload would have needed a second QR for browsers.
 *
 * Separate from any composable because two screens show this code -- the Server tab inline, and the
 * full-screen version reached from Settings -- and a payload that differed between them would be a
 * pairing bug that only appears depending on which screen you happened to use.
 */
fun pairingPayload(
    urls: List<String>,
    token: String,
    httpsUrl: String?,
    fingerprint: String?,
    alternates: List<String>,
): String? {
    // Prefer a routable LAN or Tailscale address; loopback is useless to another device.
    val base = urls.map { it.substringAfter(": ") }
        .firstOrNull { !it.contains("127.0.0.1") }
        ?: urls.firstOrNull()?.substringAfter(": ")

    // The TLS address is preferred when available: it is the one that is safe to use from outside
    // the LAN, and the fingerprint travelling in the same code is what lets the app pin it.
    val primary = when {
        httpsUrl != null && fingerprint != null -> "$httpsUrl/pair?c=$token&f=$fingerprint"
        else -> base?.let { "$it/pair?c=$token" }
    }

    // Every other address rides along. A code advertises whichever address this phone prefers, and
    // the phone scanning it may be on a network where that one is unreachable -- over Wi-Fi scanning
    // a code that names a VPN address, most obviously. Without the rest, that scan produces a client
    // that paired successfully and can never connect, because discovering more addresses requires a
    // connection it cannot make.
    //
    // The separators are left unescaped: they are legal in a query value, and encoding them would
    // inflate the code for no gain.
    val extras = alternates.filterNot { it.contains("127.0.0.1") }.distinct()
    return when {
        primary == null -> null
        extras.isEmpty() -> primary
        else -> primary + "&a=" + android.net.Uri.encode(extras.joinToString(","), ":/,")
    }
}

/**
 * The code itself, plus what to do with it.
 *
 * @param qrSize how large to draw the code. The Server tab shares the screen with a status card and
 *   uses a smaller one; the full-screen version can afford more.
 */
@Composable
fun PairingCode(payload: String?, modifier: Modifier = Modifier, qrSize: Int = 280) {
    val clipboard = LocalClipboardManager.current
    val qr = remember(payload) { payload?.let { encodeQr(it, 640) } }

    if (qr == null || payload == null) {
        Text(
            "Start the server first — there is no address to share yet.",
            modifier.padding(32.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // White quiet zone around the code: scanners need the contrast, and on a dark theme a bare
        // QR on near-black background fails to read on many cameras.
        Box(
            Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(16.dp),
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier.size(qrSize.dp),
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "On the other phone: choose \"View photos kept on another phone or PC\"",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                if (payload.startsWith("https")) {
                    "Encrypted, and safe to use from outside your network. A browser will warn " +
                        "about the certificate once; the app verifies it properly."
                } else {
                    "Or point any camera at it to sign a browser in."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = payload,
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(payload)) }) { Text("Copy link") }

        Footnote(
            "Anyone who scans this gets full access to the library. Treat it like a password, " +
                "and only show it on a network you trust.",
        )
    }
}

/** The full-screen version, reached from Settings. */
@Composable
fun PairQrScreen(
    urls: List<String>,
    token: String,
    httpsUrl: String? = null,
    fingerprint: String? = null,
    alternates: List<String> = emptyList(),
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val payload = remember(urls, token, httpsUrl, fingerprint, alternates) {
        pairingPayload(urls, token, httpsUrl, fingerprint, alternates)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                Text(
                    "Pair a device",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            PairingCode(payload)
        }
    }
}

/**
 * Renders a QR bitmap.
 *
 * Error correction is set to M rather than the default L: the code is read off a glowing screen,
 * often at an angle and with reflections, and the extra redundancy costs only a slightly denser
 * grid. Pixels are written in one `setPixels` call, because a per-pixel loop over a 640x640 bitmap
 * is a quarter of a million JNI crossings.
 */
private fun encodeQr(text: String, size: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
} catch (t: Throwable) {
    null
}
