package dev.gpicalter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import kotlinx.coroutines.launch
import dev.gpicalter.backup.BackupState
import dev.gpicalter.di.AppContainer
import dev.gpicalter.ui.screen.LibraryScreen
import dev.gpicalter.ui.screen.SettingsScreen
import dev.gpicalter.ui.screen.DevicePickerScreen
import dev.gpicalter.ui.screen.PairQrScreen
import dev.gpicalter.ui.screen.ScanQrScreen
import dev.gpicalter.ui.screen.TrashScreen
import dev.gpicalter.ui.screen.ViewerScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One app, two jobs.
 *
 * Whether this phone serves the library, backs up to one, or both, the browsing UI is the same and
 * always talks to the HTTP API. On the server phone that is loopback, which means the server device
 * runs the identical client code a laptop does -- so there is no local-only path that can quietly
 * rot.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GpicApp() }
    }
}

@Composable
private fun GpicApp() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }

    // One loader for the whole app, carrying the auth header on every thumbnail request.
    setSingletonImageLoaderFactory { container.imageLoader }

    // Without POST_NOTIFICATIONS the foreground-service notification is suppressed on API 33+:
    // the server still runs, but with no way to see or stop it.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val mediaPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val requestMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    // Asked once, at first launch, with an explanation first. Requesting it later as a side effect
    // of flipping a switch conflates two decisions -- "may this app read my photos" and "start
    // uploading now" -- and the second one then happens before you have seen any of the controls.
    var showIntro by remember { mutableStateOf(!container.prefs.onboarded) }
    if (showIntro) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Your own photo library") },
            text = {
                Text(
                    "This app can host a photo library on a phone, and back another phone's " +
                        "camera roll up to it.\n\n" +
                        "To back photos up it needs access to your photos. Granting access does " +
                        "not upload anything: you choose which folders sync, and nothing is sent " +
                        "until you turn backup on.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.prefs.onboarded = true
                    showIntro = false
                    requestMedia.launch(mediaPermissions)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    container.prefs.onboarded = true
                    showIntro = false
                }) { Text("Not now") }
            },
        )
    }

    MaterialTheme(colorScheme = GpicDarkColors) {
        var tab by remember { mutableIntStateOf(0) }
        var viewerId by remember { mutableStateOf<Long?>(null) }
        var picking by remember { mutableStateOf(false) }
        var showingQr by remember { mutableStateOf(false) }
        var scanningQr by remember { mutableStateOf(false) }
        val libraryVm: LibraryViewModel = viewModel()
        val library by libraryVm.state.collectAsStateWithLifecycle()
        val backup by BackupState.state.collectAsStateWithLifecycle()
        val server by dev.gpicalter.service.ServerState.state.collectAsStateWithLifecycle()

        // A backup that just finished has changed the library; showing a stale grid afterwards
        // makes a working upload look like a failed one.
        LaunchedEffect(backup.finishedAt) {
            if (backup.finishedAt > 0L) libraryVm.refresh()
        }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("▦") },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("🗑") },
                        label = { Text("Trash") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Text("⚙") },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                0 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    LibraryScreen(onOpen = { viewerId = it }, vm = libraryVm)
                }
                1 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    TrashScreen(api = libraryVm.api, onChanged = { libraryVm.refresh() })
                }
                else -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    SettingsScreen(
                        onEndpointChanged = { libraryVm.refresh() },
                        onPickPhotos = { picking = true },
                        onShowPairingCode = { showingQr = true },
                        onScanPairingCode = { scanningQr = true },
                    )
                }
            }
        }

        // Drawn over the scaffold rather than as a route, so the grid keeps its scroll position and
        // closing the viewer is instant.
        if (showingQr) {
            PairQrScreen(
                urls = server.urls,
                token = container.prefs.token(),
                httpsUrl = server.httpsUrl,
                fingerprint = server.tlsFingerprint,
                alternates = server.pairUrls,
                onClose = { showingQr = false },
            )
        }

        if (scanningQr) {
            ScanQrScreen(
                prefs = container.prefs,
                onPaired = { paired ->
                    scanningQr = false
                    // The pin changed, so the shared HTTP stack has to be rebuilt before any
                    // request goes out, or the old trust settings would reject the new server.
                    container.invalidateHttp()
                    libraryVm.api.invalidateEndpoint()
                    libraryVm.refresh()
                    tab = 0
                    // Explicit confirmation: the previous version simply closed the camera, which
                    // is indistinguishable from the scan having failed.
                    scope.launch { snackbar.showSnackbar("Connected to $paired") }
                },
                onClose = { scanningQr = false },
            )
        }

        if (picking) {
            DevicePickerScreen(
                db = container.db,
                prefs = container.prefs,
                onClose = { picking = false },
            )
        }

        viewerId?.let { id ->
            ViewerScreen(
                items = library.items,
                startId = id,
                api = libraryVm.api,
                onClose = { viewerId = null },
                onNearEnd = { libraryVm.loadMore() },
                onFavoriteChanged = { assetId, fav -> libraryVm.setFavorite(assetId, fav) },
            )
        }
    }
}

/** Dark by default: a photo grid reads better against near-black than against white. */
private val GpicDarkColors = darkColorScheme(
    background = Color(0xFF101114),
    surface = Color(0xFF16181C),
    surfaceVariant = Color(0xFF23262C),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = Color(0xFF7CC7FF),
)
