package io.github.akash904.photohost.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.akash904.photohost.core.Prefs

private const val TAG = "gpic"

/** What a pairing QR resolves to. */
data class PairingInfo(
    val baseUrl: String,
    val token: String,
    val fingerprint: String? = null,
    /** The server's other addresses, so a client is never left knowing only one. */
    val alternates: List<String> = emptyList(),
)

/**
 * Parses a pairing payload.
 *
 * Accepts the same `http://host:port/pair?c=token` URL the QR screen shows, so a code that works in
 * a browser works here too. Anything else is rejected outright rather than half-applied: a wrong
 * address silently saved is far more confusing than a scan that plainly refuses.
 */
fun parsePairing(raw: String?): PairingInfo? {
    if (raw.isNullOrBlank()) return null
    return try {
        val uri = Uri.parse(raw.trim())
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val token = uri.getQueryParameter("c")?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        // An https payload carries the certificate fingerprint to pin. Its absence on an https URL
        // is not fatal, but it means falling back to platform trust, which will reject a
        // self-signed certificate -- so it is surfaced rather than silently accepted.
        val fingerprint = uri.getQueryParameter("f")?.takeIf { it.length == 64 }
        // Older codes have no alternates, and a client scanning one is no worse off than before.
        val alternates = uri.getQueryParameter("a")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        PairingInfo("$scheme://$host$port", token, fingerprint, alternates)
    } catch (t: Throwable) {
        null
    }
}

/**
 * Scans a pairing QR with the camera.
 *
 * Uses CameraX with ML Kit's bundled barcode model rather than the Play Services scanner, so
 * pairing keeps working on a device without Google Play — which is exactly the sort of device
 * someone self-hosting is likely to be using.
 */
@Composable
fun ScanQrScreen(prefs: Prefs, onPaired: (io.github.akash904.photohost.core.LibraryProfile) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)
    }

    BackHandler { onClose() }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {

            if (granted) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val executor = ContextCompat.getMainExecutor(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            try {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val scanner = BarcodeScanning.getClient()
                                val analysis = ImageAnalysis.Builder()
                                    // Dropping stale frames keeps the scan responsive; a queued
                                    // backlog would decode images from seconds ago.
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(executor) { proxy ->
                                    processFrame(proxy, scanner) { value ->
                                        if (handled) return@processFrame
                                        val info = parsePairing(value)
                                        if (info == null) {
                                            status = "That QR is not a pairing code."
                                            return@processFrame
                                        }
                                        handled = true
                                        // Added to the list of libraries, or updated if this one is
                                        // already there -- never written over whichever library was
                                        // paired before. The alternates travel with it, so this
                                        // library's addresses can never be probed for another's.
                                        val library = prefs.addOrUpdateLibrary(
                                            url = info.baseUrl,
                                            token = info.token,
                                            fingerprint = info.fingerprint,
                                            candidates = info.alternates,
                                        )
                                        prefs.activeLibraryId = library.id
                                        status = "Paired with ${info.baseUrl}"
                                        provider.unbindAll()
                                        onPaired(library)
                                    }
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            } catch (t: Throwable) {
                                // The exception class belongs in the log, not on screen. "Camera
                                // unavailable: NullPointerException" was shown for a stripped ML Kit
                                // registrar, and read as a refused permission -- it sent the reader
                                // to re-grant a permission that was already granted, which is the
                                // one thing that could not help. What a person can act on is that
                                // the camera did not open, and that there is another way in.
                                Log.e(TAG, "camera bind failed", t)
                                status = "The camera could not be opened. Close any other app " +
                                    "using it and try again, or type the server address by hand " +
                                    "in Settings."
                            }
                        }, executor)
                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                DisposableEffect(Unit) {
                    onDispose {
                        runCatching {
                            ProcessCameraProvider.getInstance(context).get().unbindAll()
                        }
                    }
                }
            }

            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                    Text(
                        "Scan pairing code",
                        Modifier.weight(1f),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!granted) {
                    Text(
                        "Camera access is needed to scan the code.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { askCamera.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("Allow camera") }
                    Text(
                        "You can also type the address by hand in Settings.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Text(
                        text = status ?: "Point at the QR on the phone or PC serving the library",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Feeds one camera frame to the barcode scanner.
 *
 * `proxy.close()` has to happen exactly once, on every path including failure -- an unclosed frame
 * stalls the whole analysis pipeline after a couple of frames and the preview simply freezes.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processFrame(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onFound: (String?) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes ->
            codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let(onFound)
        }
        .addOnFailureListener { Log.w(TAG, "barcode scan failed: ${it.message}") }
        .addOnCompleteListener { proxy.close() }
}
