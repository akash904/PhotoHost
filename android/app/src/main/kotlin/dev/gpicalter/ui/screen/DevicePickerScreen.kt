package dev.gpicalter.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.gpicalter.backup.BackupScheduler
import dev.gpicalter.backup.DeviceItem
import dev.gpicalter.backup.DeviceMedia
import dev.gpicalter.core.Prefs
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Picks individual photos to back up, rather than whole folders.
 *
 * Folder selection answers "what should sync automatically, forever". This answers "send exactly
 * these, now" -- the case where you want three photos off a phone without committing to syncing the
 * folder they happen to live in.
 *
 * Items already on the server are shown dimmed and ticked rather than hidden. Hiding them would
 * make the grid disagree with the gallery it mirrors, and leave no way to see that a photo *is*
 * safely backed up -- which is usually the question being asked.
 */
@Composable
fun DevicePickerScreen(
    db: AppDatabase,
    prefs: Prefs,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DeviceItem>?>(null) }
    var backedUp by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var bucketFilter by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()

    // Drag-select state. The selection as it was when the drag began is kept so that dragging back
    // over your own path undoes it: without that, a sweep can only ever grow, and overshooting by
    // one tile means starting again.
    var dragAnchor by remember { mutableStateOf<Int?>(null) }
    var dragAdding by remember { mutableStateOf(true) }
    var selectionBeforeDrag by remember { mutableStateOf(setOf<Long>()) }
    // Pixels per frame, sign giving direction. Zero means the finger is not near an edge.
    var autoScroll by remember { mutableFloatStateOf(0f) }

    // Dragging to the bottom of the screen has to keep going, or a selection can never be longer
    // than one screenful -- which is most of the point of dragging rather than tapping.
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0f) return@LaunchedEffect
        while (isActive) {
            gridState.scrollBy(autoScroll)
            delay(16)
        }
    }

    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { DeviceMedia.list(context) }
        // One query for the whole set rather than one per tile: at a few thousand photos, per-item
        // lookups while scrolling would make the grid stutter.
        val known = withContext(Dispatchers.IO) {
            loaded.mapNotNull { item ->
                db.fingerprints().find(SourceKind.MEDIASTORE, item.sourceKey)?.let { item.sourceKey }
            }.toSet()
        }
        items = loaded
        backedUp = known
    }

    val all = items
    val buckets = remember(all) {
        all.orEmpty().mapNotNull { it.bucketName }.distinct().sorted()
    }
    val visible = remember(all, bucketFilter) {
        all.orEmpty().filter { bucketFilter == null || it.bucketName == bucketFilter }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Text(
                    text = if (selected.isEmpty()) "Pick photos" else "${selected.size} selected",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                }
                if (visible.isNotEmpty()) {
                    TextButton(onClick = {
                        // "All" means everything not already backed up: re-offering known photos
                        // is harmless but makes the count meaningless.
                        selected = visible
                            .filterNot { it.sourceKey in backedUp }
                            .map { it.mediaStoreId }
                            .toSet()
                    }) { Text("All new") }
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        BackupScheduler.runSelection(context, selected, prefs.backupWifiOnly)
                        onClose()
                    },
                ) { Text("Back up") }
            }

            if (buckets.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChipText("All", bucketFilter == null) { bucketFilter = null }
                    for (b in buckets.take(4)) {
                        FilterChipText(b, bucketFilter == b) { bucketFilter = b }
                    }
                }
            }

            // A gesture nobody knows about is not a feature. One quiet line, and only until the
            // first selection exists, by which point it has been discovered or is not needed.
            if (selected.isEmpty() && visible.isNotEmpty()) {
                Text(
                    "Tap to pick, or hold and drag across photos to select many",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            when {
                all == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Reading photos…") }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No photos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        // Keyed to the visible list so the closure never indexes a stale one after
                        // the bucket filter changes.
                        .pointerInput(visible) {
                            val edge = 96.dp.toPx()
                            fun apply(from: Int, to: Int) {
                                val lo = minOf(from, to)
                                val hi = maxOf(from, to)
                                selected = selectionBeforeDrag.toMutableSet().apply {
                                    for (i in lo..hi) {
                                        val id = visible.getOrNull(i)?.mediaStoreId ?: continue
                                        if (dragAdding) add(id) else remove(id)
                                    }
                                }
                            }
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val index = gridState.indexAt(offset) ?: return@detectDragGesturesAfterLongPress
                                    val id = visible.getOrNull(index)?.mediaStoreId
                                        ?: return@detectDragGesturesAfterLongPress
                                    // The first tile decides the whole sweep: starting on an unselected
                                    // photo selects, starting on a selected one clears. One gesture
                                    // does both jobs without a mode to set first.
                                    dragAdding = id !in selected
                                    selectionBeforeDrag = selected
                                    dragAnchor = index
                                    apply(index, index)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val anchor = dragAnchor ?: return@detectDragGesturesAfterLongPress
                                    val y = change.position.y
                                    autoScroll = when {
                                        y < edge -> -((edge - y) / edge) * 24f
                                        y > size.height - edge -> ((y - (size.height - edge)) / edge) * 24f
                                        else -> 0f
                                    }
                                    gridState.indexAt(change.position)?.let { apply(anchor, it) }
                                },
                                onDragEnd = { dragAnchor = null; autoScroll = 0f },
                                onDragCancel = { dragAnchor = null; autoScroll = 0f },
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible, key = { it.mediaStoreId }) { item ->
                        val already = item.sourceKey in backedUp
                        DeviceTile(
                            item = item,
                            selected = item.mediaStoreId in selected,
                            alreadyBackedUp = already,
                            onClick = {
                                selected = if (item.mediaStoreId in selected) {
                                    selected - item.mediaStoreId
                                } else {
                                    selected + item.mediaStoreId
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which grid item is under a point.
 *
 * Falls through to the last visible item when the finger is below everything drawn, because
 * dragging past the final row is how a sweep to the end of the list ends, and returning nothing
 * there would drop the last tile the user clearly meant to include.
 */
private fun LazyGridState.indexAt(offset: Offset): Int? {
    val items = layoutInfo.visibleItemsInfo
    items.firstOrNull { item ->
        offset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
            offset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
    }?.let { return it.index }

    val last = items.lastOrNull() ?: return null
    return if (offset.y > last.offset.y + last.size.height) last.index else null
}

@Composable
private fun DeviceTile(
    item: DeviceItem,
    selected: Boolean,
    alreadyBackedUp: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            // Coil reads MediaStore content URIs directly, so the system thumbnail is used rather
            // than decoding a full-size photo per tile.
            model = ImageRequest.Builder(LocalContext.current).data(item.uri).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (alreadyBackedUp && !selected) 0.45f else 1f),
        )
        Text(
            text = when {
                selected -> "☑"
                alreadyBackedUp -> "✓"
                else -> "☐"
            },
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
        )
        if (item.isVideo) {
            Text(
                "▶",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
            )
        }
    }
}

@Composable
private fun FilterChipText(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
