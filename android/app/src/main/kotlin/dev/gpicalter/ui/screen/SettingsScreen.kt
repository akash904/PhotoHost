package dev.gpicalter.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.gpicalter.backup.BackupScheduler
import dev.gpicalter.backup.BackupState
import dev.gpicalter.core.Prefs
import dev.gpicalter.di.AppContainer
import dev.gpicalter.net.RemoteState
import dev.gpicalter.probe.ProbeActivity
import dev.gpicalter.service.MediaServerService
import dev.gpicalter.service.ServerState
import dev.gpicalter.ui.components.CardHeader
import dev.gpicalter.ui.components.Footnote
import dev.gpicalter.ui.components.MonoLine
import dev.gpicalter.ui.components.RowDivider
import dev.gpicalter.ui.components.SectionHeader
import dev.gpicalter.ui.components.SettingsCard
import dev.gpicalter.ui.components.SettingsRow
import dev.gpicalter.ui.components.StatusDot
import dev.gpicalter.ui.components.SwitchRow
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
    onShowPairingCode: () -> Unit = {},
    onScanPairingCode: () -> Unit = {},
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
    var remote by remember { mutableStateOf(prefs.serverUrl.orEmpty()) }
    var remoteToken by remember { mutableStateOf(prefs.remoteToken.orEmpty()) }
    var remoteOn by remember { mutableStateOf(prefs.remoteAccess) }
    var showRouterTrace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val net by RemoteState.state.collectAsStateWithLifecycle()
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

        // ---------------------------------------------------------------- backup
        SectionHeader("Back up this phone")
        SettingsCard {
            CardHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = backupOn || backup.running)
                    Text(
                        text = when {
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
                        progress = { backup.done.toFloat() / backup.total },
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
                    backup.currentName?.let { MonoLine(it) }
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

        // ---------------------------------------------------------------- library
        SectionHeader("Library to browse")
        SettingsCard {
            SettingsRow(
                title = "Scan a pairing code",
                subtitle = "Point the camera at the QR shown on the phone serving the library",
                value = "›",
                onClick = onScanPairingCode,
            )
            RowDivider()
            RowDivider()
            SettingsRow(
                title = if (remote.isBlank()) "This phone" else "Remote server",
                subtitle = if (remote.isBlank()) {
                    "Browsing the library served here, over loopback"
                } else {
                    remote
                },
            )
            RowDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it },
                    label = { Text("Server address") },
                    placeholder = { Text("blank = this phone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = remoteToken,
                    onValueChange = { remoteToken = it },
                    label = { Text("Its access token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        prefs.serverUrl = remote.ifBlank { null }
                        prefs.remoteToken = remoteToken.ifBlank { null }
                        onEndpointChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply") }
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
