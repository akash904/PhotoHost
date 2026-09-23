package dev.gpicalter.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpicalter.core.Prefs
import dev.gpicalter.service.MediaServerService
import dev.gpicalter.service.ServerState
import dev.gpicalter.ui.components.CardHeader
import dev.gpicalter.ui.components.SettingsCard
import dev.gpicalter.ui.components.StatusDot

/**
 * What this phone is serving, and the code another phone scans to reach it.
 *
 * Its own tab rather than a row inside Settings. On the phone holding the library this is the
 * screen that actually gets used: you start the server, then you show somebody the code. Both of
 * those were previously reached by going into Settings and scrolling past backup and remote-access
 * options that have nothing to do with either.
 *
 * The pairing code is drawn inline rather than behind a button. It is the reason to come here, and
 * a QR that needs a tap to appear is a QR you have to explain.
 */
@Composable
fun ServerScreen(prefs: Prefs, server: ServerState.Snapshot, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val payload = remember(server.urls, server.httpsUrl, server.tlsFingerprint, server.pairUrls) {
        if (!server.running) {
            null
        } else {
            pairingPayload(
                urls = server.urls,
                token = prefs.token(),
                httpsUrl = server.httpsUrl,
                fingerprint = server.tlsFingerprint,
                alternates = server.pairUrls,
            )
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            SettingsCard {
                CardHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = server.running)
                        Text(
                            text = if (server.running) "Serving" else "Not serving",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                        )
                        if (server.running) {
                            OutlinedButton(onClick = { MediaServerService.stop(context) }) {
                                Text("Stop")
                            }
                        } else {
                            Button(onClick = { MediaServerService.start(context) }) { Text("Start") }
                        }
                    }

                    if (server.running) {
                        Spacer(Modifier.height(8.dp))
                        server.urls.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Photos are kept on ${server.backend.lowercase()} storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Other devices cannot reach your photos while this is off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            if (server.running) {
                PairingCode(payload, qrSize = 240)
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Start serving to get a pairing code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
