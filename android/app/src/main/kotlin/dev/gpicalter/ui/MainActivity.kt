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
import dev.gpicalter.core.DeviceRole
import dev.gpicalter.di.AppContainer
import dev.gpicalter.ui.screen.LibraryScreen
import dev.gpicalter.ui.screen.SettingsScreen
import dev.gpicalter.ui.screen.DevicePickerScreen
import dev.gpicalter.ui.screen.PairQrScreen
import dev.gpicalter.ui.screen.ScanQrScreen
import dev.gpicalter.ui.screen.ServerScreen
import dev.gpicalter.ui.screen.SetupScreen
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
        // Opens on whatever this phone leads with, which on the phone holding the photos is the
        // server. Evaluated once, so the tab under your finger never moves mid-session.
        var tab by remember {
            mutableStateOf(
                if (container.prefs.role == DeviceRole.HOST) Tab.SERVER else Tab.LIBRARY,
            )
        }
        var viewerId by remember { mutableStateOf<Long?>(null) }
        var picking by remember { mutableStateOf(false) }
        var showingQr by remember { mutableStateOf(false) }
        var scanningQr by remember { mutableStateOf(false) }

        // Preferences are plain SharedPreferences and do not emit, so anything that changes the
        // answer to "is this phone its own library" bumps this to force the question again.
        var setupKey by remember { mutableIntStateOf(0) }
        // Session-only: dismissing is a "leave me alone for now", not a decision worth persisting.
        var setupDismissed by remember { mutableStateOf(false) }
        // Asked for deliberately, from the Library empty state or from Settings. Separate from the
        // automatic gate below, which only fires when setup is *needed* -- so without this, a phone
        // that has already answered could tap "Set up this phone" and have nothing happen.
        var setupRequested by remember { mutableStateOf(false) }
        var trashOpen by remember { mutableStateOf(false) }
        val libraryVm: LibraryViewModel = viewModel()
        val library by libraryVm.state.collectAsStateWithLifecycle()
        val backup by BackupState.state.collectAsStateWithLifecycle()
        val server by dev.gpicalter.service.ServerState.state.collectAsStateWithLifecycle()

        val role = remember(setupKey) { container.prefs.role }

        // Ordered by what the phone is for. On the phone holding the photos the server is the
        // thing you came to check, and burying it third behind a grid you could reach from any
        // device is backwards. Keyed to the role rather than to whether it happens to be running,
        // so stopping the server does not rearrange the bar underneath you.
        //
        // A phone that has not decided leads with the library, the same as a viewer. Leading with
        // the server would be arranging the app around a job it has not agreed to do, and if the
        // answer turns out to be "host" the bar reorders the moment that is chosen.
        val tabs = remember(role) {
            if (role == DeviceRole.HOST) {
                listOf(Tab.SERVER, Tab.LIBRARY, Tab.SETTINGS)
            } else {
                listOf(Tab.LIBRARY, Tab.SERVER, Tab.SETTINGS)
            }
        }

        // A backup that just finished has changed the library; showing a stale grid afterwards
        // makes a working upload look like a failed one.
        LaunchedEffect(backup.finishedAt) {
            if (backup.finishedAt > 0L) libraryVm.refresh()
        }

        // The grid's failure state is sticky: it was drawn when nothing was listening, and nothing
        // retries on its own. Without this, starting the server leaves the library still insisting
        // it cannot be reached, which is the one moment the message is certainly wrong.
        LaunchedEffect(server.running) {
            if (server.running) libraryVm.refresh()
        }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // Three tabs, one per thing this phone does: look at photos, serve them, configure
                // it. Trash moved into Settings because it is somewhere you go occasionally to
                // undo something, not a third of what the app is for, and it was taking the slot
                // the server needed on the one phone where the server matters most.
                NavigationBar {
                    tabs.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Text(entry.glyph()) },
                            label = { Text(entry.label()) },
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                Tab.LIBRARY -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    LibraryScreen(
                        onOpen = { viewerId = it },
                        vm = libraryVm,
                        onSetUp = { setupRequested = true },
                    )
                }
                Tab.SERVER -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    ServerScreen(prefs = container.prefs, server = server)
                }
                Tab.SETTINGS -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    SettingsScreen(
                        onEndpointChanged = {
                            libraryVm.refresh()
                            // Typing an address in, or clearing it, is as much a statement of what
                            // this phone is as scanning a code or starting the server.
                            container.prefs.role =
                                if (container.prefs.serverUrl != null) DeviceRole.VIEWER
                                else DeviceRole.HOST
                            setupKey++
                        },
                        onPickPhotos = { picking = true },
                        onShowPairingCode = { showingQr = true },
                        onScanPairingCode = { scanningQr = true },
                        onOpenTrash = { trashOpen = true },
                        onSetUp = { setupRequested = true },
                    )
                }
            }
        }

        // Nothing to browse and nothing serving it. Asked before the grid rather than after it
        // fails, because an empty grid with a connection error is a symptom, and the cause is
        // simply that this phone has not been told what it is yet. A phone already paired to a
        // library is excluded: it has answered this question, and an unreachable server there is a
        // different problem with its own diagnosis.
        // Two reasons to ask: nobody has said what this phone is, or it is the library and nothing
        // is currently serving it. A viewer is never asked; it answered, and a remote server that
        // will not answer is a different failure with its own diagnosis.
        val needsSetup = when (role) {
            DeviceRole.UNSET -> true
            DeviceRole.HOST -> !server.running
            DeviceRole.VIEWER -> false
        }
        if (!showIntro && (setupRequested || (!setupDismissed && needsSetup))) {
            SetupScreen(
                prefs = container.prefs,
                server = server,
                firstTime = role == DeviceRole.UNSET,
                onScanPairingCode = { scanningQr = true },
                onOpenSettings = {
                    setupRequested = false
                    setupDismissed = true
                    tab = Tab.SETTINGS
                },
                onServing = {
                    // Starting the server is the decision, so it is recorded here rather than
                    // inferred later from the absence of a remote address.
                    container.prefs.role = DeviceRole.HOST
                    setupKey++
                    setupRequested = false
                    setupDismissed = true
                    tab = Tab.SERVER
                },
                onDismiss = {
                    setupRequested = false
                    setupDismissed = true
                },
            )
        }

        if (trashOpen) {
            TrashScreen(
                api = libraryVm.api,
                onChanged = { libraryVm.refresh() },
                onClose = { trashOpen = false },
            )
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
                    // This phone now points at someone else's library, so it is no longer waiting
                    // to be told what it is, and the bar reorders to put that library first.
                    container.prefs.role = DeviceRole.VIEWER
                    setupKey++
                    setupRequested = false
                    tab = Tab.LIBRARY
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

/**
 * The three things this phone does, as an enum rather than an index.
 *
 * The bottom bar is ordered differently depending on what this phone is, so a tab's position and
 * its identity are no longer the same thing. Indices would silently mean a different screen on the
 * serving phone than on the viewing one.
 */
private enum class Tab { LIBRARY, SERVER, SETTINGS }

private fun Tab.label() = when (this) {
    Tab.LIBRARY -> "Library"
    Tab.SERVER -> "Server"
    Tab.SETTINGS -> "Settings"
}

private fun Tab.glyph() = when (this) {
    Tab.LIBRARY -> "▦"
    Tab.SERVER -> "◉"
    Tab.SETTINGS -> "⚙"
}

/** Dark by default: a photo grid reads better against near-black than against white. */
private val GpicDarkColors = darkColorScheme(
    background = Color(0xFF101114),
    surface = Color(0xFF16181C),
    surfaceVariant = Color(0xFF23262C),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = Color(0xFF7CC7FF),
)
