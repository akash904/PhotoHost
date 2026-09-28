package io.github.akash904.photohost.ui.screen

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.akash904.photohost.backup.DeviceItem
import io.github.akash904.photohost.backup.FreeUpSpace
import io.github.akash904.photohost.di.AppContainer
import io.github.akash904.photohost.media.DeviceThumb
import kotlinx.coroutines.launch

/**
 * Settings > Free up space: removes from this phone the photos its backup library holds safely.
 *
 * Every photo that would go is shown, ticked, and can be kept with a tap, and the folders looked at
 * are named. A first version showed only a count; in testing it removed nine camera photos from a
 * person who expected three test pictures from another folder, and nothing on screen could have told
 * them. A delete screen has to show what it deletes.
 *
 * Deletion goes through Android's own trash request, so the system shows its confirmation and the
 * photos stay restorable from the Gallery's trash for 30 days. See FreeUpSpace for which qualify.
 */
@Composable
fun FreeUpSpaceScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()
    val target = remember { container.prefs.backupLibrary() }
    val tool = remember { FreeUpSpace(context, container.db, container.backupApi, container.prefs) }

    var keepRecent by remember { mutableStateOf(true) }
    var includeWhatsApp by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<FreeUpSpace.Scan?>(null) }
    var scanKey by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    // Photos the user tapped to keep. Everything else offered is ticked for removal.
    var kept by remember { mutableStateOf(setOf<Long>()) }
    // What was handed to the trash request, for the message once Android answers.
    var pending by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }

    BackHandler { onClose() }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        outcome = if (result.resultCode == Activity.RESULT_OK) {
            "Moved ${pending.size} to this phone's trash, freeing ${formatSize(pending.sumOf { it.size })}. " +
                "They can be restored from the Gallery's trash for 30 days."
        } else {
            "Nothing was removed."
        }
        pending = emptyList()
        scanKey++
    }

    LaunchedEffect(keepRecent, includeWhatsApp, scanKey) {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@LaunchedEffect
        scan = null
        kept = emptySet()
        scan = tool.scan(
            FreeUpSpace.Options(keepRecentDays = if (keepRecent) 30 else 0, includeWhatsApp = includeWhatsApp),
        )
    }

    fun moveToTrash(items: List<DeviceItem>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || items.isEmpty()) return
        working = true
        scope.launch {
            // Asked again about exactly these, now: the scan may be minutes old.
            val confirmed = tool.reconfirm(items)
            working = false
            if (confirmed == null) {
                outcome = "Could not re-check with the library just now, so nothing was removed."
                return@launch
            }
            if (confirmed.isEmpty()) {
                outcome = "The library no longer confirms these photos, so nothing was removed."
                scanKey++
                return@launch
            }
            pending = confirmed
            val request = MediaStore.createTrashRequest(context.contentResolver, confirmed.map { it.uri }, true)
            trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    val ready = scan as? FreeUpSpace.Scan.Ready
    val chosen = ready?.removable?.filterNot { it.mediaStoreId in kept }.orEmpty()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                Text("Free up space", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            // One lazy grid for the whole page, the explanations spanning its full width, so a list
            // of thousands of photos still scrolls smoothly under them.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Header(
                            targetName = target?.name,
                            keepRecent = keepRecent,
                            onKeepRecent = { keepRecent = it },
                            includeWhatsApp = includeWhatsApp,
                            onIncludeWhatsApp = { includeWhatsApp = it },
                            outcome = outcome,
                            scan = scan,
                            chosenCount = chosen.size,
                        )
                    }
                }
                if (ready != null) {
                    items(ready.removable, key = { it.mediaStoreId }) { item ->
                        val keep = item.mediaStoreId in kept
                        RemovalTile(item, removing = !keep) {
                            kept = if (keep) kept - item.mediaStoreId else kept + item.mediaStoreId
                        }
                    }
                }
            }

            if (ready != null && ready.removable.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { moveToTrash(chosen) },
                        enabled = !working && chosen.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) {
                        Text(
                            when {
                                working -> "Re-checking…"
                                chosen.isEmpty() -> "Nothing ticked"
                                else -> "Move ${chosen.size} · ${formatSize(chosen.sumOf { it.size })} to trash"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    targetName: String?,
    keepRecent: Boolean,
    onKeepRecent: (Boolean) -> Unit,
    includeWhatsApp: Boolean,
    onIncludeWhatsApp: (Boolean) -> Unit,
    outcome: String?,
    scan: FreeUpSpace.Scan?,
    chosenCount: Int,
) {
    if (targetName == null) {
        Text("Choose where to back up first (Settings, Back up to). Only photos that library holds can be removed from this phone.")
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Text("Freeing up space needs Android 11 or later, which moves photos to a recoverable trash.")
        return
    }

    Text(
        "Removes photos and videos from this phone that $targetName already holds. They move to " +
            "this phone's trash, where they can be restored for 30 days.",
        style = MaterialTheme.typography.bodyMedium,
    )
    ToggleRow("Keep the last 30 days on this phone", keepRecent, onChange = onKeepRecent)
    ToggleRow(
        "Include WhatsApp",
        includeWhatsApp,
        note = "WhatsApp shows a removed photo as missing in the chat it came from.",
        onChange = onIncludeWhatsApp,
    )

    // Said plainly because it is true: until libraries copy to each other, the library becomes
    // the only copy of anything removed here.
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            "After this, $targetName holds the only copy of these photos. If that is one disk or one " +
                "phone, keep another backup of it somewhere else.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }

    outcome?.let { Text(it, fontWeight = FontWeight.SemiBold) }

    when (scan) {
        null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Checking with $targetName…")
        }
        FreeUpSpace.Scan.LibraryTooOld -> Text(
            "$targetName needs updating before photos can be removed: its version cannot confirm " +
                "which photos it holds safely. Nothing will be removed until it can.",
            color = MaterialTheme.colorScheme.error,
        )
        is FreeUpSpace.Scan.Failed -> Text(scan.message, color = MaterialTheme.colorScheme.error)
        is FreeUpSpace.Scan.Ready -> {
            Text(
                "Looking in: " + scan.folders.ifEmpty { listOf("no folders") }.joinToString(", ") +
                    " — the folders backup covers. Change them under Back up in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scan.removable.isEmpty()) {
                Text("Nothing to remove right now.", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    "${scan.removable.size} photos and videos · ${formatSize(scan.removableBytes)} are safely " +
                        "in $targetName. $chosenCount ticked to remove — tap any photo to keep it.",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val stays = buildList {
                if (scan.keptRecent > 0) add("${scan.keptRecent} from the last 30 days")
                if (scan.keptWhatsApp > 0) add("${scan.keptWhatsApp} from WhatsApp")
                if (scan.keptNotBackedUp > 0) add("${scan.keptNotBackedUp} not backed up yet, or changed since")
                if (scan.keptNotConfirmed > 0) add("${scan.keptNotConfirmed} the library does not confirm")
            }
            if (stays.isNotEmpty()) {
                Text(
                    "Staying on this phone: " + stays.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A photo on offer: ticked while it is going, dimmed with an empty ring once tapped to keep. */
@Composable
private fun RemovalTile(item: DeviceItem, removing: Boolean, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(DeviceThumb(item.uri)).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (removing) 1f else 0.4f),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (removing) primary else Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (removing) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (item.isVideo) {
            Text(
                "▶",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
