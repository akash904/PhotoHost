package io.github.akash904.photohost.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.di.AppContainer
import io.github.akash904.photohost.storage.LibraryMoveWorker
import io.github.akash904.photohost.storage.MoveState
import io.github.akash904.photohost.storage.SafStore
import io.github.akash904.photohost.storage.StorageReadiness
import io.github.akash904.photohost.storage.StoreKind
import io.github.akash904.photohost.storage.storageReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Written into a library folder on the phone, so re-picking it later is recognised as ours. */
private const val MARKER = ".photohost-library"

/**
 * Checks a picked folder and sets it up, returning why it was refused, or null when it is usable.
 *
 * A folder on the phone's own storage is different from one on a USB drive: Android's media index
 * covers it, so the phone's gallery -- and PhotoHost's own backup and "Free up space" screens --
 * would see the library's files as ordinary photos on the phone. Backing a library up into itself
 * is merely pointless, but "Free up space" deleting photos because they are safely in the library
 * would delete the library. So a phone folder gets a `.nomedia` file, which takes it out of the
 * media index. That is also why it must be empty: `.nomedia` in a folder that already holds
 * photos, say DCIM, would hide the person's own pictures from every gallery app. A folder that
 * already carries [MARKER] is one PhotoHost set up before, and is fine to pick again.
 *
 * USB drives are left as they were: picking a drive that already holds photos, and having them
 * indexed, is a use this was built for.
 */
private fun prepareFolder(context: android.content.Context, uri: Uri): String? {
    val volume = runCatching { DocumentsContract.getTreeDocumentId(uri).substringBefore(':') }
        .getOrNull() ?: return "That folder cannot be used."
    if (volume != "primary") return null
    return try {
        val store = SafStore(context, uri)
        val names = store.list("").map { it.name }
        if (names.isNotEmpty() && MARKER !in names) {
            return "Choose an empty folder. This one already has files in it, and the library " +
                "would hide them from your gallery. You can make a new folder from the picker."
        }
        if (MARKER !in names) store.openWrite(MARKER, "application/octet-stream").use { }
        if (".nomedia" !in names) store.openWrite(".nomedia", "application/octet-stream").use { }
        null
    } catch (t: Throwable) {
        "PhotoHost could not write to that folder."
    }
}

/** "Phone storage › Pictures/PhotoHost" or "USB 1234-5678 › Photos", from a tree URI. */
fun describeFolder(treeUri: Uri?): String? {
    val docId = treeUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        ?: return null
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "")
    val where = if (volume == "primary") "Phone storage" else "USB $volume"
    return if (path.isEmpty()) where else "$where › $path"
}

private fun describe(backend: String, tree: Uri?): String =
    if (backend == StoreKind.SAF.name) describeFolder(tree) ?: "the folder you chose" else "app storage"

/** Opens the folder picker, and switches to app storage; both ask about moving the photos. */
class LocationChanger(val chooseFolder: () -> Unit, val switchTo: (backend: String, tree: Uri?) -> Unit)

/**
 * Changing where the library lives, shared by the Storage card and the setup screen.
 *
 * A change of location offers to bring the photos along. Without that, a library switched to a new
 * folder looks empty -- the files are still in the old place and the server only looks in one --
 * which was recoverable but alarming, and left the person to move files by hand, which the
 * library's records would not have followed. Moving is the default; switching without moving stays
 * possible, and is the only choice when the current location cannot be reached.
 *
 * With no photos yet there is nothing to ask, so the switch just happens.
 */
@Composable
fun rememberLocationChanger(prefs: Prefs, onChanged: () -> Unit): LocationChanger {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    data class Proposal(val backend: String, val tree: Uri?, val files: Int, val canMove: Boolean)

    var refusal by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<Proposal?>(null) }

    fun apply(backend: String, tree: Uri?) {
        prefs.backend = backend
        if (tree != null) prefs.treeUri = tree
        onChanged()
    }

    fun propose(backend: String, tree: Uri?) {
        scope.launch {
            val container = AppContainer.get(context)
            val (files, reachable) = withContext(Dispatchers.IO) {
                val store = container.buildStore()
                val files = container.db.assetFiles().onVolume(container.ensureVolume(store)).size
                files to (storageReadiness(context, prefs.backend, prefs.treeUri) == StorageReadiness.READY)
            }
            if (files == 0) apply(backend, tree) else proposal = Proposal(backend, tree, files, reachable)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persisting the grant is the easy half to get wrong: without it the permission dies with
        // the process, and the folder silently needs re-picking after every reboot.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val reason = prepareFolder(context, uri)
        if (reason != null) {
            // Released again, so a refused folder does not linger among the app's grants.
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            refusal = reason
        } else {
            propose(StoreKind.SAF.name, uri)
        }
    }

    refusal?.let { reason ->
        AlertDialog(
            onDismissRequest = { refusal = null },
            title = { Text("Choose a different folder") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = {
                    refusal = null
                    picker.launch(null)
                }) { Text("Choose again") }
            },
            dismissButton = { TextButton(onClick = { refusal = null }) { Text("Cancel") } },
        )
    }

    proposal?.let { p ->
        val here = describe(prefs.backend, prefs.treeUri)
        val there = describe(p.backend, p.tree)
        AlertDialog(
            onDismissRequest = { proposal = null },
            title = { Text(if (p.canMove) "Move your photos too?" else "Switch without your photos?") },
            text = {
                Text(
                    if (p.canMove) {
                        "${p.files} files are kept in $here. Move them to $there? Each one is " +
                            "copied and checked before the original is deleted, and the server " +
                            "stays off until the move is done."
                    } else {
                        "$here cannot be reached, so its ${p.files} files cannot be moved. If you " +
                            "switch anyway they will be missing from the library until you switch back."
                    },
                )
            },
            confirmButton = {
                if (p.canMove) {
                    TextButton(onClick = {
                        proposal = null
                        prefs.beginMove(p.backend, p.tree)
                        LibraryMoveWorker.start(context)
                        onChanged()
                    }) { Text("Move") }
                } else {
                    TextButton(onClick = {
                        proposal = null
                        apply(p.backend, p.tree)
                    }) { Text("Switch") }
                }
            },
            dismissButton = {
                Row {
                    if (p.canMove) {
                        TextButton(onClick = {
                            proposal = null
                            apply(p.backend, p.tree)
                        }) { Text("Don't move") }
                    }
                    TextButton(onClick = { proposal = null }) { Text("Cancel") }
                }
            },
        )
    }

    return remember(picker) {
        LocationChanger(
            chooseFolder = { picker.launch(null) },
            switchTo = { backend, tree -> propose(backend, tree) },
        )
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
 * It also locks while a move is running or unfinished, for the same reason the server will not
 * start then: the library is in two places at once.
 */
@Composable
fun StorageCard(prefs: Prefs, serverRunning: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var probeKey by remember { mutableIntStateOf(0) }
    var readiness by remember { mutableStateOf<StorageReadiness?>(null) }
    val move by MoveState.state.collectAsState()

    val backend = remember(probeKey, move.running) { prefs.backend }
    val treeUri = remember(probeKey, move.running) { prefs.treeUri }
    val unfinishedMove = remember(probeKey, move.running) { prefs.moveTarget }
    val usingSaf = backend == StoreKind.SAF.name
    val folder = remember(treeUri) { describeFolder(treeUri) }
    val locked = serverRunning || move.running || unfinishedMove != null

    LaunchedEffect(probeKey, move.running) {
        // Off the main thread: on a drive that has been pulled this waits for a Binder timeout.
        readiness = withContext(Dispatchers.IO) { storageReadiness(context, backend, treeUri) }
    }

    val changer = rememberLocationChanger(prefs) { probeKey++ }

    SettingsCard(modifier) {
        CardHeader {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = readiness == StorageReadiness.READY)
                Text(
                    text = when {
                        !usingSaf -> "Kept in app storage"
                        folder != null -> "Kept in a folder you chose"
                        else -> "No folder chosen yet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            MonoLine(
                if (usingSaf) folder ?: "no folder picked"
                else "Android/data/io.github.akash904.photohost/files/library",
            )
            if (readiness == StorageReadiness.FOLDER_MISSING) {
                Text(
                    "This folder cannot be reached — its drive is unplugged, or permission to it " +
                        "was lost. The server falls back to app storage until it is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                move.running -> {
                    Text(
                        "Moving the library… ${move.done} of ${move.total}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { if (move.total == 0) 0f else move.done.toFloat() / move.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                unfinishedMove != null -> {
                    Text(
                        (move.message?.let { "$it " } ?: "") +
                            "The library is partway through moving to " +
                            "${describe(unfinishedMove.first, unfinishedMove.second)}, so the " +
                            "server cannot start until it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { LibraryMoveWorker.start(context) }) { Text("Resume the move") }
                }
                move.message != null -> Text(
                    move.message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (move.failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
            title = "App storage",
            subtitle = "Private to PhotoHost and always available, but deleted if the app is " +
                "uninstalled.",
            enabled = !locked,
            onClick = { if (usingSaf) changer.switchTo(StoreKind.INTERNAL.name, null) },
            trailing = {
                RadioButton(
                    selected = !usingSaf,
                    enabled = !locked,
                    onClick = { if (usingSaf) changer.switchTo(StoreKind.INTERNAL.name, null) },
                )
            },
        )
        RowDivider()
        SettingsRow(
            title = "A folder you choose",
            subtitle = "On this phone or a USB drive. Stays put if the app is uninstalled.",
            enabled = !locked,
            onClick = { if (!usingSaf) useFolder(changer, treeUri) },
            trailing = {
                RadioButton(
                    selected = usingSaf,
                    enabled = !locked,
                    onClick = { if (!usingSaf) useFolder(changer, treeUri) },
                )
            },
        )
        RowDivider()
        SettingsRow(
            title = if (treeUri == null) "Choose a folder" else "Choose a different folder",
            subtitle = "On this phone, pick or create an empty folder, such as Pictures/PhotoHost",
            value = "›",
            enabled = !locked,
            onClick = { changer.chooseFolder() },
        )
    }
}

/**
 * True while a library move runs, for the Start buttons: the server refuses to start then anyway,
 * and a button that can only produce an error should not be offered.
 */
@Composable
fun libraryMoving(): Boolean = MoveState.state.collectAsState().value.running

/** Back to the folder used before, when there is one; otherwise pick one. */
private fun useFolder(changer: LocationChanger, treeUri: Uri?) {
    if (treeUri == null) changer.chooseFolder() else changer.switchTo(StoreKind.SAF.name, treeUri)
}
