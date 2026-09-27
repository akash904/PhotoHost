package io.github.akash904.photohost.probe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akash904.photohost.storage.StoreKind

/**
 * The control screen: pick a storage backend, run the M0 probes against it, and start or stop the
 * server. Still named "probe" because M0's diagnostics are the bulk of it; the real library UI
 * arrives at M4 and is served to browsers rather than drawn here.
 */
class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    ProbeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(modifier: Modifier = Modifier, vm: ProbeViewModel = viewModel()) {
    val lines by vm.lines.collectAsState()
    val tree by vm.treeUri.collectAsState()
    val backend by vm.backend.collectAsState()
    val running by vm.running.collectAsState()
    val autostart by vm.autostart.collectAsState()
    val server by vm.server.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onTreeGranted(uri)
    }

    // Without POST_NOTIFICATIONS on API 33+ the foreground-service notification is suppressed.
    // The service still runs, but with no way to see or stop it.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("Internal", backend == StoreKind.INTERNAL, !running) { vm.setBackend(StoreKind.INTERNAL) }
            Toggle("USB drive", backend == StoreKind.SAF, !running) { vm.setBackend(StoreKind.SAF) }
            OutlinedButton(onClick = { picker.launch(null) }, enabled = !running) {
                Text(if (tree == null) "Pick drive" else "Re-pick")
            }
        }

        Mono(
            when (backend) {
                StoreKind.INTERNAL -> "Android/data/io.github.akash904.photohost/files/library"
                StoreKind.SAF -> tree?.toString() ?: "no drive picked"
            },
            Modifier.padding(top = 6.dp),
        )

        // ---- server ----
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (server.running) {
                Button(onClick = { vm.stopServer() }) { Text("Stop server") }
            } else {
                Button(onClick = { vm.startServer() }) { Text("Start server") }
            }
            Text("Boot", style = MaterialTheme.typography.bodySmall)
            Switch(checked = autostart, onCheckedChange = { vm.setAutostart(it) })
        }

        if (server.running) {
            Mono("serving ${server.backend} | heartbeats ${server.heartbeats}", Modifier.padding(top = 4.dp))
            server.urls.forEach { Mono(it) }
            Mono("pair:  /pair?c=${vm.token()}")
        }
        server.error?.let { Mono("ERROR: $it", Modifier.padding(top = 4.dp)) }

        // ---- probes ----
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { vm.runAll() }, enabled = !running) { Text("Run all") }
            OutlinedButton(onClick = { vm.runWrite(256) }, enabled = !running) { Text("256 MiB") }
            OutlinedButton(onClick = { vm.runUnplugDrill() }, enabled = !running) { Text("Unplug") }
            if (running) OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.saveLog() }) { Text("Save log") }
            OutlinedButton(onClick = { vm.clear() }) { Text("Clear") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun Toggle(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}
