package io.github.akash904.photohost.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.storage.StorageReadiness
import io.github.akash904.photohost.storage.StoreKind
import io.github.akash904.photohost.storage.storageReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder picker that remembers what it picked.
 *
 * Shared because the grant is the easy half to get wrong: without
 * `takePersistableUriPermission` the permission dies with the process, and the drive silently needs
 * re-picking after every reboot. Two copies of that would eventually become one copy that does it
 * and one that does not.
 */
@Composable
fun rememberDrivePicker(prefs: Prefs, onPicked: () -> Unit): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            prefs.treeUri = uri
            // Picking a drive is the whole statement of intent; leaving the backend on internal
            // afterwards would mean choosing a drive and not using it.
            prefs.backend = StoreKind.SAF.name
            onPicked()
        }
    }
}

/**
 * Where the library's bytes live, and how to change it.
 *
 * This was only ever reachable from the diagnostics Probe activity, which is a screen nobody opens
 * on purpose. It belongs anywhere the server does, so the same card is drawn on both the Server tab
 * and in Settings rather than being reimplemented in each.
 *
 * ### Why it locks while the server is running
 *
 * The store is built once during bring-up and handed to the HTTP server, the thumbnail generator
 * and the job runner. Changing the preference underneath them changes nothing until the next start,
 * so offering the control while running would be a switch that silently does not work. The Probe
 * screen already took this position; this keeps it and says why out loud.
 *
 * ### Why switching asks first
 *
 * Nothing is deleted, but `asset_files` rows keep pointing at the volume they were indexed on, so
 * photos held in the other location stop resolving the moment the active store is a different one.
 * They come back when that location is selected again. That is recoverable and completely alarming
 * if it happens without warning, which is the case a confirmation exists for.
 */
@Composable
fun StorageCard(prefs: Prefs, serverRunning: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var probeKey by remember { mutableIntStateOf(0) }
    var readiness by remember { mutableStateOf<StorageReadiness?>(null) }
    var pendingSwitch by remember { mutableStateOf<StoreKind?>(null) }

    val backend = remember(probeKey) { prefs.backend }
    val treeUri = remember(probeKey) { prefs.treeUri }
    val usingSaf = backend == StoreKind.SAF.name

    val volumeKey = remember(treeUri) {
        treeUri?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it).substringBefore(':') }.getOrNull()
        }
    }

    LaunchedEffect(probeKey) {
        // Off the main thread: on a drive that has been pulled this waits for a Binder timeout.
        readiness = withContext(Dispatchers.IO) { storageReadiness(context, backend, treeUri) }
    }

    val picker = rememberDrivePicker(prefs) { probeKey++ }

    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Change where photos are kept?") },
            text = {
                Text(
                    "Nothing is deleted. Photos already stored in the other place stay exactly " +
                        "where they are, but they will not appear in your library until you " +
                        "switch back to it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.backend = target.name
                    pendingSwitch = null
                    probeKey++
                    if (target == StoreKind.SAF && prefs.treeUri == null) picker.launch(null)
                }) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text("Cancel") }
            },
        )
    }

    SettingsCard(modifier) {
        CardHeader {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = readiness == StorageReadiness.READY)
                Text(
                    text = when {
                        !usingSaf -> "Kept on this phone"
                        volumeKey != null -> "Kept on USB $volumeKey"
                        else -> "No drive chosen yet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            MonoLine(
                if (usingSaf) treeUri?.toString() ?: "no drive picked"
                else "Android/data/io.github.akash904.photohost/files/library",
            )
            if (readiness == StorageReadiness.FOLDER_MISSING) {
                Text(
                    "This drive cannot be reached — either it is unplugged, or permission to it " +
                        "was lost. The server falls back to this phone's storage until it is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (serverRunning) {
                Text(
                    "Stop the server to change this. It reads the location once when it starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RowDivider()
        SettingsRow(
            title = "This phone",
            subtitle = "App-private storage. Always available, limited to the phone's own space.",
            enabled = !serverRunning,
            onClick = { if (usingSaf) pendingSwitch = StoreKind.INTERNAL },
            trailing = {
                RadioButton(
                    selected = !usingSaf,
                    enabled = !serverRunning,
                    onClick = { if (usingSaf) pendingSwitch = StoreKind.INTERNAL },
                )
            },
        )
        RowDivider()
        SettingsRow(
            title = "USB drive",
            subtitle = "A drive plugged into this phone. Needs a powered hub on most handsets.",
            enabled = !serverRunning,
            onClick = { if (!usingSaf) pendingSwitch = StoreKind.SAF },
            trailing = {
                RadioButton(
                    selected = usingSaf,
                    enabled = !serverRunning,
                    onClick = { if (!usingSaf) pendingSwitch = StoreKind.SAF },
                )
            },
        )
        RowDivider()
        SettingsRow(
            title = if (treeUri == null) "Choose a drive" else "Choose a different drive",
            subtitle = "Pick the folder on the drive that should hold the library",
            value = "›",
            enabled = !serverRunning,
            onClick = { picker.launch(null) },
        )
    }
}
