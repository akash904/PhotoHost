package dev.gpicalter.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.gpicalter.media.BlurHashDecoder
import dev.gpicalter.net.TimelineItemDto
import dev.gpicalter.ui.Cell
import dev.gpicalter.ui.GridEntry
import dev.gpicalter.ui.JustifiedGrid
import dev.gpicalter.ui.LibraryViewModel
import dev.gpicalter.ui.components.addressLabel
import dev.gpicalter.ui.components.diagnose
import dev.gpicalter.ui.components.friendlyError

private const val GAP_DP = 2

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    vm: LibraryViewModel = viewModel(),
    onSetUp: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // Load the next page before the user reaches the bottom, so scrolling never visibly stalls.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 4) vm.loadMore()
            }
    }

    var confirmDelete by remember { mutableStateOf(false) }

    // Back should leave selection mode before it leaves the screen.
    BackHandler(enabled = state.selected.isNotEmpty()) { vm.clearSelection() }

    if (confirmDelete) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $n item${if (n == 1) "" else "s"}?") },
            text = {
                Text(
                    "They move to the trash. The originals on the phone that produced them are " +
                        "not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
    if (state.selected.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.clearSelection() }) { Text("✕") }
            Text(
                "${state.selected.size} selected",
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { vm.selectAllLoaded() }) { Text("All") }
            TextButton(onClick = { vm.favoriteSelected(true) }) { Text("★") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
    }

    // Shown above a grid that already has photos in it: some tiles are blank because they are not
    // rendered yet, and saying so is the difference between "wait" and "something is broken".
    if (state.preparing > 0 && state.items.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                "Preparing ${state.preparing} more photos…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }

    if (state.selected.isEmpty() && state.knownAddresses.isNotEmpty()) {
        ConnectionBar(
            active = state.endpoint,
            known = state.knownAddresses,
            // Without this the bar reads the fallback address as though it were live, and cheerfully
            // says "Connected over Tailscale" directly above the words "Library unreachable".
            live = state.reachable,
        )
    }

    Row(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val widthPx = with(density) { maxWidth.toPx().toInt() }
            val gapPx = with(density) { GAP_DP.dp.toPx().toInt() }
            // Denser on a phone, taller rows on a tablet or landscape.
            val targetPx = with(density) { (if (maxWidth < 500.dp) 128.dp else 180.dp).toPx().toInt() }

            val entries = remember(state.items, widthPx, targetPx) {
                JustifiedGrid.build(state.items, widthPx, targetPx, gapPx)
            }

            when {
                // Checked before unreachability, because "nobody has set this up" is a different
                // thing from "the server did not answer" and only one of them is the user's network.
                state.items.isEmpty() && !state.configured -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    ) {
                        Text("Not set up yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This phone has not been told whether it keeps your photos or views " +
                                "another phone's library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // The way out. An empty state that explains the situation and offers no
                        // action is a dead end, and this one is reached precisely by someone who
                        // dismissed the screen that would have asked.
                        Button(
                            onClick = onSetUp,
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Set up this phone") }
                    }
                }
                state.items.isEmpty() && !state.reachable -> Unreachable(
                    endpoint = state.endpoint,
                    error = state.error,
                    knownAddresses = state.knownAddresses,
                    retrying = state.loading,
                    onRetry = vm::refresh,
                )
                state.items.isEmpty() && state.loading -> Centered { CircularProgressIndicator() }
                // Connected, and the library is genuinely empty. Previously this said "tap Scan",
                // naming a control that existed only in the web UI -- so the one instruction the
                // screen gave could not be followed on the phone it was printed on.
                // Still rendering. Distinct from an empty library, because the tiles look the same
                // and only one of the two is worth waiting for.
                state.items.isEmpty() && state.preparing > 0 -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Preparing ${state.preparing} photos…",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "The server is making thumbnails. They appear as they finish.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                state.items.isEmpty() -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    ) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Photos already on the server appear here once it has looked for " +
                                "them. Or send this phone's own photos across.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Button(
                            onClick = { vm.rescan() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Look for photos on the server") }
                        TextButton(onClick = onOpenBackup) {
                            Text("Back up this phone's photos")
                        }
                    }
                }
                // The grid is a snapshot of a server that keeps changing underneath it -- a backup
                // running on another phone adds photos this app has no way to hear about. Pull to
                // refresh is the expected gesture for exactly that.
                else -> PullToRefreshBox(
                    isRefreshing = state.loading && state.items.isEmpty(),
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                  LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    entries.forEach { entry ->
                        when (entry) {
                            is GridEntry.DayHeader -> stickyHeader(key = "h_${entry.dayKey}") {
                                DayHeader(entry)
                            }
                            is GridEntry.PhotoRow -> item(key = "r_${entry.cells.first().item.id}") {
                                PhotoRow(entry, vm, state.selected, onOpen)
                            }
                        }
                    }
                    if (state.loading) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                  }
                }
            }
        }

        if (state.buckets.isNotEmpty()) Scrubber(vm)
    }
    }
}

@Composable
private fun DayHeader(entry: GridEntry.DayHeader) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(
            "  ${entry.count}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhotoRow(
    row: GridEntry.PhotoRow,
    vm: LibraryViewModel,
    selected: Set<Long>,
    onOpen: (Long) -> Unit,
) {
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        for (cell in row.cells) {
            val isSelected = cell.item.id in selected
            Tile(
                cell = cell,
                widthDp = with(density) { cell.width.toDp() },
                heightDp = with(density) { cell.height.toDp() },
                thumbUrl = vm.api.thumbUrl(cell.item.id),
                selected = isSelected,
                selectionMode = selected.isNotEmpty(),
                onClick = {
                    if (selected.isNotEmpty()) vm.toggleSelect(cell.item.id) else onOpen(cell.item.id)
                },
                onLongClick = { vm.toggleSelect(cell.item.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    cell: Cell,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    thumbUrl: String,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val item = cell.item
    // Painted before any network request, at the right size and roughly the right colours, so the
    // grid never shows grey boxes and never shifts as thumbnails arrive.
    val placeholder = remember(item.blurhash) {
        BlurHashDecoder.decode(item.blurhash)?.let { BitmapPainter(it.asImageBitmap()) }
    }

    Box(
        Modifier
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Long-press starts selection, which is the gesture every gallery uses; once in
            // selection mode a plain tap toggles rather than opening, so you can sweep through a
            // batch without the viewer appearing over it.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                }
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Text(
                text = "▶ ${JustifiedGrid.durationLabel(item.durationMs)}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        if (item.favorite) {
            Text(
                text = "★",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        if (selectionMode) {
            Text(
                text = if (selected) "☑" else "☐",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}

/**
 * Month rail. Each month is sized by how many photos it holds, so a busy month is a bigger target
 * than a quiet one, and tapping performs a keyset seek rather than scrolling through the gap.
 */
@Composable
private fun Scrubber(vm: LibraryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .width(36.dp)
            .fillMaxSize()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        var lastYear = ""
        for (bucket in state.buckets) {
            val year = bucket.bucket.take(4)
            val isYear = year != lastYear
            lastYear = year
            val weight = kotlin.math.sqrt(bucket.count.toDouble()).toFloat().coerceAtLeast(1f)
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxWidth()
                    .clickable { vm.jumpTo(bucket) },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = if (isYear) year else bucket.bucket.substring(5, 7),
                    fontSize = if (isYear) 10.sp else 9.sp,
                    fontWeight = if (isYear) FontWeight.Bold else FontWeight.Normal,
                    color = if (isYear) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

/**
 * A one-line summary of how the app is reaching the library, expanding to the full list.
 *
 * The app silently moves between a VPN address and a LAN one as networks come and go, which is the
 * behaviour people want and also completely invisible -- so a library that loads slowly over a VPN
 * while sitting next to the server looks like the app being slow rather than like a routing choice
 * the user could make differently.
 *
 * Collapsed by default, because this is an answer to an occasional question and not something worth
 * a permanent two lines above the photos.
 */
@Composable
private fun ConnectionBar(active: String, known: List<String>, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // One row per kind of route rather than per URL: the server advertises plain and TLS variants of
    // every address, and six rows that are really three distinct answers obscures the one fact this
    // is here to convey.
    val grouped = remember(active, known) {
        known.groupBy(::addressLabel)
            .map { (label, urls) -> label to (urls.firstOrNull { it == active } ?: urls.first()) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (live) "●" else "○",
                fontSize = 10.sp,
                color = if (live) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (live) "Connected over ${addressLabel(active)}" else "Not connected",
                Modifier.weight(1f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(if (expanded) "▲" else "▼", fontSize = 9.sp)
        }
        if (expanded) {
            grouped.forEach { (label, url) ->
                val isActive = live && url == active
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isActive) "●" else "○",
                        fontSize = 9.sp,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        url,
                        Modifier.weight(1f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "Tried top to bottom. The first that answers wins, and it is rechecked whenever the network changes.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Shown when no address answered.
 *
 * The old version stated the symptom and printed the exception, which tells the reader nothing they
 * can act on -- the phone already knows whether it has a network, whether it is on Wi-Fi and whether
 * Tailscale is up, and each of those has a different first thing to try. It says that instead, with
 * the technical detail kept but demoted.
 *
 * The retry matters more here than anywhere else in the app. Every cause listed is something the
 * user fixes by hand and then wants to say "now try again": the automatic re-probe covers changes
 * the system announces, but not a server being started on the other phone.
 */
@Composable
private fun Unreachable(
    endpoint: String,
    error: String?,
    knownAddresses: List<String>,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var showDetail by remember { mutableStateOf(false) }
    // Recomputed on each retry, since the whole point is that the user has just changed something.
    val diagnosis = remember(knownAddresses, retrying) { diagnose(context, knownAddresses) }

    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                diagnosis.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            friendlyError(error)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Column(Modifier.padding(top = 20.dp)) {
                diagnosis.steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Button(
                onClick = onRetry,
                enabled = !retrying,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (retrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (retrying) "Checking..." else "Try again")
            }
            Text(
                "Retries every address it knows, so it picks up a VPN or Wi-Fi you just turned on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )

            TextButton(
                onClick = { showDetail = !showDetail },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(if (showDetail) "Hide details" else "Details", fontSize = 12.sp) }

            if (showDetail) {
                Text(
                    "Last tried: $endpoint",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
}
