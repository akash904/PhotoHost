package dev.gpicalter.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpicalter.core.Prefs
import dev.gpicalter.service.MediaServerService
import dev.gpicalter.service.ServerState
import dev.gpicalter.storage.StorageReadiness
import dev.gpicalter.storage.StoreKind
import dev.gpicalter.storage.storageReadiness
import dev.gpicalter.ui.components.rememberDrivePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The screen for a phone that is not yet doing anything.
 *
 * Opening the app to an empty grid and an error is the worst version of this: the library is
 * unreachable because nothing is serving it, and the one control that would fix that is three taps
 * away inside Settings, under a heading you would only look at if you already knew the answer.
 *
 * So the question gets asked directly, once, with both answers on screen. There are exactly two
 * things this phone can be, and neither is a default worth guessing at: hosting is wrong for a
 * second phone that should be viewing, and viewing is wrong for the phone holding the photos.
 *
 * Not a permanent gate. "Not now" dismisses it for the session, because somebody who stopped the
 * server deliberately, or who wants to type an address in by hand, should not have to argue with a
 * setup screen to reach Settings.
 */
@Composable
fun SetupScreen(
    prefs: Prefs,
    server: ServerState.Snapshot,
    /** True when nobody has answered this yet, as opposed to a library whose server is simply down. */
    firstTime: Boolean,
    onScanPairingCode: () -> Unit,
    onOpenSettings: () -> Unit,
    onServing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Bumped whenever something changes what the answer would be, since preferences are plain
    // SharedPreferences and do not emit.
    var probeKey by remember { mutableIntStateOf(0) }
    var readiness by remember { mutableStateOf<StorageReadiness?>(null) }
    var starting by remember { mutableStateOf(false) }

    // Read through a key rather than straight off prefs at draw time. Reading it inline happens to
    // work, because `readiness` changes right after the backend does and drags a recomposition with
    // it, but that is an accident of ordering and not something to rely on.
    val backend = remember(probeKey) { prefs.backend }

    LaunchedEffect(probeKey) {
        // Off the main thread: on a drive that has been pulled this waits for a Binder timeout.
        readiness = withContext(Dispatchers.IO) {
            storageReadiness(context, prefs.backend, prefs.treeUri)
        }
    }

    // The server coming up is the answer to the question this screen asks, so it closes itself.
    // Deliberately a different callback from dismissal: somebody who just chose to host wants to
    // see what they are now hosting and the code to pair against it, not the photo grid, which at
    // that moment has nothing in it and a stale connection error from before the server existed.
    LaunchedEffect(server.running, server.error) {
        if (server.running) onServing()
        if (server.error != null) starting = false
    }

    val picker = rememberDrivePicker(prefs) { probeKey++ }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
        ) {
            Text(
                "What should this phone do?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (firstTime) {
                    "Nothing has been set up on this phone yet. You can change this later in Settings."
                } else {
                    "This phone holds the library, but nothing is serving it right now."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            ChoiceCard(
                title = "Keep the photos on this phone",
                body = "This phone stores the library and serves it to your other devices. " +
                    "Choose this on the phone the photos live on.",
            ) {
                when (readiness) {
                    null -> Text(
                        "Checking where photos are stored…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    StorageReadiness.FOLDER_MISSING -> {
                        Text(
                            "This phone is set up to use a USB drive, but it cannot be reached — " +
                                "either it is unplugged, or permission to it was lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch(null) }) { Text("Choose folder") }
                            OutlinedButton(onClick = {
                                prefs.backend = StoreKind.INTERNAL.name
                                probeKey++
                            }) { Text("Use this phone instead") }
                        }
                    }

                    StorageReadiness.READY -> {
                        Text(
                            "Photos will be kept on " +
                                if (backend == StoreKind.SAF.name) "the USB drive." else "this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (starting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    "Starting…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    starting = true
                                    MediaServerService.start(context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Start serving") }
                        }
                    }
                }

                server.error?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Could not start: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            ChoiceCard(
                title = "View photos kept on another phone or PC",
                body = "Scan the pairing code shown by the phone that holds the library. " +
                    "Choose this on a phone you only want to browse from.",
            ) {
                Button(onClick = onScanPairingCode, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan pairing code")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Type the address instead")
                }
            }

            Spacer(Modifier.height(24.dp))

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}
