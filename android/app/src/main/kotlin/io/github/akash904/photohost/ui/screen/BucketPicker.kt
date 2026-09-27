package io.github.akash904.photohost.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akash904.photohost.backup.MediaBucket
import io.github.akash904.photohost.backup.MediaBuckets
import io.github.akash904.photohost.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chooses which folders get backed up.
 *
 * Counts and sizes are shown because the decision is meaningless without them: "WhatsApp Images,
 * 2,431 items, 3.1 GB" is a choice, whereas a bare folder name is a guess.
 */
@Composable
fun BucketPicker(prefs: Prefs, hasPermission: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    var buckets by remember { mutableStateOf<List<MediaBucket>?>(null) }
    var selected by remember { mutableStateOf(prefs.backupBuckets) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission, expanded) {
        if (hasPermission && expanded && buckets == null) {
            buckets = withContext(Dispatchers.IO) { MediaBuckets.enumerate(context) }
            // First time through, pre-tick what looks like this phone's own camera output rather
            // than silently backing up every folder on the device.
            if (selected == null) {
                selected = MediaBuckets.defaultSelection(buckets.orEmpty())
            }
        }
    }

    val current = selected
    val list = buckets

    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                current == null -> "Folders to back up: not chosen yet"
                current.isEmpty() -> "Folders to back up: none"
                list == null -> "Folders to back up: ${current.size} selected"
                else -> {
                    val chosen = list.filter { it.id in current }
                    "Folders: ${chosen.size} selected · ${chosen.sumOf { it.itemCount }} items"
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (!expanded) return

    if (!hasPermission) {
        Text(
            "Grant photo access to list folders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (list == null) {
        Text("Reading folders…", style = MaterialTheme.typography.bodySmall)
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            selected = list.map { it.id }.toSet()
            prefs.backupBuckets = selected
            onChanged()
        }) { Text("All") }
        TextButton(onClick = {
            selected = emptySet()
            prefs.backupBuckets = emptySet()
            onChanged()
        }) { Text("None") }
        TextButton(onClick = {
            selected = MediaBuckets.defaultSelection(list)
            prefs.backupBuckets = selected
            onChanged()
        }) { Text("Camera only") }
    }

    Column(Modifier.fillMaxWidth()) {
        for (bucket in list) {
            val isOn = current?.contains(bucket.id) == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = (current ?: emptySet()).toMutableSet()
                        if (isOn) next.remove(bucket.id) else next.add(bucket.id)
                        selected = next
                        prefs.backupBuckets = next
                        onChanged()
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isOn, onCheckedChange = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        bucket.name,
                        fontSize = 14.sp,
                        fontWeight = if (bucket.looksLikeCamera) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        "${bucket.itemCount} items · ${formatSize(bucket.totalBytes)}" +
                            (bucket.relativePath?.let { " · $it" } ?: ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Text(
        "Unticking a folder only stops future uploads. Anything already in the library stays there.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Also used by the backup card's per-file progress. */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
