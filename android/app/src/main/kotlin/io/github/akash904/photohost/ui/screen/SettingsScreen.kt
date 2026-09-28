package io.github.akash904.photohost.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akash904.photohost.backup.BackupScheduler
import io.github.akash904.photohost.backup.BackupState
import io.github.akash904.photohost.core.DeviceRole
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.di.AppContainer
import io.github.akash904.photohost.net.RemoteState
import io.github.akash904.photohost.probe.ProbeActivity
import io.github.akash904.photohost.service.MediaServerService
import io.github.akash904.photohost.service.ServerState
import io.github.akash904.photohost.ui.components.CardHeader
import io.github.akash904.photohost.ui.components.addressLabel
import io.github.akash904.photohost.ui.components.Footnote
import io.github.akash904.photohost.ui.components.MonoLine
import io.github.akash904.photohost.ui.components.RowDivider
import io.github.akash904.photohost.ui.components.SectionHeader
import io.github.akash904.photohost.ui.components.SettingsCard
import io.github.akash904.photohost.ui.components.SettingsRow
import io.github.akash904.photohost.ui.components.StatusDot
import io.github.akash904.photohost.ui.components.StorageCard
import io.github.akash904.photohost.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * Settings, organised by the question each section answers:
 *
 *  - **Server** -- does this phone host the library?
 *  - **Back up** -- does this phone send its photos somewhere?
 *  - **Library** -- which library is being browsed?
 *
 * Those three are independent, and the layout says so by keeping them in separate cards rather
 * than as one undifferentiated column of switches.
 */
@Composable
fun SettingsScreen(
    onEndpointChanged: () -> Unit,
    onPickPhotos: () -> Unit = {},
    onFreeUpSpace: () -> Unit = {},
    onShowPairingCode: () -> Unit = {},
    onScanPairingCode: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onSetUp: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val container = remember { AppContainer.get(context) }
    val prefs = container.prefs

    val server by ServerState.state.collectAsStateWithLifecycle()
    val backup by BackupState.state.collectAsStateWithLifecycle()

    var autostart by remember { mutableStateOf(prefs.autostart) }
    var showToken by remember { mutableStateOf(false) }
    var backupOn by remember { mutableStateOf(prefs.backupEnabled) }
    var wifiOnly by remember { mutableStateOf(prefs.backupWifiOnly) }
    var chargingOnly by remember { mutableStateOf(prefs.backupWhileChargingOnly) }
    var showFolders by remember { mutableStateOf(false) }
    var folderSummary by remember { mutableStateOf(summarise(prefs)) }
    // Libraries. Preferences do not emit, so this is re-read after every change made here.
    var libraries by remember { mutableStateOf(prefs.libraries()) }
    var activeId by remember { mutableStateOf(prefs.activeLibrary().id) }
    var backupId by remember { mutableStateOf(prefs.backupLibrary()?.id) }
    fun reloadLibraries() {
        libraries = prefs.libraries()
        activeId = prefs.activeLibrary().id
        backupId = prefs.backupLibrary()?.id
    }
    var libraryActions by remember { mutableStateOf<io.github.akash904.photohost.core.Library?>(null) }
    var renaming by remember { mutableStateOf<io.github.akash904.photohost.core.Library?>(null) }
    var renameText by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<io.github.akash904.photohost.core.Library?>(null) }
    var choosingBackup by remember { mutableStateOf(false) }
    var addAddress by remember { mutableStateOf("") }
    var addToken by remember { mutableStateOf("") }
    var remoteOn by remember { mutableStateOf(prefs.remoteAccess) }
    var showRouterTrace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val net by RemoteState.state.collectAsStateWithLifecycle()
    // The address actually in use, which is not always the one configured below: the app falls back
    // to any address the server advertised when the configured one stops answering.
    val active by container.api.activeEndpoint.collectAsStateWithLifecycle()
    // The TLS port. Derived the same way the service derives it, so the two cannot drift apart.
    val securePort = server.port + 363

    val mediaPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    var hasMedia by remember {
        mutableStateOf(
            mediaPermissions.any {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val requestMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Granting access is not consent to upload. The switch decides that, separately, so that
        // saying yes to a permission prompt cannot kick off a full-library sync on its own.
        hasMedia = granted.values.any { it }
    }

    fun reschedule() {
        if (!backupOn) return
        BackupScheduler.cancel(context)
        BackupScheduler.schedulePeriodic(context, wifiOnly, chargingOnly)
    }

    libraryActions?.let { lib ->
        AlertDialog(
            onDismissRequest = { libraryActions = null },
            title = { Text(lib.name) },
            text = {
                Column {
                    Text(lib.url ?: "Kept on this phone", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    if (lib.id != activeId) {
                        TextButton(onClick = {
                            prefs.activeLibraryId = lib.id
                            libraryActions = null
                            reloadLibraries()
                            onEndpointChanged()
                        }) { Text("Browse this library") }
                    }
                    if (lib.id != backupId) {
                        TextButton(onClick = {
                            prefs.backupLibraryId = lib.id
                            libraryActions = null
                            reloadLibraries()
                        }) { Text("Back up this phone to it") }
                    }
                    if (!lib.isLocal) {
                        TextButton(onClick = {
                            renameText = lib.name
                            renaming = lib
                            libraryActions = null
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            removing = lib
                            libraryActions = null
                        }) { Text("Remove from this phone") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraryActions = null }) { Text("Close") } },
        )
    }

    renaming?.let { lib ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename library") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.renameLibrary(lib.id, renameText)
                    renaming = null
                    reloadLibraries()
                    onEndpointChanged()
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    removing?.let { lib ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${lib.name}?") },
            text = {
                Text(
                    "This phone forgets how to reach it. Nothing on that library is deleted, and you can " +
                        "add it again by scanning its pairing code." +
                        if (lib.id == backupId) "\n\nIt is where this phone backs up to, so backup stops until you choose another." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.removeLibrary(lib.id)
                    removing = null
                    reloadLibraries()
                    onEndpointChanged()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (choosingBackup) {
        AlertDialog(
            onDismissRequest = { choosingBackup = false },
            title = { Text("Back up this phone to") },
            text = {
                Column {
                    if (libraries.isEmpty()) {
                        Text("No library yet. Scan a pairing code, or set this phone up to keep its own.")
                    }
                    libraries.forEach { lib ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.backupLibraryId = lib.id
                                    choosingBackup = false
                                    reloadLibraries()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lib.id == backupId, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(lib.name)
                                Text(lib.url ?: "Kept on this phone", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingBackup = false }) { Text("Close") } },
        )
    }

    if (showFolders) {
        AlertDialog(
            onDismissRequest = {
                showFolders = false
                folderSummary = summarise(prefs)
            },
            title = { Text("Folders to back up") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BucketPicker(prefs = prefs, hasPermission = hasMedia, onChanged = {})
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFolders = false
                    folderSummary = summarise(prefs)
                }) { Text("Done") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {

        // ---------------------------------------------------------------- role
        // A permanent way back to the question. The setup screen dismisses for the session, and
        // without this a phone that answered "not now" -- or one that simply changed its mind
        // later -- has no route to the choice at all.
        SectionHeader("This phone")
        SettingsCard {
            SettingsRow(
                title = "Set up this phone",
                subtitle = when (prefs.role) {
                    DeviceRole.HOST -> "Keeps the library here and serves it to other devices"
                    DeviceRole.VIEWER -> "Views libraries kept on other phones or PCs"
                    DeviceRole.UNSET -> "Not chosen yet"
                },
                value = "›",
                onClick = onSetUp,
            )
        }

        // ---------------------------------------------------------------- backup
        SectionHeader("Back up this phone")
        SettingsCard {
            CardHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = backupOn || backup.running)
                    Text(
                        text = when {
                            // Named by the phase actually running. Reading and hashing every
                            // candidate happens before any upload does, and calling that "backing
                            // up" while the count stands still is what made it look stuck.
                            //
                            // The upload half of a batch is asked about first, from the engine's
                            // own flag: checked stays ahead of done for all of it, so without this
                            // the screen said "Checking" -- frozen -- while files were being sent.
                            backup.running && backup.uploading ->
                                "Uploading ${minOf(backup.done + 1, backup.total)} of ${backup.total}"

                            backup.running && backup.checked > backup.done ->
                                "Checking ${backup.checked} of ${backup.total}"

                            backup.running -> "Backing up ${backup.done} of ${backup.total}"
                            !backupOn -> "Off"
                            backup.finishedAt > 0L -> "Up to date"
                            else -> "Scheduled"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
                if (backup.running && backup.total > 0) {
                    LinearProgressIndicator(
                        // Every photo is two steps: checked (read and hashed) and finished (sent,
                        // found already there, or given up on). Showing whichever count was further
                        // ahead filled the bar during checking -- a batch of up to 200 photos is
                        // checked before its first upload -- and then left it full through every
                        // upload. Counting both steps puts "all checked" at half way, and the
                        // current file's bytes move it smoothly through a long video.
                        //
                        // Unchanged photos are skipped without being read, so they count as both
                        // steps at once (they are in done but never in checked). Every term only
                        // grows, except the current file's fraction, which gives way to a whole
                        // step in done when the file completes, so the bar never goes backwards.
                        progress = {
                            val current = if (backup.uploading && backup.currentSize > 0) {
                                backup.currentBytes.toFloat() / backup.currentSize
                            } else {
                                0f
                            }
                            val steps = backup.checked + backup.skipped + backup.done + current
                            (steps / (2f * backup.total)).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (backup.done > 0 || backup.running) {
                    Text(
                        text = buildString {
                            append("${backup.uploaded} uploaded")
                            if (backup.alreadyOnServer > 0) append(" · ${backup.alreadyOnServer} already there")
                            if (backup.skipped > 0) append(" · ${backup.skipped} unchanged")
                            if (backup.failed > 0) append(" · ${backup.failed} failed")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    backup.currentName?.let { name ->
                        // A large video is one item for minutes; the percentage is what shows it
                        // is moving.
                        val progress = if (backup.uploading && backup.currentSize > 0) {
                            val pct = (backup.currentBytes * 100 / backup.currentSize).coerceIn(0, 100)
                            " · $pct% of ${formatSize(backup.currentSize)}"
                        } else {
                            ""
                        }
                        MonoLine(name + progress)
                    }
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (backup.running) {
                        // A long upload with no way out is a trap. Stop cancels the run without
                        // touching the automatic schedule.
                        FilledTonalButton(
                            onClick = { BackupScheduler.stopRun(context) },
                        ) { Text("Stop") }
                    } else {
                        FilledTonalButton(
                            enabled = hasMedia,
                            onClick = { BackupScheduler.runNow(context, wifiOnly) },
                        ) { Text("Back up now") }
                    }
                    OutlinedButton(
                        enabled = hasMedia && !backup.running,
                        onClick = onPickPhotos,
                    ) { Text("Pick photos") }
                }
            }
            RowDivider()
            SettingsRow(
                title = "Back up to",
                subtitle = libraries.firstOrNull { it.id == backupId }?.name
                    ?: "Not chosen — backup will not run until you pick a library",
                value = "›",
                onClick = { choosingBackup = true },
            )
            RowDivider()
            SettingsRow(
                title = "Free up space",
                subtitle = "Remove photos from this phone that the backup library already holds",
                value = "›",
                onClick = onFreeUpSpace,
            )
            RowDivider()
            SettingsRow(
                title = "Photo access",
                subtitle = if (hasMedia) {
                    "Granted"
                } else {
                    "Not granted — nothing can be backed up without it"
                },
                trailing = {
                    if (hasMedia) {
                        StatusDot(active = true)
                    } else {
                        TextButton(onClick = { requestMedia.launch(mediaPermissions) }) {
                            Text("Grant")
                        }
                    }
                },
            )
            RowDivider()
            SwitchRow(
                title = "Automatic backup",
                subtitle = "Looks for new photos every few hours",
                checked = backupOn,
                onCheckedChange = { on ->
                    if (on && !hasMedia) {
                        requestMedia.launch(mediaPermissions)
                    } else {
                        backupOn = on
                        prefs.backupEnabled = on
                        if (on) {
                            BackupScheduler.schedulePeriodic(context, wifiOnly, chargingOnly)
                            BackupScheduler.runNow(context, wifiOnly)
                        } else {
                            BackupScheduler.cancel(context)
                            BackupScheduler.stopRun(context)
                        }
                    }
                },
            )
            RowDivider()
            SettingsRow(
                title = "Folders",
                subtitle = folderSummary,
                value = "›",
                enabled = hasMedia,
                onClick = { showFolders = true },
            )
            RowDivider()
            SwitchRow(
                title = "Wi-Fi only",
                subtitle = "A camera roll is measured in gigabytes",
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    prefs.backupWifiOnly = it
                    reschedule()
                },
            )
            RowDivider()
            SwitchRow(
                title = "Only while charging",
                subtitle = "Fingerprinting every photo is hard on the battery",
                checked = chargingOnly,
                onCheckedChange = {
                    chargingOnly = it
                    prefs.backupWhileChargingOnly = it
                    reschedule()
                },
            )
        }
        Footnote(
            if (!hasMedia) {
                "Photo access has not been granted. Turning on automatic backup will ask for it."
            } else {
                "The first run reads every photo once to fingerprint it, so it is slow. After that, " +
                    "unchanged photos are skipped without being opened, and anything the server " +
                    "already holds costs no upload at all."
            },
        )

        // ---------------------------------------------------------------- libraries
        // Several libraries are a legitimate arrangement -- recent photos on a phone, the archive on
        // a PC -- and they are separate collections, not copies. This lists every one this phone can
        // reach; the Library tab shows one at a time, and backup goes to the one chosen above.
        SectionHeader("Libraries")
        SettingsCard {
            if (libraries.isEmpty()) {
                SettingsRow(title = "No libraries yet", subtitle = "Scan a pairing code to add one")
                RowDivider()
            }
            libraries.forEach { lib ->
                SettingsRow(
                    title = lib.name,
                    subtitle = buildString {
                        append(lib.url ?: "Kept on this phone")
                        val tags = buildList {
                            if (lib.id == activeId) add("on screen")
                            if (lib.id == backupId) add("backup goes here")
                        }
                        if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" · "))
                    },
                    value = "›",
                    onClick = { libraryActions = lib },
                )
                RowDivider()
            }
            SettingsRow(
                title = "Add a library",
                subtitle = "Scan the pairing code shown by a phone or PC serving one",
                value = "›",
                onClick = onScanPairingCode,
            )
            RowDivider()
            // Was a tab of its own. Deleting something is rare and undoing it rarer still, which
            // does not earn a third of the bottom bar on every screen.
            SettingsRow(
                title = "Trash",
                subtitle = "Restore deleted photos, or remove them for good",
                value = "›",
                onClick = onOpenTrash,
            )
            RowDivider()
            CardHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = active != null)
                    Text(
                        text = active?.let { "Connected over ${addressLabel(it)}" } ?: "Not connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                active?.let { MonoLine(it) }
                // Only worth saying when the two differ, which is exactly when the address listed
                // for the library would otherwise look like a lie.
                val configured = libraries.firstOrNull { it.id == activeId }?.url
                if (active != null && configured != null && active != configured) {
                    Text(
                        text = "Not the address the library was paired at. The app tries every " +
                            "address the server advertised and uses the first that replies, so " +
                            "this changes on its own when a VPN or Wi-Fi comes and goes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RowDivider()
            // By hand, for when the camera cannot be used. No certificate pin travels this way, so it
            // is plain HTTP and meant for a LAN; the pairing code is the secure route.
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Add by address", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addAddress,
                    onValueChange = { addAddress = it },
                    label = { Text("Server address") },
                    placeholder = { Text("http://192.168.1.10:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = addToken,
                    onValueChange = { addToken = it },
                    label = { Text("Its access token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = addAddress.isNotBlank() && addToken.isNotBlank(),
                    onClick = {
                        val lib = prefs.addOrUpdateLibrary(addAddress.trim(), addToken.trim(), null, emptyList())
                        prefs.activeLibraryId = lib.id
                        if (prefs.backupLibrary() == null) prefs.backupLibraryId = lib.id
                        addAddress = ""
                        addToken = ""
                        reloadLibraries()
                        onEndpointChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add library") }
            }
        }

        // ---------------------------------------------------------------- server
        SectionHeader("Server")
        SettingsCard {
            CardHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = server.running)
                    Text(
                        text = if (server.running) "Running" else "Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    if (server.running) {
                        OutlinedButton(onClick = { MediaServerService.stop(context) }) { Text("Stop") }
                    } else {
                        Button(onClick = { MediaServerService.start(context) }) { Text("Start") }
                    }
                }
                if (server.running) {
                    server.urls.forEach { MonoLine(it) }
                    Text(
                        text = "${server.heartbeats} heartbeats · serving ${server.backend}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                server.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            RowDivider()
            SwitchRow(
                title = "Start on boot",
                subtitle = "Bring the library back up automatically after a restart",
                checked = autostart,
                onCheckedChange = {
                    autostart = it
                    prefs.autostart = it
                },
            )
            RowDivider()
            SettingsRow(
                title = "Access token",
                // Masked by default. This screen gets shown to other people, and the token is the
                // only thing between them and the entire library.
                subtitle = if (showToken) prefs.token() else "••••••••••••••••  (tap to reveal)",
                onClick = { showToken = !showToken },
                trailing = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(prefs.token()))
                    }) { Text("Copy") }
                },
            )
            RowDivider()
            SettingsRow(
                title = "Show pairing code",
                subtitle = "A QR another phone scans, or any camera opens in a browser",
                value = "›",
                enabled = server.running,
                onClick = onShowPairingCode,
            )
            RowDivider()
            SettingsRow(
                title = "Copy pairing link",
                subtitle = "For pasting into a browser by hand",
                trailing = {
                    TextButton(
                        enabled = server.running && server.urls.isNotEmpty(),
                        onClick = {
                            val base = server.urls.first().substringAfter(": ")
                            clipboard.setText(AnnotatedString("$base/pair?c=${prefs.token()}"))
                        },
                    ) { Text("Copy") }
                },
            )
        }
        Footnote(
            "Port ${server.port} is plain HTTP and carries the token in the clear, so it belongs on " +
                "your own Wi-Fi only. Remote access uses the encrypted port instead.",
        )

        // ---------------------------------------------------------------- storage
        // Sits with the server because it is what the server serves from, and because both are
        // only meaningful on the phone that holds the library.
        SectionHeader("Where photos are kept")
        StorageCard(prefs = prefs, serverRunning = server.running)

        // ---------------------------------------------------------------- remote access
        SectionHeader("Remote access")
        SettingsCard {
            SwitchRow(
                title = "Reach this library from the internet",
                subtitle = "Asks the router to allow incoming connections on the encrypted port",
                checked = remoteOn,
                enabled = server.running,
                onCheckedChange = { on ->
                    remoteOn = on
                    prefs.remoteAccess = on
                    if (on) {
                        scope.launch { container.remoteAccess.open(securePort) }
                    } else {
                        container.remoteAccess.stop()
                    }
                },
            )
            if (remoteOn) {
                RowDivider()
                CardHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = net.opened)
                        Text(
                            text = when {
                                net.attempting -> "Asking the router..."
                                else -> net.summary ?: "Not attempted yet"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                        )
                        TextButton(
                            enabled = !net.attempting && server.running,
                            onClick = { scope.launch { container.remoteAccess.open(securePort) } },
                        ) { Text("Retry") }
                    }
                    net.publicUrl?.let { MonoLine(it) }
                    if (net.carrierNat) {
                        Text(
                            text = "The router's own WAN address is ${net.routerWanIpv4}, which is a " +
                                "shared carrier address rather than one you hold. No IPv4 forwarding " +
                                "can make this phone reachable; IPv6 is the only road from here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                RowDivider()
                SettingsRow(
                    title = "Test from outside",
                    subtitle = "Dials this phone's public address over mobile data. Costs a few kilobytes.",
                    trailing = {
                        TextButton(
                            enabled = !net.testing && server.running,
                            onClick = {
                                scope.launch {
                                    container.remoteAccess.test(securePort, server.tlsFingerprint)
                                }
                            },
                        ) { Text(if (net.testing) "Testing" else "Test") }
                    },
                )
                net.testResult?.let { text ->
                    CardHeader {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (net.testPassed == true) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                if (net.lines.isNotEmpty()) {
                    RowDivider()
                    SettingsRow(
                        title = "What the router said",
                        subtitle = "${net.lines.size} steps",
                        value = if (showRouterTrace) "hide" else "show",
                        onClick = { showRouterTrace = !showRouterTrace },
                    )
                    if (showRouterTrace) {
                        CardHeader { net.lines.forEach { MonoLine(it) } }
                    }
                }
            }
        }
        Footnote(
            if (net.opened) {
                "Confirm the whole path by pairing another phone from the QR while it is on mobile data."
            } else {
                "If the router offers no way to ask, the only ways in are a one-time rule in its admin " +
                    "page or a VPN such as Tailscale. Nothing else can carry an inbound connection across it."
            },
        )

        // ---------------------------------------------------------------- advanced
        SectionHeader("Advanced")
        SettingsCard {
            SettingsRow(
                title = "Diagnostics",
                subtitle = "Storage probes, throughput tests and logs",
                value = "›",
                onClick = { context.startActivity(Intent(context, ProbeActivity::class.java)) },
            )
        }
    }
}

private fun summarise(prefs: Prefs): String {
    val chosen = prefs.backupBuckets
    return when {
        chosen == null -> "Not chosen — camera folders by default"
        chosen.isEmpty() -> "None selected"
        chosen.size == 1 -> "1 folder selected"
        else -> "${chosen.size} folders selected"
    }
}
