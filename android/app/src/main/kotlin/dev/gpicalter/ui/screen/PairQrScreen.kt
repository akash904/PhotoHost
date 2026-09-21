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
 * Shows the pairing QR on the phone that hosts the library.
 *
 * The code encodes the ordinary pairing URL, `http://<host>:<port>/pair?c=<token>`, rather than a
 * private format. That single choice makes one QR serve both clients: this app parses it to
 * configure itself, and any phone camera or browser opens it as a link, which signs that browser
 * in. A bespoke `gpic://` payload would have needed a second QR for browsers.
 */
@Composable
fun PairQrScreen(
    urls: List<String>,
    token: String,
    httpsUrl: String? = null,
    fingerprint: String? = null,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    BackHandler { onClose() }

    // Prefer a routable LAN or Tailscale address; loopback is useless to another device.
    val base = remember(urls) {
        urls.map { it.substringAfter(": ") }
            .firstOrNull { !it.contains("127.0.0.1") }
            ?: urls.firstOrNull()?.substringAfter(": ")
    }
    // The TLS address is preferred when available: it is the one that is safe to use from outside
    // the LAN, and the fingerprint travelling in the same code is what lets the app pin it.
    val payload = remember(base, httpsUrl, token, fingerprint) {
        when {
            httpsUrl != null && fingerprint != null -> "$httpsUrl/pair?c=$token&f=$fingerprint"
            else -> base?.let { "$it/pair?c=$token" }
        }
    }
    val qr = remember(payload) { payload?.let { encodeQr(it, 640) } }

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

            val link = payload
            if (qr == null || link == null) {
                Text(
                    "Start the server first — there is no address to share yet.",
                    Modifier.padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // White quiet zone around the code: scanners need the contrast, and on a dark theme a
            // bare QR on near-black background fails to read on many cameras.
            Box(
                Modifier
                    .padding(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.WHITE.let { androidx.compose.ui.graphics.Color.White })
                    .padding(16.dp),
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.size(280.dp),
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "On the other phone: Settings → Scan QR",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
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
                text = link,
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copy link") }

            Footnote(
                "Anyone who scans this gets full access to the library. Treat it like a password, " +
                    "and only show it on a network you trust.",
            )
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
