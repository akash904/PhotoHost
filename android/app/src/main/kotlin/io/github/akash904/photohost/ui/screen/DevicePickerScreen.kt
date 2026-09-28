package io.github.akash904.photohost.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.akash904.photohost.backup.BackupScheduler
import io.github.akash904.photohost.backup.DeviceItem
import io.github.akash904.photohost.backup.DeviceMedia
import io.github.akash904.photohost.core.Prefs
import io.github.akash904.photohost.data.db.AppDatabase
import io.github.akash904.photohost.data.entity.SourceKind
import io.github.akash904.photohost.media.DeviceThumb
import io.github.akash904.photohost.ui.components.FastScroller
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
    val monthFormat = remember { java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()) }

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
    // Every folder, biggest first, with its count. It used to be the first four in alphabetical
    // order, in a row that could not scroll -- so "WhatsApp Images", often the largest folder on a
    // phone, sat fifth or later and could not be reached at all.
    val buckets = remember(all) {
        all.orEmpty().mapNotNull { it.bucketName }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
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
                    // One line, always. This is the element that gives way when the row is tight,
                    // and a wrapping title makes the whole bar jump taller mid-selection.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The two bulk actions are mutually exclusive by situation rather than both being
                // shown: "all new" is how a selection starts, "clear" is how it ends. Offering
                // both at once was what left five controls fighting over one row.
                if (selected.isEmpty()) {
                    if (visible.isNotEmpty()) {
                        TextButton(onClick = {
                            // "All" means everything not already backed up: re-offering known
                            // photos is harmless but makes the count meaningless.
                            selected = visible
                                .filterNot { it.sourceKey in backedUp }
                                .map { it.mediaStoreId }
                                .toSet()
                        }) { Text("All new") }
                    }
                } else {
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    Button(
                        onClick = {
                            BackupScheduler.runSelection(context, selected, prefs.backupWifiOnly)
                            onClose()
                        },
                    ) { Text("Back up") }
                }
            }

            if (buckets.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChipText("All · ${all.orEmpty().size}", bucketFilter == null) { bucketFilter = null }
                    for ((name, count) in buckets) {
                        FilterChipText("$name · $count", bucketFilter == name) { bucketFilter = name }
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
                else -> Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
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
                    // A camera roll runs to thousands of photos; the handle crosses a year in one drag.
                    FastScroller(
                        state = gridState,
                        itemCount = visible.size,
                        modifier = Modifier.matchParentSize(),
                        label = { i -> visible.getOrNull(i)?.let { monthFormat.format(java.util.Date(it.sortKey)) } },
                    )
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
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            // Android's own thumbnail, which also exists for videos; see DeviceThumbs.kt.
            model = ImageRequest.Builder(LocalContext.current).data(DeviceThumb(item.uri)).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (alreadyBackedUp && !selected) 0.45f else 1f),
        )

        // A picked tile is tinted and framed as well as ticked, so a selection reads at a glance
        // across a whole screen of photos rather than one corner at a time.
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(primary.copy(alpha = 0.22f))
                    .border(3.dp, primary),
            )
        }

        // The mark sits on its own dark or coloured disc: a white symbol on its own vanished
        // against every bright photo.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> primary
                        alreadyBackedUp -> Color(0xFF2E7D32)
                        else -> Color.Black.copy(alpha = 0.25f)
                    },
                )
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected || alreadyBackedUp) {
                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
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

/** A folder chip. Outlined, so a row of them reads as choices rather than as loose text. */
@Composable
private fun FilterChipText(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        fontSize = 12.sp,
        maxLines = 1,
        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
