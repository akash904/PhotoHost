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

private const val GAP_DP = 2

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    vm: LibraryViewModel = viewModel(),
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

    if (state.selected.isEmpty() && state.knownAddresses.isNotEmpty()) {
        ConnectionBar(active = state.endpoint, known = state.knownAddresses)
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
                state.items.isEmpty() && !state.reachable -> Unreachable(
                    endpoint = state.endpoint,
                    error = state.error,
                    retrying = state.loading,
                    onRetry = vm::refresh,
                )
                state.items.isEmpty() && state.loading -> Centered { CircularProgressIndicator() }
                state.items.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add photos to the library and tap Scan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
private fun ConnectionBar(active: String, known: List<String>) {
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
            Text("●", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                "Connected over ${addressLabel(active)}",
                Modifier.weight(1f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(if (expanded) "▲" else "▼", fontSize = 9.sp)
        }
        if (expanded) {
            grouped.forEach { (label, url) ->
                val isActive = url == active
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
 * Names a route from its address, rather than storing a label alongside it.
 *
 * The server does send labels, but deriving them here keeps the stored candidate list a plain list
 * of URLs -- and the ranges are unambiguous enough that there is nothing to get wrong: 100.64/10 is
 * what Tailscale hands out, and the RFC 1918 ranges are what a home router hands out.
 */
private fun addressLabel(url: String): String {
    val hostPort = url.substringAfter("://")
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringAfter('[').substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    return when {
        host == "127.0.0.1" || host == "::1" -> "This phone"
        octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 -> "Tailscale"
        octets.size == 4 && octets[0] == 10 -> "Wi-Fi"
        octets.size == 4 && octets[0] == 192 && octets[1] == 168 -> "Wi-Fi"
        octets.size == 4 && octets[0] == 172 && octets[1] in 16..31 -> "Wi-Fi"
        host.contains(':') -> "IPv6"
        else -> host
    }
}

/**
 * Shown when no address answered.
 *
 * The retry matters more here than anywhere else in the app. The usual reasons for landing on this
 * screen -- a VPN coming up, joining Wi-Fi, the server being started on the other phone -- are all
 * things the user has just fixed by hand, and they arrive here wanting to say "try again now". The
 * automatic re-probe covers the cases the system reports as a network change, but not the ones it
 * has no way to know about, and without a button the only recovery was to guess that the empty
 * screen could still be pulled down.
 */
@Composable
private fun Unreachable(
    endpoint: String,
    error: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Library unreachable", style = MaterialTheme.typography.titleMedium)
            Text(
                endpoint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = onRetry,
                enabled = !retrying,
                modifier = Modifier.padding(top = 20.dp),
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
                "Every known address is retried, so this also picks up a VPN or Wi-Fi you just turned on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Still failing? In Settings, start the server here or point this app at another one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
}
